package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus
import kotlinx.coroutines.CancellationException

/** @param now fornitore di epoch-millis (Android: System::currentTimeMillis). */
class Engine(
    private val store: AutomationStore,
    private val executor: ActionExecutor,
    private val evaluator: ConditionEvaluator,
    private val matcher: TriggerMatcher,
    private val firePolicy: FirePolicy,
    private val audit: AuditSink = NoopAuditSink,
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
            is TriggerEvent.TimeFired -> listOfNotNull(store.get(event.automationId))
            is TriggerEvent.GeofenceTransitioned -> listOfNotNull(store.get(event.automationId))
            else -> store.armed()
        }.filter { it.status == AutomationStatus.ARMED && it.enabled }
            .sortedWith(compareBy({ it.priority }, { it.id.value }))

        val batchNow = now()
        var cached: DeviceState? = null
        suspend fun state(): DeviceState = cached ?: stateProvider().also { cached = it }

        val outcomes = mutableListOf<FireOutcome>()
        for (automation in candidates) {
            var executionId: ExecutionId? = null
            try {
                if (!matcher.matches(automation.trigger, event)) continue

                when (val decision = firePolicy.evaluate(automation, event)) {
                    FirePolicyDecision.Allow -> Unit
                    is FirePolicyDecision.Block -> {
                        if (decision.needsReview)
                            store.setStatus(automation.id, AutomationStatus.NEEDS_REVIEW)
                        audit.record(
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
                    audit.record(
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
                    FireClaimResult.Claimed -> Unit
                    is FireClaimResult.Duplicate -> {
                        audit.record(
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
                        audit.record(
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
                        audit.record(
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
                val results = automation.actions.map { action ->
                    try {
                        executor.execute(action, context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ActionResult.Failure(e.message ?: e::class.simpleName ?: "action error")
                    }
                }
                audit.record(
                    AuditEvent(
                        automation.id,
                        AuditKind.FIRED,
                        batchNow,
                        detail = results.joinToString { it::class.simpleName ?: "?" },
                        eventId = envelope.id,
                        executionId = executionId,
                    ),
                )
                outcomes += FireOutcome(automation, automation.actions, results, envelope.id, executionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audit.record(
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

    private fun effectiveCooldown(automation: Automation): Long =
        if (automation.actions.any { it.tier == ActionTier.GENERATIVE })
            maxOf(automation.cooldownMs, MIN_GENERATIVE_COOLDOWN_MS)
        else automation.cooldownMs
}
