package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    private val now: () -> Long,
) {
    companion object {
        const val MIN_GENERATIVE_COOLDOWN_MS = 60_000L
    }

    data class FireOutcome(
        val automation: Automation,
        val actions: List<Action>,
        val results: List<ActionResult>,
        val eventId: TriggerEventId,
        val executionId: ExecutionId,
    )

    /** [stateProvider] resta lazy e viene letto al più una volta per batch. */
    suspend fun onTrigger(
        envelope: TriggerEnvelope,
        stateProvider: suspend () -> DeviceState,
    ): List<FireOutcome> {
        val event = envelope.event
        val candidates = when (event) {
            is TriggerEvent.Registered -> listOfNotNull(store.get(event.automationId))
            else -> store.armed()
        }.filter { it.status == AutomationStatus.ARMED && it.enabled }
            .sortedWith(compareBy({ it.priority }, { it.id.value }))

        val batchNow = now()
        var cached: DeviceState? = null
        suspend fun state(): DeviceState = cached ?: stateProvider().also { cached = it }

        val outcomes = mutableListOf<FireOutcome>()
        for (automation in candidates) {
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
                            eventId = envelope.id,
                        ),
                    )
                    continue
                }
                if (!matcher.matches(automation.trigger, event)) continue

                when (val decision = firePolicy.evaluate(automation, event)) {
                    FirePolicyDecision.Allow -> Unit
                    is FirePolicyDecision.Block -> {
                        if (decision.needsReview)
                            store.markNeedsReview(automation.id)
                        recordAudit(
                            AuditEvent(
                                automationId = automation.id,
                                kind = AuditKind.BLOCKED_POLICY,
                                atMillis = batchNow,
                                detail = decision.code,
                                eventId = envelope.id,
                            ),
                        )
                        continue
                    }
                }

                if (automation.conditions != null && !evaluator.eval(automation.conditions, state())) {
                    recordAudit(
                        AuditEvent(
                            automation.id,
                            AuditKind.CONDITIONS_NOT_MET,
                            batchNow,
                            eventId = envelope.id,
                        ),
                    )
                    continue
                }

                executionId = executionIds.create(automation.id, envelope.id)
                when (
                    val claim = store.claimFire(
                        FireClaimRequest(
                            automationId = automation.id,
                            eventId = envelope.id,
                            executionId = executionId,
                            atMillis = batchNow,
                            cooldownMs = effectiveCooldown(automation),
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
                                eventId = envelope.id,
                                executionId = claim.existingExecutionId,
                            ),
                        )
                        continue
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
                                eventId = envelope.id,
                                executionId = executionId,
                            ),
                        )
                        continue
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
                                eventId = envelope.id,
                                executionId = executionId,
                            ),
                        )
                        continue
                    }
                }

                val context = FireContext(event, state(), automation.id, envelope.id, executionId)
                automation.actions.forEachIndexed { index, action ->
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
                            errorCode = if (result is ActionResult.Failure) "action_failed" else null,
                        ),
                    )
                }
                finishJournal(actionResults.completion(executionId, batchNow))
                claimed = false
                recordAudit(
                    AuditEvent(
                        automation.id,
                        AuditKind.FIRED,
                        batchNow,
                        eventId = envelope.id,
                        executionId = executionId,
                    ),
                )
                outcomes += FireOutcome(automation, automation.actions, actionResults.toList(), envelope.id, executionId)
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
                        eventId = envelope.id,
                        executionId = executionId,
                    ),
                )
            }
        }
        return outcomes
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

    private fun effectiveCooldown(automation: Automation): Long =
        if (automation.actions.any { it.tier == ActionTier.GENERATIVE })
            maxOf(automation.cooldownMs, MIN_GENERATIVE_COOLDOWN_MS)
        else automation.cooldownMs
}
