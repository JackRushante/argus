package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationSchema
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
    private val resolvedExecutor: ResolvedActionExecutor? = null,
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
        )
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
        var cachedRequest = StateReadRequest.EMPTY
        var cachedState = DeviceState()
        suspend fun state(request: StateReadRequest): DeviceState {
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
                                            eventId = envelope.id,
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
                                eventId = envelope.id,
                            ),
                        )
                        continue
                    }
                }

                if (automation.conditions != null) {
                    val conditionState = state(StateReadPlanner.forCondition(automation.conditions))
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
                                    eventId = envelope.id,
                                ),
                            )
                            continue
                        }
                    }
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

                val approvalFingerprint = requireNotNull(automation.approvalFingerprint) {
                    "Automazione approvata priva di fingerprint"
                }
                if (AutomationSchema.requiresP4(automation)) {
                    val indexes = linkedMapOf<ActionPath, Int>()
                    fun indexFor(path: ActionPath): Int = indexes.getOrPut(path) { indexes.size }
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
                                eventId = envelope.id,
                                executionId = executionId,
                                actionIndex = index,
                                priority = automation.priority,
                                actionPath = path.value,
                            )
                            val p4Executor = resolvedExecutor
                            when {
                                p4Executor != null -> p4Executor.execute(resolved, context)
                                resolved.runtimeData.isNotEmpty() -> ProgramActionResult(
                                    ActionResult.Failure("p4_runtime_data_unavailable"),
                                )
                                resolved.action.captureNameOrNull() != null -> ProgramActionResult(
                                    ActionResult.Failure("p4_capture_unavailable"),
                                )
                                resolved.action.tier == ActionTier.GENERATIVE -> ProgramActionResult(
                                    ActionResult.Failure("p4_generative_unavailable"),
                                )
                                else -> ProgramActionResult(executor.execute(resolved.action, context))
                            }
                        },
                        stateProvider = stateProvider,
                        conditionEvaluator = evaluator,
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
                    )
                    val result = program.execute(
                        trigger = automation.trigger,
                        event = event,
                        bindings = automation.vars,
                        actions = automation.actions,
                    )
                    actionResults += result.steps.map { it.result }
                    if (!result.completed) {
                        val code = requireNotNull(result.stopCode)
                        recordAudit(
                            AuditEvent(
                                automation.id,
                                if (code in PROGRAM_POLICY_STOPS) {
                                    AuditKind.BLOCKED_POLICY
                                } else {
                                    AuditKind.ERROR
                                },
                                now().coerceAtLeast(0),
                                detail = code,
                                eventId = envelope.id,
                                executionId = executionId,
                            ),
                        )
                    }
                    finishJournal(
                        actionResults.completion(
                            executionId,
                            now().coerceAtLeast(0),
                            forcedStatus = if (result.completed) null else ExecutionStatus.FAILED,
                        ),
                    )
                } else {
                    automation.actions.forEachIndexed { index, action ->
                        val context = FireContext(
                            event = event,
                            state = state(StateReadPlanner.forAction(action)),
                            automationId = automation.id,
                            approvalFingerprint = approvalFingerprint,
                            eventId = envelope.id,
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
                                errorCode = if (result is ActionResult.Failure) "action_failed" else null,
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

    private fun Action.captureNameOrNull(): String? = when (this) {
        is Action.RunShell -> captureAs
        is Action.InvokeLlm -> captureAs
        is Action.InvokeLlmV2 -> captureAs
        else -> null
    }

    private fun DeviceState.merge(other: DeviceState): DeviceState = DeviceState(
        values = values + other.values,
        foregroundApp = other.foregroundApp ?: foregroundApp,
        location = other.location ?: location,
        queryValues = queryValues + other.queryValues,
    )

}
