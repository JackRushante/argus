package dev.argus.automation

import dev.argus.automation.budget.BudgetMeta
import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyRequest
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.GenerativeAction
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateContextClassification
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueCoercion
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.isOneShot
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.ActionPath
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.engine.runtime.SubmittedActionCompletion
import dev.argus.engine.runtime.SubmittedActionJournal
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Ritorna true soltanto se il testo è stato cifrato e persistito davvero (E13). */
fun interface DeferredReplySink {
    suspend fun defer(context: FireContext, text: String): Boolean
}

/**
 * Lane bounded, single-consumer e process-local. Non effettua replay dopo process death: la
 * maintenance trasforma i SUBMITTED stale in INTERRUPTED.
 */
class AndroidGenerativeLane(
    scope: CoroutineScope,
    private val journal: SubmittedActionJournal,
    private val automations: AutomationStore,
    private val firePolicy: FirePolicy,
    private val brain: Brain,
    private val replies: NotificationReplyGateway,
    private val deferredReplies: DeferredReplySink,
    private val notifier: AutomationNotifier,
    private val oneShotConsumptions: OneShotConsumptionRegistry = NoopOneShotConsumptionRegistry,
    capacity: Int = DEFAULT_CAPACITY,
    private val submissionHandshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : GenerativeLane {
    private val queue: Channel<QueuedAction>

    init {
        require(capacity > 0) { "La capacità generativa deve essere positiva" }
        require(submissionHandshakeTimeoutMillis > 0) { "Timeout handshake non valido" }
        queue = Channel(capacity)
        scope.launch {
            try {
                for (queued in queue) {
                    if (queued.resolved != null) processResolvedSafely(queued) else processSafely(queued)
                }
            } finally {
                queue.close()
            }
        }
    }

    override fun trySubmit(context: FireContext, action: GenerativeAction): Boolean {
        val frozenContext = context.copy(
            state = context.state.copy(
                values = context.state.values.toMap(),
                queryValues = context.state.queryValues.toMap(),
            ),
        )
        val frozenAction: GenerativeAction = when (action) {
            is Action.InvokeLlm -> action.copy(
                contextSources = action.contextSources.toList(),
                allowedTools = action.allowedTools.toList(),
            )
            is Action.InvokeLlmV2 -> action.copy(
                stateContext = action.stateContext.toList(),
                allowedTools = action.allowedTools.toList(),
            )
        }
        return queue.trySend(QueuedAction(frozenContext, frozenAction)).isSuccess
    }

    /**
     * Canale sincrono P4 per [Action.InvokeLlm]: gestisce sia capture sia consegna. Accoda sulla
     * STESSA lane single-consumer e sospende fino a modello + revalidazione + eventuale sink. Il dato
     * TAINTED resta in [runtimeData], mai nel goal. Il TEMPLATE approvato (pre-interpolazione) viene
     * catturato ORA così la revalidazione confronta template⇄template, non l'azione interpolata.
     */
    override suspend fun submitAndAwait(
        context: FireContext,
        action: Action.InvokeLlm,
        runtimeData: List<RuntimeDataBinding>,
    ): ActResult {
        val frozenContext = context.copy(
            state = context.state.copy(
                values = context.state.values.toMap(),
                queryValues = context.state.queryValues.toMap(),
            ),
        )
        val frozenAction = action.copy(
            contextSources = action.contextSources.toList(),
            allowedTools = action.allowedTools.toList(),
        )
        val approvedTemplate = try {
            automations.get(context.automationId)
                ?.let { ActionPath(context.actionPath).resolve(it.actions) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val deferred = CompletableDeferred<ActResult>()
        val request = ResolvedRequest(deferred, runtimeData.toList(), approvedTemplate)
        if (!queue.trySend(QueuedAction(frozenContext, frozenAction, request)).isSuccess) {
            return ActResult(text = null, metaError = "generative_lane_unavailable")
        }
        return try {
            deferred.await()
        } catch (error: CancellationException) {
            // Cancellazione del CHIAMANTE (es. regola disabilitata mid-flight, Engine.cancelAndJoin):
            // il worker resta indipendente ma il suo esito non viene mai osservato → nessuna capture.
            deferred.cancel(error)
            throw error
        }
    }

    private suspend fun processResolvedSafely(queued: QueuedAction) {
        val request = queued.resolved ?: return
        try {
            request.deferred.complete(processResolved(queued, request))
        } catch (error: CancellationException) {
            request.deferred.cancel(error)
            throw error
        } catch (_: Exception) {
            request.deferred.complete(ActResult(text = null, metaError = "lane_failed"))
        }
    }

    /**
     * Esegue una foglia generativa P4 sincrona. Con runtime data usa `brain.actResolved`; senza usa
     * il normale `brain.act` (evita di negoziare schema 3 con una lista vuota). Dopo la seconda
     * revalidazione, `captureAs` restituisce il testo all'interprete; una foglia senza capture lo
     * consegna qui, dove esistono anche defer cifrato e notifier.
     */
    private suspend fun processResolved(queued: QueuedAction, request: ResolvedRequest): ActResult {
        validateResolved(queued, request)?.let { return ActResult(text = null, metaError = it) }
        val action = queued.action as? Action.InvokeLlm
            ?: return ActResult(text = null, metaError = "action_contract_invalid")
        if (!validContract(queued)) {
            return ActResult(text = null, metaError = "action_contract_invalid")
        }

        val act = try {
            withTimeout(action.timeoutMs) {
                if (request.runtimeData.isEmpty()) {
                    brain.act(
                        context = queued.context,
                        goal = action.goal,
                        contextSources = action.contextSources,
                        allowedTools = action.allowedTools,
                    )
                } else {
                    brain.actResolved(
                        context = queued.context,
                        goal = action.goal,
                        contextSources = action.contextSources,
                        allowedTools = action.allowedTools,
                        runtimeData = request.runtimeData,
                    )
                }
            }
        } catch (_: TimeoutCancellationException) {
            return ActResult(text = null, metaError = "act_timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActResult(text = null, metaError = "brain_failed")
        }

        val text = act.text
        if (text == null) {
            return ActResult(text = null, metaError = safeCode(act.metaError, "brain_failed"))
        }
        // Revalidazione POST-modello: un edit/disabilitazione/policy revocata durante la latenza LLM
        // sopprime la capture (nessun falso successo), esattamente come il sink async.
        validateResolved(queued, request)?.let { return ActResult(text = null, metaError = it) }
        if (action.captureAs != null) return act
        return deliverResolved(queued, action, text)
    }

    /** Consegna sincrona P4. Il testo restituito segnala successo all'executor ma non viene catturato. */
    private suspend fun deliverResolved(
        queued: QueuedAction,
        action: Action.InvokeLlm,
        text: String,
    ): ActResult {
        if (action.deliver == GenerativeDeliverMode.LOCAL_NOTIFICATION) {
            return try {
                notifier.show(requireNotNull(action.notificationTitle), text, queued.context)
                ActResult(text = text, metaError = null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ActResult(text = null, metaError = "notify_failed")
            }
        }
        if (action.deliver == GenerativeDeliverMode.CAPTURE_ONLY) {
            return ActResult(text = null, metaError = "capture_required")
        }
        val notification = queued.context.event as? TriggerEvent.NotificationPosted
            ?: return ActResult(text = null, metaError = "reply_event_unverified")
        return when (
            val delivery = replies.send(
                NotificationReplyRequest(
                    packageName = notification.pkg,
                    notificationKey = notification.notificationKey.orEmpty(),
                    conversationId = notification.conversationId.orEmpty(),
                    eventId = queued.context.eventId,
                    text = text,
                ),
            )
        ) {
            NotificationReplyDelivery.Sent -> ActResult(text = text, metaError = null)
            is NotificationReplyDelivery.Failed -> {
                if (delivery.code in DEFER_ELIGIBLE_CODES && deferSafely(queued.context, text)) {
                    ActResult(text = text, metaError = null)
                } else {
                    ActResult(
                        text = null,
                        metaError = safeCode(delivery.code, "reply_send_failed"),
                    )
                }
            }
        }
    }

    /**
     * Revalidazione del canale RISOLTO. La sicurezza è tutta nel fingerprint (nessuna approvazione
     * stale). Il confronto d'azione NON usa l'azione interpolata (i marker `{{ARGUS_RUNTIME_DATA_n}}`
     * romperebbero l'uguaglianza byte-per-byte): confronta il TEMPLATE approvato risolto dal path con
     * quello catturato al submit. Regola cambiata ⇒ fingerprint diverso ⇒ approval_changed; foglia
     * spostata/cambiata ⇒ template diverso ⇒ action_changed.
     */
    private suspend fun validateResolved(queued: QueuedAction, request: ResolvedRequest): String? {
        val current = automations.get(queued.context.automationId) ?: return "rule_missing"
        if (
            current.approvalFingerprint != queued.context.approvalFingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return "approval_changed"
        val template = try {
            ActionPath(queued.context.actionPath).resolve(current.actions)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (template == null || template != request.approvedTemplate) return "action_changed"
        if (current.status != AutomationStatus.ARMED || !current.enabled) {
            if (
                !isOneShot(current.trigger) ||
                !oneShotConsumptions.wasAutoConsumed(queued.context.eventId)
            ) return "rule_inactive"
        }
        return when (val decision = firePolicy.evaluate(current, queued.context.event)) {
            FirePolicyDecision.Allow -> null
            is FirePolicyDecision.Block -> safeCode(decision.code, "policy_blocked")
        }
    }

    private suspend fun processSafely(queued: QueuedAction) {
        try {
            process(queued)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            complete(queued, ActionJournalOutcome.FAILED, "lane_failed")
        }
    }

    private suspend fun process(queued: QueuedAction) {
        if (!awaitSubmitted(queued)) return
        validate(queued)?.let { code ->
            complete(queued, ActionJournalOutcome.FAILED, code)
            return
        }

        val act = try {
            withTimeout(timeoutMs(queued.action)) {
                when (val action = queued.action) {
                    is Action.InvokeLlm -> brain.act(
                        context = queued.context,
                        goal = action.goal,
                        contextSources = action.contextSources,
                        allowedTools = action.allowedTools,
                    )
                    is Action.InvokeLlmV2 -> brain.actV2(queued.context, action)
                }
            }
        } catch (_: TimeoutCancellationException) {
            complete(queued, ActionJournalOutcome.FAILED, "act_timeout")
            return
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            complete(queued, ActionJournalOutcome.FAILED, "brain_failed")
            return
        }
        val text = act.text
        if (text == null) {
            val suppressed = act.metaError == BudgetMeta.BUDGET_EXCEEDED
            complete(
                queued,
                ActionJournalOutcome.FAILED,
                safeCode(act.metaError, "brain_failed"),
                suppressedStatus = if (suppressed) ExecutionStatus.SUPPRESSED_BUDGET else null,
            )
            return
        }

        validate(queued)?.let { code ->
            complete(queued, ActionJournalOutcome.FAILED, code)
            return
        }
        val action = queued.action
        if (action is Action.InvokeLlm && action.deliver == GenerativeDeliverMode.LOCAL_NOTIFICATION) {
            deliverNotification(queued, action, text)
            return
        }
        val notification = queued.context.event as? TriggerEvent.NotificationPosted
        if (notification == null) {
            complete(queued, ActionJournalOutcome.FAILED, "reply_event_unverified")
            return
        }
        val delivery = replies.send(
            NotificationReplyRequest(
                packageName = notification.pkg,
                notificationKey = notification.notificationKey.orEmpty(),
                conversationId = notification.conversationId.orEmpty(),
                eventId = queued.context.eventId,
                text = text,
            ),
        )
        when (delivery) {
            NotificationReplyDelivery.Sent ->
                complete(queued, ActionJournalOutcome.SUCCEEDED, null)

            is NotificationReplyDelivery.Failed -> {
                if (delivery.code in DEFER_ELIGIBLE_CODES && deferSafely(queued.context, text)) {
                    complete(queued, ActionJournalOutcome.DEFERRED, delivery.code)
                } else {
                    complete(
                        queued,
                        ActionJournalOutcome.FAILED,
                        safeCode(delivery.code, "reply_send_failed"),
                    )
                }
            }
        }
    }

    /**
     * Sink NOTIFICA (#59): il testo generato diventa una notifica locale, senza alcun requisito
     * sull'evento trigger (a differenza del reply, che esige una NotificationPosted). Il titolo è
     * LETTERALE dal fingerprint approvato (validContract ne garantisce la presenza, D2).
     */
    private suspend fun deliverNotification(
        queued: QueuedAction,
        action: Action.InvokeLlm,
        text: String,
    ) {
        try {
            notifier.show(
                title = action.notificationTitle!!,
                text = text,
                context = queued.context,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            complete(queued, ActionJournalOutcome.FAILED, "notify_failed")
            return
        }
        complete(queued, ActionJournalOutcome.SUCCEEDED, null)
    }

    private suspend fun awaitSubmitted(queued: QueuedAction): Boolean =
        withTimeoutOrNull(submissionHandshakeTimeoutMillis) {
            journal.observeSubmission(
                queued.context.executionId,
                queued.context.actionIndex,
            ).filterNotNull().first { state ->
                state.ready || state.executionStatus !in PENDING_EXECUTION_STATES
            }.ready
        } ?: false

    private suspend fun validate(queued: QueuedAction): String? {
        if (!validContract(queued)) return "action_contract_invalid"
        val current = automations.get(queued.context.automationId) ?: return "rule_missing"
        // Fingerprint e azione PRIMA dello stato: la sicurezza (nessuna approvazione stale) è tutta
        // nel fingerprint, che NON include status/enabled (vedi ApprovalFingerprints.of). Un edit non
        // ri-approvato resta "approval_changed" anche su un one-shot; un'azione cambiata "action_changed".
        if (
            current.approvalFingerprint != queued.context.approvalFingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return "approval_changed"
        if (ActionPath(queued.context.actionPath).resolve(current.actions) != queued.action) {
            return "action_changed"
        }
        // RACE one-shot (#60): un trigger one-shot (immediate, o time con `at`) si DISABILITA appena
        // dispatchato (TimeAlarmCoordinator.deliverLocked / ArmedAutomationRegistrar.registerImmediate
        // consumano la regola), mentre la lane generativa gira async. L'azione era già accodata quando
        // la regola era ARMATA e fingerprint+azione combaciano ancora: DEVE poter completare. Le regole
        // ricorrenti disabilitate dall'utente (time con cron, ecc.) restano invece "rule_inactive".
        if (current.status != AutomationStatus.ARMED || !current.enabled) {
            if (
                !isOneShot(current.trigger) ||
                !oneShotConsumptions.wasAutoConsumed(queued.context.eventId)
            ) return "rule_inactive"
        }
        return when (val decision = firePolicy.evaluate(current, queued.context.event)) {
            FirePolicyDecision.Allow -> null
            is FirePolicyDecision.Block -> safeCode(decision.code, "policy_blocked")
        }
    }

    /** One-shot = fira una sola volta e si auto-consuma: `immediate` oppure `time` one-shot
     *  (`at` assoluto o `afterMs` relativo, mai un `time` ricorrente con `cron`). Solo questi
     *  tollerano lo stato disabilitato in ri-validazione, perché il dispatch li disabilita per
     *  costruzione. */
    private fun isOneShot(trigger: Trigger): Boolean =
        trigger is Trigger.Immediate || (trigger as? Trigger.Time)?.isOneShot() == true

    private fun validContract(queued: QueuedAction): Boolean = when (val action = queued.action) {
        is Action.InvokeLlm -> when (action.deliver) {
            // Sink REPLY (profilo P1, invariato).
            GenerativeDeliverMode.WHATSAPP_REPLY -> action.replyTargetSender &&
                action.contextSources.isNotEmpty() &&
                action.contextSources == action.contextSources.distinct() &&
                "notification" in action.contextSources &&
                action.contextSources.all { it in P1_CONTEXT_SOURCES } &&
                GenerativeContract.isAllowedToolset(action.allowedTools) &&
                action.timeoutMs in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS
            // Sink NOTIFICA (#59): specchia DraftValidator.validateInvokeLlmNotificationDeliver.
            GenerativeDeliverMode.LOCAL_NOTIFICATION -> !action.replyTargetSender &&
                GenerativeContract.isNotificationToolset(action.allowedTools) &&
                validNotificationTitle(action.notificationTitle) &&
                action.contextSources == action.contextSources.distinct() &&
                action.contextSources.all { it == GenerativeContract.CONTEXT_STATE } &&
                action.timeoutMs in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS
            GenerativeDeliverMode.CAPTURE_ONLY -> action.captureAs != null &&
                !action.replyTargetSender &&
                action.notificationTitle == null &&
                GenerativeContract.isNotificationToolset(action.allowedTools) &&
                action.contextSources == action.contextSources.distinct() &&
                action.contextSources.all { it == GenerativeContract.CONTEXT_STATE } &&
                action.timeoutMs in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS
        }
        is Action.InvokeLlmV2 -> action.replyTargetSender &&
            GenerativeContract.isAllowedToolset(action.allowedTools) &&
            action.timeoutMs in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS &&
            action.stateContext.isNotEmpty() &&
            action.stateContext.size <= StateContextClassification.MAX_QUERIES &&
            action.stateContext.map { it.query.canonicalId }.distinct().size == action.stateContext.size &&
            action.stateContext.all { context ->
                context.policyVersion == StateQueryPolicy.VERSION &&
                    StateQueryPolicy.validQuery(context.query) &&
                    StateContextClassification.validValueType(context.query, context.valueType) &&
                    context.integrity == IntegrityLabel.CLEAN &&
                    StateContextClassification.covers(
                        context.confidentiality,
                        StateContextClassification.minimumConfidentiality(context.query),
                    ) &&
                    queuedValueIsValid(queued.context, context.query.canonicalId, context.valueType)
            }
    }

    /** Titolo notifica LETTERALE (D2): non-null, non-blank, bounded e senza caratteri di controllo.
     *  Specchia DraftValidator.validateInvokeLlmNotificationDeliver (MAX_NAME_LENGTH = 120). */
    private fun validNotificationTitle(title: String?): Boolean =
        !title.isNullOrBlank() &&
            title.length <= MAX_NOTIFICATION_TITLE_CHARS &&
            title.none(Char::isISOControl)

    private fun queuedValueIsValid(
        context: FireContext,
        queryId: String,
        valueType: dev.argus.engine.model.StateValueType,
    ): Boolean = context.state.queryValues[queryId]?.let { raw ->
        raw.isNotEmpty() && raw.length <= StateQueryPolicy.MAX_SCALAR_CHARS &&
            raw.none(Char::isISOControl) && StateValueCoercion.compatible(raw, valueType)
    } == true

    private fun timeoutMs(action: GenerativeAction): Long = when (action) {
        is Action.InvokeLlm -> action.timeoutMs
        is Action.InvokeLlmV2 -> action.timeoutMs
    }

    private suspend fun deferSafely(context: FireContext, text: String): Boolean = try {
        deferredReplies.defer(context, text)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun complete(
        queued: QueuedAction,
        outcome: ActionJournalOutcome,
        errorCode: String?,
        suppressedStatus: ExecutionStatus? = null,
    ): Boolean = try {
        journal.resolveSubmitted(
            SubmittedActionCompletion(
                executionId = queued.context.executionId,
                actionIndex = queued.context.actionIndex,
                outcome = outcome,
                atMillis = nowMillis().coerceAtLeast(0),
                errorCode = errorCode,
                suppressedStatus = suppressedStatus,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun safeCode(value: String?, fallback: String): String {
        val normalized = value?.lowercase(Locale.ROOT)
        return normalized?.takeIf { ERROR_CODE.matches(it) } ?: fallback
    }

    private class QueuedAction(
        val context: FireContext,
        val action: GenerativeAction,
        /** Non-null solo per il canale RISOLTO (capture sincrona via [submitAndAwait]). */
        val resolved: ResolvedRequest? = null,
    )

    /**
     * Richiesta del canale RISOLTO: il [deferred] che il worker completa, il dato runtime TAINTED da
     * framare in [runtimeData] e il [approvedTemplate] (pre-interpolazione) per la revalidazione. Il
     * toString non espone mai il valore runtime.
     */
    private class ResolvedRequest(
        val deferred: CompletableDeferred<ActResult>,
        val runtimeData: List<RuntimeDataBinding>,
        val approvedTemplate: Action?,
    ) {
        override fun toString(): String =
            "ResolvedRequest(runtimeData=${runtimeData.size}, template=${approvedTemplate?.let { it::class.simpleName }})"
    }

    private companion object {
        const val DEFAULT_CAPACITY = 8
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 5_000L
        const val MIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_TIMEOUT_MILLIS = 120_000L
        // Allineato a DraftValidator.MAX_NAME_LENGTH: il titolo del sink notifica è letterale.
        const val MAX_NOTIFICATION_TITLE_CHARS = 120
        const val REPLY_TOOL = "whatsapp_reply"
        val P1_CONTEXT_SOURCES = setOf("notification", "state")
        val PENDING_EXECUTION_STATES = setOf(ExecutionStatus.RUNNING, ExecutionStatus.SUBMITTED)
        val ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")

        /**
         * Esiti del gateway per cui la risposta generata resta consegnabile a mano (E13):
         * PendingIntent scaduto oppure notifica rimossa/aggiornata/registry perso durante la
         * latenza Hermes. Tutto il resto (package untrusted, testo invalido) resta FAILED.
         */
        val DEFER_ELIGIBLE_CODES = setOf("channel_expired", "reply_channel_unavailable")
    }
}
