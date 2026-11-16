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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
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
    }

    data class FireOutcome(
        val automation: Automation,
        val actions: List<Action>,
        val results: List<ActionResult>,
        val eventId: TriggerEventId,
        val executionId: ExecutionId,
    )

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
        )
    }

    /**
     * Uno scatto di [automation] per [envelope]. Con [confirmed] è l'esecuzione che l'utente ha
     * confermato: l'evento di claim è quello della domanda, il cooldown non si riprende e non si chiede di
     * nuovo. Null se la regola non ha eseguito azioni da riportare.
     */
    private suspend fun fireCandidate(
        automation: Automation,
        envelope: TriggerEnvelope,
        batchNow: Long,
        state: BatchState,
        stateProvider: suspend (StateReadRequest) -> DeviceState,
        confirmed: ConfirmedFire?,
    ): FireOutcome? {
        val event = envelope.event
        // L'esecuzione confermata ha un'identità propria (claim, journal, Log), distinta dallo scatto che ha chiesto.
        val runEventId = confirmed?.runEventId ?: envelope.id
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
            if (!matcher.matches(automation.trigger, event)) return null

            // «Pausa automazioni» (F21): dopo il match, così il Log mostra solo le regole che
            // sarebbero davvero scattate; prima di policy, condizioni e claim, così lo scatto
            // saltato non consuma cooldown né deduplica e alla ripresa non si recupera.
            // 0.7.2: decisa per regola, così la pausa di un gruppo (etichetta) segue la stessa strada.
            val pauseCode = firingPause.pauseCodeFor(automation.id)
            if (pauseCode != null) {
                recordAudit(
                    AuditEvent(
                        automationId = automation.id,
                        kind = AuditKind.BLOCKED_POLICY,
                        atMillis = batchNow,
                        detail = pauseCode,
                        eventId = runEventId,
                    ),
                )
                return null
            }

            when (val decision = firePolicy.evaluate(automation, event)) {
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
                            }
                        }
                    }
                    recordAudit(
                        AuditEvent(
                            automationId = automation.id,
                            kind = AuditKind.BLOCKED_POLICY,
                            atMillis = batchNow,
                            detail = decision.code,
                            eventId = runEventId,
                        ),
                    )
                    return null
                }
            }

            if (automation.conditions != null) {
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

            executionId = executionIds.create(automation.id, runEventId)
            when (
                val claim = store.claimFire(
                    FireClaimRequest(
                        automationId = automation.id,
                        eventId = runEventId,
                        executionId = executionId,
                        atMillis = batchNow,
                        // Il cooldown lo ha già preso lo scatto che ha chiesto conferma.
                        cooldownMs = if (confirmed != null) 0 else effectiveCooldown(automation),
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
            if (confirm != null && confirmed == null) {
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

            if (AutomationSchema.requiresP4(automation)) {
                val indexes = linkedMapOf<ActionPath, Int>()
                fun indexFor(path: ActionPath): Int = indexes.getOrPut(path) { indexes.size }
                val questionContext = QuestionContext(
                    automationId = automation.id,
                    ruleName = automation.name,
                    approvalFingerprint = approvalFingerprint,
                    executionId = executionId,
                    eventId = runEventId,
                )
                val program = ProgramInterpreter(
                    runner = ProgramActionRunner { resolved, path ->
                        val index = indexFor(path)
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
                    program.execute(
                        trigger = automation.trigger,
                        event = event,
                        bindings = automation.vars,
                        actions = automation.actions,
                    )
                }
                actionResults += result.steps.map { it.result }
                val stopCode = result.stopCode
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
                    actionResults.completion(
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
                if (stopCode == PROGRAM_CANCELLED) {
                    claimed = false
                    return null
                }
                if (stopCode != null && (stopCode in PROGRAM_POLICY_STOPS || stopCode in PROGRAM_USER_STOPS)) {
                    // B11: già registrato come BLOCKED_POLICY, mai anche come FIRED. L'esito resta
                    // nei risultati (le azioni già eseguite contano per one-shot e Log).
                    claimed = false
                    return FireOutcome(automation, automation.actions, actionResults.toList(), runEventId, executionId)
                }
            } else {
                automation.actions.forEachIndexed { index, action ->
                    val context = FireContext(
                        event = event,
                        state = state.read(StateReadPlanner.forAction(action)),
                        automationId = automation.id,
                        approvalFingerprint = approvalFingerprint,
                        eventId = runEventId,
                        executionId = executionId,
                        actionIndex = index,
                        priority = automation.priority,
                    )
                    val result = try {
                        executor.execute(action, context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ActionResult.Failure("executor_exception")
                    }
                    actionResults += result
                    recordJournalAction(
                        ActionJournalEntry(
                            executionId = executionId,
                            actionIndex = index,
                            actionType = action.journalType(),
                            outcome = result.journalOutcome(),
                            atMillis = batchNow,
                            errorCode = result.journalCode(),
                        ),
                    )
                }
                finishJournal(actionResults.completion(executionId, batchNow))
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

    private fun effectiveCooldown(automation: Automation): Long =
        if (automation.actions.any { it.tier == ActionTier.GENERATIVE })
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
