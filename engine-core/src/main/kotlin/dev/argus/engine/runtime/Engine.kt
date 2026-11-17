package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ClockVariable
import dev.argus.engine.model.LiveSystemClock
import dev.argus.engine.model.SpanPolicy
import dev.argus.engine.model.SpanRecord
import dev.argus.engine.model.SpanRestoreStep
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.allActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.Locale

/** @param now fornitore di epoch-millis (Android: System::currentTimeMillis). */
class Engine(
    private val store: AutomationStore,
    private val executor: ActionExecutor,
    private val evaluator: ConditionEvaluator,
    private val matcher: TriggerMatcher,
    private val firePolicy: FirePolicy,
    private val audit: AuditSink = NoopAuditSink,
    private val journal: ExecutionJournal = NoopExecutionJournal,
    private val executionIds: ExecutionIdFactory = StableExecutionIdFactory,
    private val resolvedExecutor: ResolvedActionExecutor? = null,
    /** Attesa dei programmi P4 (`wait`): Android inietta una pausa che sveglia il dispositivo (B19). */
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** «Pausa automazioni» (0.4.1, F21): se attiva, nessuna regola scatta e lo scatto saltato va nel Log. */
    private val firingPause: AutomationPause = AutomationPause.NEVER,
    /** 0.5.0: orologio e lingua della variabile `clock` (ora allo scatto). Default: orologio vivo del telefono. */
    private val programClock: Clock = LiveSystemClock,
    private val programLocale: () -> Locale = ClockVariable::appLocale,
    /** 0.8 `confirm`: chiede Esegui/Annulla invece di eseguire. Default: non si può chiedere ⇒ non esegue. */
    private val confirmations: ConfirmationGate = ConfirmationGate.UNAVAILABLE,
    /** 0.8 `ask`: domande con pulsanti dentro un programma. Default: nessuna domanda ⇒ il programma si ferma. */
    private val questions: QuestionAsker = QuestionAsker.UNAVAILABLE,
    /** 0.8 `screen_call` con `onlyUnknownCallers`: chi chiama è nel perimetro? Default: non si sa ⇒ non esegue. */
    private val callerScope: CallerScopeGate = CallerScopeGate.UNAVAILABLE,
    /** 0.8 regole-intervallo: quali regole sono in corso e con quali valori di prima. Android: preferenze. */
    private val spans: SpanStore = MemorySpanStore(),
    /** 0.8 regole-intervallo: lettura dei valori prima del cambio. Default: niente si legge ⇒ niente si rimette. */
    private val spanDevice: SpanDevice = SpanDevice.UNAVAILABLE,
    private val now: () -> Long,
) {
    companion object {
        const val MIN_GENERATIVE_COOLDOWN_MS = 60_000L
        private val PROGRAM_POLICY_STOPS = setOf(
            "invalid_program",
            "trigger_mismatch",
            "binding_unavailable",
            "binding_state_unavailable",
            "condition_state_unavailable",
            "variable_unavailable",
            "interpolation_policy_missing",
            "interpolation_malformed",
            "interpolation_too_long",
            "resolved_action_invalid",
            "taint_blocked",
            // Blocchi dell'interpolatore: il dato esterno non può stare in quel punto (0.7.2).
            "shell_quote_context",
            "shell_numeric_context",
            "json_quote_context",
            "url_host_changed",
            "write_setting_key_interpolated",
            "remote_egress_blocked",
            // 0.8: il destinatario di un SMS non è il numero di chi ha chiamato o scritto, o non è un numero.
            "sms_number_not_sender",
            "sms_number_invalid",
            "p4_invoke_llm_v2_unsupported",
            ProgramInterpreter.GENERATIVE_CALL_LIMIT,
        )

        /**
         * 0.8: il programma si ferma perché l'utente non ha risposto a una domanda senza scelta
         * predefinita. Non è un guasto: va nel Log come esecuzione annullata, fuori dagli avvisi di salute.
         */
        private val PROGRAM_USER_STOPS = setOf(ProgramInterpreter.ASK_TIMEOUT)

        /** Programma fermato perché la regola è stata spenta, cancellata o modificata durante l'esecuzione. */
        private const val PROGRAM_CANCELLED = "cancelled"

        /**
         * 0.8: l'identità della fine di un intervallo per [eventId]: claim, journal e Log propri, così la
         * deduplica della fine non si confonde con quella dell'inizio anche se un evento combaciasse con entrambe.
         */
        internal fun exitEventId(eventId: TriggerEventId): TriggerEventId {
            val candidate = "${eventId.value}#span_exit"
            if (candidate.length <= 512) return TriggerEventId(candidate)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(eventId.value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return TriggerEventId("span_exit:$digest")
        }
    }

    data class FireOutcome(
        val automation: Automation,
        val actions: List<Action>,
        val results: List<ActionResult>,
        val eventId: TriggerEventId,
        val executionId: ExecutionId,
    )

    /**
     * Ripristini degli intervalli: mai due insieme per la stessa regola (fine, spegnimento, modifica). Il lock
     * copre solo la presa e il rilascio della regola in [restoring], mai l'esecuzione: un ripristino che cambia
     * la modalità può far scattare altre regole nello stesso processo.
     */
    private val spanMutex = Mutex()
    private val restoring = mutableSetOf<AutomationId>()

    /** [block] con la regola presa per il ripristino; null se un altro ripristino della stessa regola è in corso. */
    private suspend fun <T> whileRestoring(id: AutomationId, block: suspend () -> T): T? {
        if (!spanMutex.withLock { restoring.add(id) }) return null
        try {
            return block()
        } finally {
            withContext(NonCancellable) { spanMutex.withLock { restoring.remove(id) } }
        }
    }

    /** [stateProvider] resta lazy; nello stesso batch riceve soltanto il delta non ancora letto. */
    suspend fun onTrigger(
        envelope: TriggerEnvelope,
        stateProvider: suspend (StateReadRequest) -> DeviceState,
    ): List<FireOutcome> {
        val event = envelope.event
        val candidates = when (event) {
            is TriggerEvent.Registered -> listOfNotNull(store.get(event.automationId))
            else -> store.armed()
        }.filter { it.status == AutomationStatus.ARMED && it.enabled }
            .sortedWith(compareBy({ it.priority }, { it.id.value }))

        val batchNow = now()
        val state = BatchState(stateProvider)
        val outcomes = mutableListOf<FireOutcome>()
        for (automation in candidates) {
            fireCandidate(automation, envelope, batchNow, state, stateProvider, confirmed = null)
                ?.let(outcomes::add)
        }
        return outcomes
    }

    /**
     * 0.8 `confirm`: l'utente ha toccato Esegui. La regola deve essere ancora armata, con `confirm` e con
     * la STESSA impronta vista quando la domanda è partita; poi l'evento originale ripassa da tutto ciò
     * che controlla uno scatto normale (trigger, pausa, policy e capacità, condizioni). Il cooldown l'ha
     * già preso lo scatto che ha chiesto; l'id evento della domanda rende un doppio tocco un duplicato.
     */
    suspend fun onConfirmed(
        confirmed: ConfirmedFire,
        stateProvider: suspend (StateReadRequest) -> DeviceState,
    ): FireOutcome? {
        val batchNow = now()
        val automation = store.get(confirmed.automationId)
        if (automation == null ||
            automation.status != AutomationStatus.ARMED ||
            !automation.enabled ||
            automation.confirm == null ||
            automation.approvalFingerprint != confirmed.approvalFingerprint
        ) {
            recordAudit(
                AuditEvent(
                    automationId = confirmed.automationId,
                    kind = AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                    atMillis = batchNow,
                    detail = ConfirmationCodes.STALE,
                    eventId = confirmed.runEventId,
                ),
            )
            return null
        }
        return fireCandidate(
            automation = automation,
            envelope = confirmed.envelope,
            batchNow = batchNow,
            state = BatchState(stateProvider),
            stateProvider = stateProvider,
            confirmed = confirmed,
        )
    }

    /**
     * 0.8 `ask`: la risposta (o la scadenza) di una domanda è arrivata dopo la morte del processo che la
     * poneva. Il programma prosegue da quella domanda solo se la regola è ancora armata con la STESSA
     * impronta; trigger, policy e capacità si ricontrollano, pausa e condizioni no (il programma le aveva
     * già passate, come uno vivo). Un'esecuzione nuova (id evento della domanda: un doppio tocco è un
     * duplicato), senza cooldown né conferma.
     */
    suspend fun onQuestionResumed(
        resumed: ResumedQuestion,
        stateProvider: suspend (StateReadRequest) -> DeviceState,
    ): FireOutcome? {
        val batchNow = now()
        val automation = store.get(resumed.automationId)
        if (automation == null ||
            automation.status != AutomationStatus.ARMED ||
            !automation.enabled ||
            automation.approvalFingerprint != resumed.approvalFingerprint ||
            !AutomationSchema.requiresP4(automation)
        ) {
            recordAudit(
                AuditEvent(
                    automationId = resumed.automationId,
                    kind = AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                    atMillis = batchNow,
                    detail = ProgramInterpreter.ASK_RESUME_STALE,
                    eventId = resumed.runEventId,
                ),
            )
            return null
        }
        return fireCandidate(
            automation = automation,
            envelope = resumed.envelope,
            batchNow = batchNow,
            state = BatchState(stateProvider),
            stateProvider = stateProvider,
            confirmed = null,
            resumed = resumed,
        )
    }

    /** 0.8 `confirm`: Annulla o nessuna risposta in tempo. Solo un codice chiuso nel Log, mai un errore. */
    suspend fun onConfirmationClosed(automationId: AutomationId, reason: ConfirmationClosed) {
        recordAudit(
            AuditEvent(
                automationId = automationId,
                kind = AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                atMillis = now().coerceAtLeast(0),
                detail = reason.code,
            ),
        )
    }

    /**
     * 0.8 regole-intervallo: ogni intervallo in corso la cui regola non può più finirlo da sola (cancellata,
     * spenta, da rivedere, riapprovata con un'altra impronta, senza più una fine) rimette subito i valori di
     * prima e si chiude. Android lo chiama all'avvio e a ogni cambio delle regole: così un intervallo non resta
     * mai applicato a una versione approvata diversa da quella che l'ha aperto. Ritorna le regole ripristinate.
     */
    suspend fun reconcileSpans(): List<AutomationId> {
        val restored = mutableListOf<AutomationId>()
        val open = try {
            spans.all()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return restored
        }
        for (span in open.sortedBy { it.automationId.value }) {
            val current = try {
                store.get(span.automationId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            if (spanStillOwnedBy(current, span)) continue
            if (restoreStaleSpan(span)) restored += span.automationId
        }
        return restored
    }

    private fun spanStillOwnedBy(current: Automation?, span: ActiveSpan): Boolean =
        current != null && current.status == AutomationStatus.ARMED && current.enabled &&
            current.exit != null && current.approvalFingerprint == span.approvalFingerprint

    /** Lettura di stato lazy e incrementale, condivisa dalle regole candidate dello stesso evento. */
    private class BatchState(private val stateProvider: suspend (StateReadRequest) -> DeviceState) {
        private var cachedRequest = StateReadRequest.EMPTY
        private var cachedState = DeviceState()

        suspend fun read(request: StateReadRequest): DeviceState {
            if (request.isEmpty) return DeviceState()
            val missing = request.missingFrom(cachedRequest)
            if (!missing.isEmpty) {
                val read = try {
                    stateProvider(missing)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    DeviceState()
                }
                cachedState = cachedState.merge(missing.project(read))
                cachedRequest += missing
            }
            return request.project(cachedState)
        }

        private fun DeviceState.merge(other: DeviceState): DeviceState = DeviceState(
            values = values + other.values,
            foregroundApp = other.foregroundApp ?: foregroundApp,
            location = other.location ?: location,
            queryValues = queryValues + other.queryValues,
            facts = facts.merge(other.facts),
            stored = stored + other.stored,
            userMode = other.userMode ?: userMode,
        )
    }

    /** Un trigger combacia con l'evento per [phase]: un evento programmato per l'altra fase non conta. */
    private fun matchesPhase(spec: Trigger, event: TriggerEvent, phase: SpanPhase): Boolean {
        if (!matcher.matches(spec, event)) return false
        val scheduled = (event as? TriggerEvent.Registered)?.spanPhase ?: return true
        return scheduled == phase
    }

    /**
     * Uno scatto di [automation] per [envelope]. Con [confirmed] è l'esecuzione che l'utente ha
     * confermato: l'evento di claim è quello della domanda, il cooldown non si riprende e non si chiede di
     * nuovo. Con [resumed] è il programma ripreso a una domanda dopo un riavvio: niente pausa né condizioni
     * (già passate), niente cooldown né conferma. Null se la regola non ha eseguito azioni da riportare.
     *
     * 0.8 regole-intervallo: l'evento appartiene alla fine se combacia col trigger di fine E la regola è in
     * corso; altrimenti all'inizio se combacia col trigger d'inizio. Una fine fuori da un intervallo non fa nulla.
     * Conferma e domande riprese valgono solo per l'inizio.
     */
    private suspend fun fireCandidate(
        automation: Automation,
        envelope: TriggerEnvelope,
        batchNow: Long,
        state: BatchState,
        stateProvider: suspend (StateReadRequest) -> DeviceState,
        confirmed: ConfirmedFire?,
        resumed: ResumedQuestion? = null,
    ): FireOutcome? {
        val event = envelope.event
        // L'esecuzione confermata o ripresa ha un'identità propria (claim, journal, Log), distinta dallo
        // scatto che l'ha preceduta.
        val runEventId = confirmed?.runEventId ?: resumed?.runEventId ?: envelope.id
        var executionId: ExecutionId? = null
        var claimed = false
        val actionResults = mutableListOf<ActionResult>()
        try {
            if (event is TriggerEvent.Registered &&
                automation.approvalFingerprint != event.approvalFingerprint
            ) {
                recordAudit(
                    AuditEvent(
                        automationId = automation.id,
                        kind = AuditKind.BLOCKED_POLICY,
                        atMillis = batchNow,
                        detail = "stale_trigger_registration",
                        eventId = runEventId,
                    ),
                )
                return null
            }

            val exit = automation.exit
            val startMatch = matchesPhase(automation.trigger, event, SpanPhase.START)
            val exitMatch = exit != null && confirmed == null && resumed == null &&
                matchesPhase(exit.trigger, event, SpanPhase.EXIT)
            if (!startMatch && !exitMatch) return null
            var span: ActiveSpan? = null
            if (exit != null) {
                span = spans.get(automation.id)
                if (span != null && span.approvalFingerprint != automation.approvalFingerprint) {
                    // Un intervallo aperto da un'altra versione approvata: si rimette e si chiude prima di tutto.
                    restoreStaleSpan(span)
                    span = null
                }
            }
            val phase = when {
                exitMatch && span != null -> SpanPhase.EXIT
                startMatch -> SpanPhase.START
                // La fine di un intervallo che non è in corso: niente da rimettere, niente da eseguire.
                else -> return null
            }

            // «Pausa automazioni» (F21): dopo il match, così il Log mostra solo le regole che
            // sarebbero davvero scattate; prima di policy, condizioni e claim, così lo scatto
            // saltato non consuma cooldown né deduplica e alla ripresa non si recupera.
            // 0.7.2: decisa per regola, così la pausa di un gruppo (etichetta) segue la stessa strada.
            // Un programma ripreso a una domanda era già partito: come uno vivo, la pausa non lo ferma.
            val pauseCode = if (resumed == null) firingPause.pauseCodeFor(automation.id) else null
            if (pauseCode != null) {
                recordAudit(
                    AuditEvent(
                        automationId = automation.id,
                        kind = AuditKind.BLOCKED_POLICY,
                        atMillis = batchNow,
                        detail = pauseCode,
                        eventId = if (phase == SpanPhase.EXIT) exitEventId(runEventId) else runEventId,
                    ),
                )
                return null
            }

            when (val decision = firePolicy.evaluate(automation, event, phase)) {
                FirePolicyDecision.Allow -> Unit
                is FirePolicyDecision.Block -> {
                    if (decision.needsReview) {
                        automation.approvalFingerprint?.let { fingerprint ->
                            // Lifecycle (task #31-B): la quarantena toglie la regola dalle
                            // armate; evento emesso solo se la transizione è avvenuta davvero.
                            // Il code esatto del blocco resta nel BLOCKED_POLICY successivo.
                            if (store.markNeedsReviewIfApproved(automation.id, fingerprint)) {
                                recordAudit(
                                    AuditEvent(
                                        automationId = automation.id,
                                        kind = AuditKind.RULE_NEEDS_REVIEW,
                                        atMillis = batchNow,
                                        detail = "fire_policy",
                                        eventId = runEventId,
                                    ),
                                )
                                // 0.8: una regola da rivedere non finisce più da sola il suo intervallo.
                                if (exit != null) reconcileSpans()
                            }
                        }
                    }
                    recordAudit(
                        AuditEvent(
                            automationId = automation.id,
                            kind = AuditKind.BLOCKED_POLICY,
                            atMillis = batchNow,
                            detail = decision.code,
                            eventId = if (phase == SpanPhase.EXIT) exitEventId(runEventId) else runEventId,
                        ),
                    )
                    return null
                }
            }

            if (phase == SpanPhase.EXIT) {
                return fireExit(automation, envelope, batchNow, state, stateProvider)
            }

            if (resumed == null && automation.conditions != null) {
                val conditionState = state.read(StateReadPlanner.forCondition(automation.conditions))
                val conditionResult = evaluator.result(automation.conditions, conditionState)
                when (conditionResult) {
                    ConditionEvaluator.Result.MET -> Unit
                    ConditionEvaluator.Result.NOT_MET,
                    ConditionEvaluator.Result.STATE_UNAVAILABLE,
                    -> {
                        recordAudit(
                            AuditEvent(
                                automation.id,
                                AuditKind.CONDITIONS_NOT_MET,
                                batchNow,
                                detail = if (
                                    conditionResult == ConditionEvaluator.Result.STATE_UNAVAILABLE
                                ) {
                                    "condition_state_unavailable"
                                } else {
                                    ""
                                },
                                eventId = runEventId,
                            ),
                        )
                        return null
                    }
                }
            }

            // 0.8: `onlyUnknownCallers` vale per tutta la regola: un contatto, o un'appartenenza che non si è
            // potuta stabilire, la ferma prima di qualunque azione (anche un SMS messo prima del filtro).
            if (resumed == null && CallerScopeGate.appliesTo(automation)) {
                val scopeCode = try {
                    callerScope.closedCode(automation, event)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    CallerScopeGate.CALLER_UNVERIFIED
                }
                if (scopeCode != null) {
                    recordAudit(
                        AuditEvent(
                            automation.id,
                            AuditKind.CONDITIONS_NOT_MET,
                            batchNow,
                            detail = scopeCode,
                            eventId = runEventId,
                        ),
                    )
                    return null
                }
            }

            executionId = executionIds.create(automation.id, runEventId)
            when (
                val claim = store.claimFire(
                    FireClaimRequest(
                        automationId = automation.id,
                        eventId = runEventId,
                        executionId = executionId,
                        atMillis = batchNow,
                        // Il cooldown lo ha già preso lo scatto che ha chiesto conferma o posto la domanda.
                        cooldownMs = if (confirmed != null || resumed != null) 0 else effectiveCooldown(automation),
                    ),
                )
            ) {
                FireClaimResult.Claimed -> claimed = true
                is FireClaimResult.Duplicate -> {
                    recordAudit(
                        AuditEvent(
                            automation.id,
                            AuditKind.SUPPRESSED_DUPLICATE,
                            batchNow,
                            eventId = runEventId,
                            executionId = claim.existingExecutionId,
                        ),
                    )
                    return null
                }
                is FireClaimResult.Cooldown -> {
                    finishJournal(
                        emptyList<ActionResult>().completion(
                            executionId,
                            batchNow,
                            ExecutionStatus.SUPPRESSED_COOLDOWN,
                        ),
                    )
                    recordAudit(
                        AuditEvent(
                            automation.id,
                            AuditKind.SUPPRESSED_COOLDOWN,
                            batchNow,
                            detail = "retry_at=${claim.retryAtMillis}",
                            eventId = runEventId,
                            executionId = executionId,
                        ),
                    )
                    return null
                }
                FireClaimResult.NotEligible -> {
                    finishJournal(
                        emptyList<ActionResult>().completion(
                            executionId,
                            batchNow,
                            ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE,
                        ),
                    )
                    recordAudit(
                        AuditEvent(
                            automation.id,
                            AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                            batchNow,
                            eventId = runEventId,
                            executionId = executionId,
                        ),
                    )
                    return null
                }
            }

            val approvalFingerprint = requireNotNull(automation.approvalFingerprint) {
                "Automazione approvata priva di fingerprint"
            }

            // 0.8 `confirm`: lo scatto è valido e ha preso cooldown e deduplica; ora si chiede invece di eseguire.
            val confirm = automation.confirm
            if (confirm != null && confirmed == null && resumed == null) {
                val requested = try {
                    confirmations.request(
                        ConfirmationRequest(
                            automationId = automation.id,
                            ruleName = automation.name,
                            approvalFingerprint = approvalFingerprint,
                            envelope = envelope,
                            executionId = executionId,
                            timeoutMs = confirm.timeoutMs,
                            requestedAtMillis = batchNow,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    ConfirmationRequestResult.UNAVAILABLE
                }
                val (kind, code, status) = when (requested) {
                    ConfirmationRequestResult.REQUESTED -> Triple(
                        AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                        ConfirmationCodes.REQUESTED,
                        ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE,
                    )
                    ConfirmationRequestResult.ALREADY_PENDING -> Triple(
                        AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                        ConfirmationCodes.PENDING,
                        ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE,
                    )
                    ConfirmationRequestResult.UNAVAILABLE -> Triple(
                        AuditKind.BLOCKED_POLICY,
                        ConfirmationCodes.UNAVAILABLE,
                        ExecutionStatus.FAILED,
                    )
                }
                finishJournal(emptyList<ActionResult>().completion(executionId, batchNow, status))
                claimed = false
                recordAudit(
                    AuditEvent(
                        automation.id,
                        kind,
                        batchNow,
                        detail = code,
                        eventId = runEventId,
                        executionId = executionId,
                    ),
                )
                return null
            }

            // 0.8 regole-intervallo: le azioni d'inizio stanno per girare, la regola è in corso. Un intervallo già
            // aperto resta quello: i valori già registrati non si registrano di nuovo.
            val recording = if (exit != null) {
                spans.open(automation.id, approvalFingerprint, batchNow)
                exit.restore
            } else {
                false
            }
            val beforeLeaf: suspend (Action) -> Unit = if (recording) {
                { action -> recordBefore(automation.id, approvalFingerprint, action, runEventId, executionId) }
            } else {
                NO_HOOK
            }

            val run = runActions(
                automation = automation,
                trigger = automation.trigger,
                actions = automation.actions,
                event = event,
                envelope = envelope,
                runEventId = runEventId,
                executionId = executionId,
                approvalFingerprint = approvalFingerprint,
                batchNow = batchNow,
                state = state,
                stateProvider = stateProvider,
                resumed = resumed,
                reserved = emptyList(),
                beforeLeaf = beforeLeaf,
            )
            actionResults += run.results
            when (val finished = finishRun(automation, run, actionResults, runEventId, executionId, batchNow)) {
                RunEnd.Cancelled -> {
                    claimed = false
                    return null
                }
                RunEnd.Stopped -> {
                    claimed = false
                    return FireOutcome(automation, automation.actions, actionResults.toList(), runEventId, executionId)
                }
                RunEnd.Completed -> Unit
            }
            claimed = false
            recordAudit(
                AuditEvent(
                    automation.id,
                    AuditKind.FIRED,
                    batchNow,
                    eventId = runEventId,
                    executionId = executionId,
                ),
            )
            return FireOutcome(automation, automation.actions, actionResults.toList(), runEventId, executionId)
        } catch (e: CancellationException) {
            if (claimed && executionId != null) withContext(NonCancellable) {
                finishJournal(actionResults.completion(executionId, batchNow, ExecutionStatus.CANCELLED))
            }
            throw e
        } catch (e: Exception) {
            if (claimed && executionId != null)
                finishJournal(actionResults.completion(executionId, batchNow, ExecutionStatus.FAILED))
            recordAudit(
                AuditEvent(
                    automation.id,
                    AuditKind.ERROR,
                    batchNow,
                    detail = e::class.simpleName ?: "error",
                    eventId = runEventId,
                    executionId = executionId,
                ),
            )
            return null
        }
    }

    /**
     * 0.8: la fine di un intervallo in corso (policy già passata). Deduplica propria (mai la pausa dell'inizio),
     * poi, con `restore`, rimette i valori registrati al contrario (ognuno col suo controllo di capacità: uno che
     * manca non ferma gli altri), chiude l'intervallo ed esegue le azioni di fine, che hanno la loro pausa. Le
     * condizioni della regola valgono solo per l'inizio: la fine rimette sempre.
     */
    private suspend fun fireExit(
        automation: Automation,
        envelope: TriggerEnvelope,
        batchNow: Long,
        state: BatchState,
        stateProvider: suspend (StateReadRequest) -> DeviceState,
    ): FireOutcome? {
        val exit = requireNotNull(automation.exit)
        val event = envelope.event
        val approvalFingerprint = requireNotNull(automation.approvalFingerprint) {
            "Automazione approvata priva di fingerprint"
        }
        val runEventId = exitEventId(envelope.id)
        val executionId = executionIds.create(automation.id, runEventId)
        var claimed = false
        val results = mutableListOf<ActionResult>()
        try {
            when (
                val claim = store.claimFire(
                    FireClaimRequest(
                        automationId = automation.id,
                        eventId = runEventId,
                        executionId = executionId,
                        atMillis = batchNow,
                        cooldownMs = 0,
                        phase = SpanPhase.EXIT,
                    ),
                )
            ) {
                FireClaimResult.Claimed -> claimed = true
                is FireClaimResult.Duplicate -> {
                    recordAudit(
                        AuditEvent(
                            automation.id,
                            AuditKind.SUPPRESSED_DUPLICATE,
                            batchNow,
                            eventId = runEventId,
                            executionId = claim.existingExecutionId,
                        ),
                    )
                    return null
                }
                is FireClaimResult.Cooldown, FireClaimResult.NotEligible -> {
                    finishJournal(
                        emptyList<ActionResult>().completion(executionId, batchNow, ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE),
                    )
                    recordAudit(
                        AuditEvent(
                            automation.id,
                            AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                            batchNow,
                            eventId = runEventId,
                            executionId = executionId,
                        ),
                    )
                    return null
                }
            }

            // Ripristino e chiusura insieme, con la regola presa: una regola spenta nello stesso istante non rimette
            // due volte. Se l'intervallo nel frattempo è stato chiuso, questa fine non ha più nulla da fare. La
            // chiusura viene dopo il ripristino: se il processo muore a metà, l'intervallo resta e si rimette poi.
            // I passi di ripristino vanno nel journal dopo i numeri delle azioni di fine, così non si sovrappongono.
            val restorePaths = mutableListOf<ActionPath>()
            val stillOpen = whileRestoring(automation.id) {
                val span = spans.get(automation.id)?.takeIf { it.approvalFingerprint == approvalFingerprint }
                    ?: return@whileRestoring false
                if (exit.restore) {
                    results += restoreRecords(
                        records = span.records,
                        event = event,
                        automationId = automation.id,
                        approvalFingerprint = approvalFingerprint,
                        eventId = runEventId,
                        executionId = executionId,
                        priority = automation.priority,
                        firstPath = exit.actions.size + 1,
                        journalAt = batchNow,
                        paths = restorePaths,
                    )
                }
                spans.close(automation.id, approvalFingerprint)
                true
            } ?: false
            if (!stillOpen) {
                finishJournal(
                    emptyList<ActionResult>().completion(executionId, batchNow, ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE),
                )
                claimed = false
                return null
            }

            if (exit.actions.isNotEmpty()) {
                val retryAt = spans.claimExitCooldown(automation.id, batchNow, effectiveCooldown(automation))
                if (retryAt != null) {
                    // La pausa delle azioni di fine non ferma mai il ripristino, già fatto qui sopra.
                    recordAudit(
                        AuditEvent(
                            automation.id,
                            AuditKind.SUPPRESSED_COOLDOWN,
                            batchNow,
                            detail = SpanCodes.EXIT_ACTIONS_COOLDOWN,
                            eventId = runEventId,
                            executionId = executionId,
                        ),
                    )
                } else {
                    val run = runActions(
                        automation = automation,
                        trigger = exit.trigger,
                        actions = exit.actions,
                        event = event,
                        envelope = envelope,
                        runEventId = runEventId,
                        executionId = executionId,
                        approvalFingerprint = approvalFingerprint,
                        batchNow = batchNow,
                        state = state,
                        stateProvider = stateProvider,
                        resumed = null,
                        reserved = restorePaths,
                        beforeLeaf = NO_HOOK,
                    )
                    results += run.results
                    when (finishRun(automation, run, results, runEventId, executionId, batchNow)) {
                        RunEnd.Cancelled -> {
                            claimed = false
                            return null
                        }
                        RunEnd.Stopped -> {
                            claimed = false
                            return FireOutcome(automation, exit.actions, results.toList(), runEventId, executionId)
                        }
                        else -> Unit
                    }
                    claimed = false
                    recordAudit(
                        AuditEvent(automation.id, AuditKind.FIRED, batchNow, SpanCodes.EXIT, runEventId, executionId),
                    )
                    return FireOutcome(automation, exit.actions, results.toList(), runEventId, executionId)
                }
            }
            finishJournal(results.completion(executionId, now().coerceAtLeast(0)))
            claimed = false
            recordAudit(AuditEvent(automation.id, AuditKind.FIRED, batchNow, SpanCodes.EXIT, runEventId, executionId))
            return FireOutcome(automation, exit.actions, results.toList(), runEventId, executionId)
        } catch (e: CancellationException) {
            if (claimed) withContext(NonCancellable) {
                finishJournal(results.completion(executionId, batchNow, ExecutionStatus.CANCELLED))
            }
            throw e
        } catch (e: Exception) {
            if (claimed) finishJournal(results.completion(executionId, batchNow, ExecutionStatus.FAILED))
            recordAudit(
                AuditEvent(
                    automation.id,
                    AuditKind.ERROR,
                    batchNow,
                    detail = e::class.simpleName ?: "error",
                    eventId = runEventId,
                    executionId = executionId,
                ),
            )
            return null
        }
    }

    /**
     * Rimette un intervallo che la sua regola non può più chiudere (vedi [reconcileSpans]) e lo chiude. Nessun
     * claim né journal (la regola può non esistere più): l'esito va nel Log come codici chiusi.
     */
    private suspend fun restoreStaleSpan(span: ActiveSpan): Boolean {
        val atMillis = now().coerceAtLeast(0)
        return whileRestoring(span.automationId) {
            val current = spans.get(span.automationId)
            if (current == null || current.approvalFingerprint != span.approvalFingerprint ||
                current.startedAtMillis != span.startedAtMillis
            ) return@whileRestoring false
            val eventId = TriggerEventId("span_restore:${span.approvalFingerprint.value}:${span.startedAtMillis}")
            val executionId = executionIds.create(span.automationId, eventId)
            val results = restoreRecords(
                records = current.records,
                // Nessun evento ha chiuso l'intervallo: il ripristino è un gesto dell'app sulla regola approvata.
                event = TriggerEvent.ManualFired(span.automationId, span.approvalFingerprint),
                automationId = span.automationId,
                approvalFingerprint = span.approvalFingerprint,
                eventId = eventId,
                executionId = executionId,
                priority = 0,
                firstPath = 1,
                journalAt = null,
                paths = mutableListOf(),
            )
            results.filterIsInstance<ActionResult.Failure>().forEach { failure ->
                recordAudit(
                    AuditEvent(
                        span.automationId,
                        AuditKind.ERROR,
                        atMillis,
                        detail = failure.reason.takeIf { it.startsWith("span_") } ?: SpanCodes.RESTORE_FAILED,
                        eventId = eventId,
                    ),
                )
            }
            spans.close(span.automationId, span.approvalFingerprint)
            recordAudit(
                AuditEvent(
                    span.automationId,
                    AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                    atMillis,
                    detail = SpanCodes.RESTORED_RULE_CHANGED,
                    eventId = eventId,
                ),
            )
            true
        } ?: false
    }

    /**
     * I valori di [records] rimessi al contrario (l'ultimo cambiato torna per primo). Ogni passo ha il controllo
     * di capacità della policy e l'esecutore normale: un passo che fallisce non ferma gli altri. Con [journalAt]
     * ogni passo va nel journal dell'esecuzione, ai percorsi da [firstPath] in poi (aggiunti a [paths]).
     */
    private suspend fun restoreRecords(
        records: List<SpanRecord>,
        event: TriggerEvent,
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventId: TriggerEventId,
        executionId: ExecutionId,
        priority: Int,
        firstPath: Int,
        journalAt: Long?,
        paths: MutableList<ActionPath>,
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        for (record in records.asReversed()) {
            val steps = SpanPolicy.restoreSteps(record)
            if (steps == null) {
                results += ActionResult.Failure(SpanCodes.RESTORE_FAILED)
                continue
            }
            for (step in steps) {
                val index = results.size
                val path = ActionPath((firstPath + index).toString())
                val context = FireContext(
                    event = event,
                    state = DeviceState(),
                    automationId = automationId,
                    approvalFingerprint = approvalFingerprint,
                    eventId = eventId,
                    executionId = executionId,
                    actionIndex = index,
                    priority = priority,
                    actionPath = path.value,
                )
                val result = try {
                    when (step) {
                        is SpanRestoreStep.Run -> when (val decision = firePolicy.evaluateRestore(step.action)) {
                            FirePolicyDecision.Allow -> executor.execute(step.action, context)
                            is FirePolicyDecision.Block -> ActionResult.Failure(decision.code)
                        }
                        SpanRestoreStep.ClearUserMode -> spanDevice.clearUserMode(context)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    ActionResult.Failure(SpanCodes.RESTORE_FAILED)
                }
                results += result
                paths += path
                if (journalAt != null) {
                    recordJournalAction(
                        ActionJournalEntry(
                            executionId = executionId,
                            actionIndex = index,
                            actionType = when (step) {
                                is SpanRestoreStep.Run -> step.action.journalType()
                                SpanRestoreStep.ClearUserMode -> dev.argus.engine.model.ActionTypeIds.SET_USER_MODE
                            },
                            outcome = result.journalOutcome(),
                            atMillis = now().coerceAtLeast(0),
                            errorCode = result.journalCode(),
                            actionPath = path.value,
                        ),
                    )
                }
            }
        }
        return results
    }

    /**
     * 0.8: [action] sta per cambiare un valore che la fine rimette. La prima volta per intervallo se ne legge il
     * valore di prima (mai inventato: se non si legge, la chiave resta vista e il Log lo dice con un codice chiuso).
     */
    private suspend fun recordBefore(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        action: Action,
        eventId: TriggerEventId,
        executionId: ExecutionId,
    ) {
        val slot = SpanPolicy.slotOf(action) ?: return
        try {
            val span = spans.get(automationId)?.takeIf { it.approvalFingerprint == approvalFingerprint } ?: return
            if (slot.key in span.handled) return
            val value = try {
                spanDevice.read(slot)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val record = value?.let { SpanRecord(slot, it) }?.takeIf { SpanPolicy.restoreSteps(it) != null }
            spans.record(automationId, approvalFingerprint, slot.key, record)
            if (record == null) {
                recordAudit(
                    AuditEvent(
                        automationId,
                        AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                        now().coerceAtLeast(0),
                        detail = SpanCodes.VALUE_UNREADABLE,
                        eventId = eventId,
                        executionId = executionId,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Lo store non risponde: il valore non è registrato e quindi non si rimette, ma l'azione approvata parte.
            recordAudit(
                AuditEvent(
                    automationId,
                    AuditKind.SUPPRESSED_NOT_ELIGIBLE,
                    now().coerceAtLeast(0),
                    detail = SpanCodes.VALUE_UNREADABLE,
                    eventId = eventId,
                    executionId = executionId,
                ),
            )
        }
    }

    private class RunResult(val results: List<ActionResult>, val stopCode: String?, val program: Boolean)

    private enum class RunEnd { Completed, Stopped, Cancelled }

    /**
     * Esegue [actions] (quelle d'inizio o quelle di fine) per [trigger]: come programma P4 se la regola lo è,
     * altrimenti in sequenza. [beforeLeaf] parte prima di ogni azione foglia già risolta; [reserved] sono i
     * percorsi del journal già usati in questa esecuzione (i passi di ripristino).
     */
    private suspend fun runActions(
        automation: Automation,
        trigger: Trigger,
        actions: List<Action>,
        event: TriggerEvent,
        envelope: TriggerEnvelope,
        runEventId: TriggerEventId,
        executionId: ExecutionId,
        approvalFingerprint: ApprovalFingerprint,
        batchNow: Long,
        state: BatchState,
        stateProvider: suspend (StateReadRequest) -> DeviceState,
        resumed: ResumedQuestion?,
        reserved: List<ActionPath>,
        beforeLeaf: suspend (Action) -> Unit,
    ): RunResult {
        val offset = reserved.size
        if (AutomationSchema.requiresP4(automation)) {
            val indexes = linkedMapOf<ActionPath, Int>()
            fun indexFor(path: ActionPath): Int = indexes.getOrPut(path) { offset + indexes.size }
            val questionContext = QuestionContext(
                automationId = automation.id,
                ruleName = automation.name,
                approvalFingerprint = approvalFingerprint,
                executionId = executionId,
                eventId = runEventId,
                // L'evento originale: con lui una domanda può far proseguire il programma dopo un riavvio.
                envelope = envelope,
            )
            val program = ProgramInterpreter(
                runner = ProgramActionRunner { resolved, path ->
                    val index = indexFor(path)
                    beforeLeaf(resolved.action)
                    val request = StateReadPlanner.forAction(resolved.action)
                    val actionState = if (request.isEmpty) DeviceState() else stateProvider(request)
                    val context = FireContext(
                        event = event,
                        state = actionState,
                        automationId = automation.id,
                        approvalFingerprint = approvalFingerprint,
                        eventId = runEventId,
                        executionId = executionId,
                        actionIndex = index,
                        priority = automation.priority,
                        actionPath = path.value,
                    )
                    val p4Executor = resolvedExecutor
                    when {
                        // Cablato in P4-D2: il ResolvedActionExecutor Android frama i
                        // RuntimeDataBinding fuori dal prompt e restituisce testo concreto per
                        // le capture. Le barriere fail-closed p4_runtime_data_unavailable e
                        // p4_generative_unavailable sono state rimosse insieme al wiring.
                        p4Executor != null -> p4Executor.execute(resolved, context)
                        // Senza executor risolto una capture NON è realizzabile (il boundary
                        // flat sa solo SUBMITTED): resta fail-closed, mai un capture_missing.
                        resolved.action.captureNameOrNull() != null -> ProgramActionResult(
                            ActionResult.Failure("p4_capture_unavailable"),
                        )
                        else -> ProgramActionResult(executor.execute(resolved.action, context))
                    }
                },
                stateProvider = stateProvider,
                conditionEvaluator = evaluator,
                pause = pause,
                clock = programClock,
                locale = programLocale,
                journal = ProgramExecutionJournal { entry ->
                    recordJournalAction(
                        ActionJournalEntry(
                            executionId = executionId,
                            actionIndex = indexFor(entry.path),
                            actionType = entry.actionType,
                            outcome = entry.outcome,
                            atMillis = now().coerceAtLeast(0),
                            errorCode = entry.errorCode,
                            actionPath = entry.path.value,
                        ),
                    )
                },
                questions = ProgramQuestions { question -> questions.ask(questionContext, question) },
            )
            val result = runWhileApproved(automation, approvalFingerprint) {
                if (resumed == null) {
                    program.execute(
                        trigger = trigger,
                        event = event,
                        bindings = automation.vars,
                        actions = actions,
                    )
                } else {
                    program.resume(
                        trigger = trigger,
                        event = event,
                        bindings = automation.vars,
                        actions = actions,
                        continuation = resumed.continuation,
                        outcome = resumed.outcome,
                    )
                }
            }
            return RunResult(result.steps.map { it.result }, result.stopCode, program = true)
        }
        val results = mutableListOf<ActionResult>()
        // Percorsi del journal: quelli delle azioni (1, 2, …), poi saltando quelli già usati dal ripristino.
        val taken = reserved.mapTo(HashSet()) { it.value }
        actions.forEachIndexed { position, action ->
            val path = (position + 1).toString().takeIf { it !in taken } ?: (offset + position + 1).toString()
            val context = FireContext(
                event = event,
                state = state.read(StateReadPlanner.forAction(action)),
                automationId = automation.id,
                approvalFingerprint = approvalFingerprint,
                eventId = runEventId,
                executionId = executionId,
                actionIndex = offset + position,
                priority = automation.priority,
                actionPath = path,
            )
            beforeLeaf(action)
            val result = try {
                executor.execute(action, context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ActionResult.Failure("executor_exception")
            }
            results += result
            recordJournalAction(
                ActionJournalEntry(
                    executionId = executionId,
                    actionIndex = offset + position,
                    actionType = action.journalType(),
                    outcome = result.journalOutcome(),
                    atMillis = batchNow,
                    errorCode = result.journalCode(),
                    actionPath = path,
                ),
            )
        }
        return RunResult(results, stopCode = null, program = false)
    }

    /**
     * Chiude il journal di un'esecuzione con [all] i risultati e registra l'arresto di un programma. [RunEnd.Stopped]:
     * già nel Log come blocco, mai anche come FIRED; [RunEnd.Cancelled]: regola cambiata a metà, nessun esito.
     */
    private suspend fun finishRun(
        automation: Automation,
        run: RunResult,
        all: List<ActionResult>,
        runEventId: TriggerEventId,
        executionId: ExecutionId,
        batchNow: Long,
    ): RunEnd {
        if (!run.program) {
            finishJournal(all.completion(executionId, batchNow))
            return RunEnd.Completed
        }
        val stopCode = run.stopCode
        if (stopCode != null) {
            recordAudit(
                AuditEvent(
                    automation.id,
                    when (stopCode) {
                        // Regola spenta o cambiata a metà, o domanda senza risposta: non è un guasto (B11).
                        PROGRAM_CANCELLED -> AuditKind.SUPPRESSED_NOT_ELIGIBLE
                        in PROGRAM_USER_STOPS -> AuditKind.SUPPRESSED_NOT_ELIGIBLE
                        in PROGRAM_POLICY_STOPS -> AuditKind.BLOCKED_POLICY
                        else -> AuditKind.ERROR
                    },
                    now().coerceAtLeast(0),
                    detail = stopCode,
                    eventId = runEventId,
                    executionId = executionId,
                ),
            )
        }
        finishJournal(
            all.completion(
                executionId,
                now().coerceAtLeast(0),
                forcedStatus = when (stopCode) {
                    null -> null
                    PROGRAM_CANCELLED -> ExecutionStatus.CANCELLED
                    in PROGRAM_USER_STOPS -> ExecutionStatus.CANCELLED
                    else -> ExecutionStatus.FAILED
                },
            ),
        )
        if (stopCode == PROGRAM_CANCELLED) return RunEnd.Cancelled
        // B11: già registrato come BLOCKED_POLICY, mai anche come FIRED. L'esito resta
        // nei risultati (le azioni già eseguite contano per one-shot e Log).
        if (stopCode != null && (stopCode in PROGRAM_POLICY_STOPS || stopCode in PROGRAM_USER_STOPS)) {
            return RunEnd.Stopped
        }
        return RunEnd.Completed
    }

    private suspend fun recordAudit(event: AuditEvent) {
        try {
            audit.record(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Il logging non deve cambiare l'esito dell'automazione.
        }
    }

    private suspend fun recordJournalAction(entry: ActionJournalEntry) {
        try {
            journal.recordAction(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Il claim RUNNING resta recuperabile dalla maintenance se il journal è indisponibile.
        }
    }

    private suspend fun finishJournal(completion: ExecutionCompletion) {
        try {
            journal.finish(completion)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Il claim RUNNING verrà marcato INTERRUPTED dalla maintenance.
        }
    }

    /**
     * Isola ogni programma P4 in un child supervisionato: disable, delete o cambio fingerprint
     * cancellano solo quella regola (anche durante un `wait`), senza abortire le altre candidate
     * dello stesso evento. Se il flusso di stato non è più verificabile l'esecuzione viene
     * invalidata: una regola lunga non continua con un'approvazione che non possiamo confermare.
     */
    private suspend fun runWhileApproved(
        automation: Automation,
        fingerprint: ApprovalFingerprint,
        block: suspend () -> ProgramExecutionResult,
    ): ProgramExecutionResult = supervisorScope {
        val execution = async(start = CoroutineStart.UNDISPATCHED) { block() }
        val invalidated = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                store.observeAll().first { automations ->
                    val current = automations.firstOrNull { it.id == automation.id }
                    current == null ||
                        current.status != AutomationStatus.ARMED ||
                        !current.enabled ||
                        current.approvalFingerprint != fingerprint ||
                        ApprovalFingerprints.of(current) != fingerprint
                }
                Unit
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
        }
        try {
            select {
                execution.onAwait { it }
                invalidated.onAwait {
                    execution.cancelAndJoin()
                    ProgramExecutionResult(emptyList(), stopCode = "cancelled")
                }
            }
        } finally {
            invalidated.cancelAndJoin()
            if (execution.isActive) execution.cancelAndJoin()
        }
    }

    /** 0.8: un'azione AI d'inizio o di fine impone a entrambe le fasi la pausa minima delle regole generative. */
    private fun effectiveCooldown(automation: Automation): Long =
        if (automation.allActions.any { it.tier == ActionTier.GENERATIVE })
            maxOf(automation.cooldownMs, MIN_GENERATIVE_COOLDOWN_MS)
        else automation.cooldownMs

    private fun Action.captureNameOrNull(): String? = when (this) {
        is Action.RunShell -> captureAs
        is Action.InvokeLlm -> captureAs
        is Action.InvokeLlmV2 -> captureAs
        is Action.Ask -> captureAs
        is Action.HttpRequest -> captureAs
        else -> null
    }
}

private val NO_HOOK: suspend (Action) -> Unit = {}
