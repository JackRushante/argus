package dev.argus.automation

import dev.argus.automation.budget.BudgetMeta
import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyRequest
import dev.argus.engine.brain.Brain
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.GenerativeAction
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateContextClassification
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueCoercion
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.SubmittedActionCompletion
import dev.argus.engine.runtime.SubmittedActionJournal
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.CancellationException
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
                for (queued in queue) processSafely(queued)
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
        if (current.status != AutomationStatus.ARMED || !current.enabled) return "rule_inactive"
        if (
            current.approvalFingerprint != queued.context.approvalFingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return "approval_changed"
        if (current.actions.getOrNull(queued.context.actionIndex) != queued.action) {
            return "action_changed"
        }
        return when (val decision = firePolicy.evaluate(current, queued.context.event)) {
            FirePolicyDecision.Allow -> null
            is FirePolicyDecision.Block -> safeCode(decision.code, "policy_blocked")
        }
    }

    private fun validContract(queued: QueuedAction): Boolean = when (val action = queued.action) {
        is Action.InvokeLlm -> action.replyTargetSender &&
            action.contextSources.isNotEmpty() &&
            action.contextSources == action.contextSources.distinct() &&
            "notification" in action.contextSources &&
            action.contextSources.all { it in P1_CONTEXT_SOURCES } &&
            GenerativeContract.isAllowedToolset(action.allowedTools) &&
            action.timeoutMs in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS
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
    )

    private companion object {
        const val DEFAULT_CAPACITY = 8
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 5_000L
        const val MIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_TIMEOUT_MILLIS = 120_000L
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
