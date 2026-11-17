package dev.argus.engine.dryrun

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ClockVariable
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.HttpCapturePolicy
import dev.argus.engine.model.LiveSystemClock
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.SpanPolicy
import dev.argus.engine.model.SpanSlot
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.StoredWrite
import dev.argus.engine.model.StoredValueOps
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.UserModePolicy
import dev.argus.engine.runtime.ActionPath
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AutomationPause
import dev.argus.engine.runtime.CallerScopeGate
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.ConfirmationCodes
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.ProgramActionResult
import dev.argus.engine.runtime.ProgramActionRunner
import dev.argus.engine.runtime.ProgramInterpreter
import dev.argus.engine.runtime.ProgramObserver
import dev.argus.engine.runtime.ProgramQuestion
import dev.argus.engine.runtime.ProgramQuestions
import dev.argus.engine.runtime.ProgramStep
import dev.argus.engine.runtime.QuestionOutcome
import dev.argus.engine.runtime.ResolvedProgramAction
import dev.argus.engine.runtime.SpanPhase
import dev.argus.engine.runtime.StateReadPlanner
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.StoredModeActions
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.util.Locale

/**
 * «Prova a secco» (0.8): one run of a rule, simulated. The same path as `Engine.fireCandidate` — the real
 * [TriggerMatcher], pause, [FirePolicy], [ConditionEvaluator] on the phone's current state, the caller scope,
 * `confirm`, then the actions through the real [ProgramInterpreter] and [TaintAwareInterpolator] — except that
 * every leaf goes to a recorder instead of an executor.
 *
 * The no-side-effect property is structural: this class holds no executor, no store, no journal, no audit sink,
 * no span store, no vault and no brain, so it has nothing to act with. Its inputs only read: [readState] (state
 * readers), [facts] (values), [firePolicy] (the policy decides; it is the Engine that would quarantine a rule, and
 * the dry run never does), [pauses] and [callerScope] (lookups). Stored values and the user mode a run would
 * change are kept in an overlay in memory, so later steps of the same run see them, and dropped at the end.
 * Waits do not wait. No claim, no cooldown, no AI call, no notification for `ask` or `confirm`: the user's
 * answers stand in.
 */
class DryRunSimulator(
    private val firePolicy: FirePolicy,
    private val readState: suspend (StateReadRequest) -> DeviceState,
    private val facts: DryRunFactsSource = DryRunFactsSource.NONE,
    private val pauses: AutomationPause = AutomationPause.NEVER,
    private val callerScope: CallerScopeGate = CallerScopeGate.UNAVAILABLE,
    private val clock: Clock = LiveSystemClock,
    private val locale: () -> Locale = ClockVariable::appLocale,
    private val evaluator: ConditionEvaluator = ConditionEvaluator(clock),
    private val matcher: TriggerMatcher = TriggerMatcher(),
) {
    companion object {
        /** Steps after which the simulation stops itself (loops without waits run at once here). */
        const val MAX_STEPS = 300

        /** The rule as the engine would see it armed: same content, its fingerprint (computed for a draft). */
        fun asArmed(automation: Automation): Automation = automation.copy(
            status = AutomationStatus.ARMED,
            enabled = true,
            approvalFingerprint = automation.approvalFingerprint ?: ApprovalFingerprints.of(automation),
        )
    }

    suspend fun simulate(request: DryRunRequest): DryRunReport {
        val original = request.automation
        val automation = asArmed(original)
        val exit = automation.exit
        val phase = if (exit != null) request.phase else SpanPhase.START
        val trigger = if (phase == SpanPhase.EXIT) requireNotNull(exit).trigger else automation.trigger
        val event = DryRunEvents.event(automation, trigger, phase, request.sample, request.conversationLabel)
        val run = Run(automation, phase, event, request, clock.millis())
        run.simulatedAsArmed = original.status != AutomationStatus.ARMED || !original.enabled ||
            original.approvalFingerprint == null
        return run.execute(trigger)
    }

    private inner class Run(
        val automation: Automation,
        val phase: SpanPhase,
        val event: TriggerEvent,
        val request: DryRunRequest,
        val atMillis: Long,
    ) {
        var simulatedAsArmed = false
        val steps = mutableListOf<DryRunStep>()
        private val overlay = Overlay()
        private lateinit var phoneFacts: DryRunFacts

        fun report(end: DryRunEnd, code: String? = null) =
            DryRunReport(automation, phase, atMillis, simulatedAsArmed, steps.toList(), end, code)

        suspend fun execute(trigger: Trigger): DryRunReport {
            val scheduled = (event as? TriggerEvent.Registered)?.spanPhase
            val matched = matcher.matches(trigger, event) && (scheduled == null || scheduled == phase)
            steps += DryRunStep.TriggerCheck(trigger, phase, matched)
            if (!matched) return report(DryRunEnd.NOT_MATCHED)

            pauses.pauseCodeFor(automation.id)?.let { code ->
                steps += DryRunStep.Blocked(code)
                return report(DryRunEnd.BLOCKED, code)
            }
            val decision = try {
                firePolicy.evaluate(automation, event, phase)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                FirePolicyDecision.Block("capability_snapshot_unavailable", needsReview = false)
            }
            if (decision is FirePolicyDecision.Block) {
                steps += DryRunStep.Blocked(decision.code)
                return report(DryRunEnd.BLOCKED, decision.code)
            }
            phoneFacts = try {
                facts.read()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DryRunFacts()
            }

            if (phase == SpanPhase.EXIT) {
                val exit = requireNotNull(automation.exit)
                if (exit.restore) restoreStep()
                if (exit.actions.isEmpty()) return report(DryRunEnd.COMPLETED)
                return runActions(exit.trigger, exit.actions)
            }

            automation.conditions?.let { conditions ->
                val state = overlay.read(StateReadPlanner.forCondition(conditions))
                val result = evaluator.result(conditions, state)
                steps += DryRunStep.Conditions(check(conditions, state), result)
                when (result) {
                    ConditionEvaluator.Result.MET -> Unit
                    ConditionEvaluator.Result.NOT_MET -> return report(DryRunEnd.CONDITIONS_NOT_MET)
                    ConditionEvaluator.Result.STATE_UNAVAILABLE ->
                        return report(DryRunEnd.CONDITIONS_UNKNOWN, "condition_state_unavailable")
                }
            }

            if (CallerScopeGate.appliesTo(automation)) {
                val code = try {
                    callerScope.closedCode(automation, event)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    CallerScopeGate.CALLER_UNVERIFIED
                }
                if (code != null) {
                    steps += DryRunStep.Blocked(code)
                    return report(DryRunEnd.BLOCKED, code)
                }
            }

            automation.confirm?.let { confirm ->
                val answer = request.answers[DryRunAnswerKeys.CONFIRM] ?: DryRunAnswer.Text(DryRunAnswerKeys.CONFIRM_RUN)
                steps += DryRunStep.Confirm(confirm.timeoutMs, answer)
                when {
                    answer == DryRunAnswer.NoAnswer -> return report(DryRunEnd.CANCELLED, ConfirmationCodes.EXPIRED)
                    (answer as DryRunAnswer.Text).value != DryRunAnswerKeys.CONFIRM_RUN ->
                        return report(DryRunEnd.CANCELLED, ConfirmationCodes.CANCELLED)
                }
            }
            return runActions(automation.trigger, automation.actions)
        }

        /** The values the start actions change, put back last first; each with the policy's restore check. */
        private suspend fun restoreStep() {
            val seen = linkedMapOf<String, Pair<SpanSlot, Action>>()
            leaves(automation.actions).forEach { action ->
                SpanPolicy.slotOf(action)?.let { slot -> seen.putIfAbsent(slot.key, slot to action) }
            }
            val ordered = seen.values.toList().asReversed()
            val blocked = linkedMapOf<String, String>()
            for ((slot, action) in ordered) {
                val decision = try {
                    firePolicy.evaluateRestore(action)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    FirePolicyDecision.Block("capability_snapshot_unavailable", needsReview = false)
                }
                if (decision is FirePolicyDecision.Block) blocked[slot.key] = decision.code
            }
            steps += DryRunStep.Restore(ordered.map { it.first }, blocked)
        }

        private suspend fun runActions(trigger: Trigger, actions: List<Action>): DryRunReport =
            if (AutomationSchema.requiresP4(automation)) runProgram(trigger, actions) else runFlat(actions)

        /** A flat rule: each action in order, literal (nothing to fill in), a failure does not stop the others. */
        private fun runFlat(actions: List<Action>): DryRunReport {
            actions.forEachIndexed { index, action ->
                val code = DryRunLeafChecks.blockCode(action, event, phoneFacts, program = false)
                steps += DryRunStep.Leaf(ActionPath((index + 1).toString()), action, action, emptyMap(), code, false, null)
            }
            return report(DryRunEnd.COMPLETED)
        }

        private suspend fun runProgram(trigger: Trigger, actions: List<Action>): DryRunReport {
            val recorder = Recorder(actions)
            val interpreter = ProgramInterpreter(
                runner = ProgramActionRunner { resolved, path -> recorder.leaf(resolved, path) },
                stateProvider = overlay::read,
                conditionEvaluator = evaluator,
                interpolator = TaintAwareInterpolator(),
                // A wait is shown, never waited.
                pause = {},
                clock = clock,
                locale = locale,
                questions = ProgramQuestions { question -> recorder.question(question) },
                observer = recorder,
            )
            val result = try {
                interpreter.execute(trigger, event, automation.vars, actions)
            } catch (_: StepLimitReached) {
                steps += recorder.steps
                steps += DryRunStep.Stopped(null, DryRunCodes.STEP_LIMIT)
                return report(DryRunEnd.STEP_LIMIT, DryRunCodes.STEP_LIMIT)
            }
            val recorded = recorder.steps
            val stopCode = result.stopCode
            if (stopCode == null) {
                steps += recorded
                return report(DryRunEnd.COMPLETED)
            }
            val last = recorded.lastOrNull()
            val markedLast = when {
                last is DryRunStep.Leaf && last.path == result.stopPath && last.code == stopCode ->
                    recorded.dropLast(1) + last.copy(stopsRun = true)
                last is DryRunStep.Question && last.path == result.stopPath && last.code == stopCode -> recorded
                else -> null
            }
            steps += markedLast ?: (recorded + DryRunStep.Stopped(result.stopPath, stopCode))
            return report(DryRunEnd.STOPPED, stopCode)
        }

        /** Builds the condition tree with each node's own result, on the state already read. */
        private fun check(condition: Condition, state: DeviceState): ConditionCheck {
            val children = when (condition) {
                is Condition.And -> condition.all.map { check(it, state) }
                is Condition.Or -> condition.any.map { check(it, state) }
                is Condition.Not -> listOf(check(condition.cond, state))
                else -> emptyList()
            }
            return ConditionCheck(condition, evaluator.result(condition, state), children, observed(condition, state))
        }

        private fun observed(condition: Condition, state: DeviceState): String? = when (condition) {
            is Condition.StateEquals -> state.values[condition.key]
            is Condition.StateCompare -> state.queryValues[condition.query.canonicalId]
            is Condition.AppInForeground -> state.foregroundApp
            is Condition.UserModeIs -> state.userMode?.let { it.active ?: "" }
            is Condition.StoredCompare -> state.stored[condition.key]
                ?.takeIf { it.confidentiality != ConfidentialityLabel.SECRET }?.text
            else -> null
        }

        /** The steps of a program in order, built from what the interpreter observes and what the runner records. */
        private inner class Recorder(private val actions: List<Action>) : ProgramObserver {
            val steps = mutableListOf<DryRunStep>()
            private val leaves = HashMap<String, DryRunStep.Leaf>()
            private val questions = HashMap<String, DryRunStep.Question>()
            private var count = 0

            private fun tick() {
                count += 1
                if (count > MAX_STEPS) throw StepLimitReached()
            }

            override fun onCondition(path: ActionPath, loop: Boolean, result: ConditionEvaluator.Result) {
                tick()
                val action = path.resolve(actions) ?: return
                steps += DryRunStep.Flow(path, action, loop, result)
            }

            override fun onStep(step: ProgramStep) {
                tick()
                val code = (step.result as? ActionResult.Failure)?.reason
                val action = step.path.resolve(actions) ?: return
                steps += when (action) {
                    is Action.Wait -> DryRunStep.Wait(step.path, action.durationMs)
                    is Action.Ask -> (questions.remove(step.path.value) ?: DryRunStep.Question(
                        step.path, action, prompt = null, promptTainted = emptyList(),
                        answer = DryRunAnswer.NoAnswer, code = null, key = DryRunAnswerKeys.forPath(step.path),
                    )).copy(code = code)
                    // A leaf the runner saw keeps what it recorded; one it never saw was refused by the interpolator.
                    else -> leaves.remove(step.path.value)?.let { recorded ->
                        if (code != null && recorded.code == null) recorded.copy(code = code) else recorded
                    } ?: DryRunStep.Leaf(step.path, action, null, emptyMap(), code, false, null)
                }
            }

            suspend fun leaf(resolved: ResolvedProgramAction, path: ActionPath): ProgramActionResult {
                val action = resolved.action
                val original = path.resolve(actions) ?: action
                val code = DryRunLeafChecks.blockCode(action, event, phoneFacts, program = true)
                    ?: overlay.apply(resolved)
                val input = if (code == null) simulatedInput(action, path) else null
                leaves[path.value] = DryRunStep.Leaf(path, original, action, resolved.taintedRanges, code, false, input)
                if (code != null) return ProgramActionResult(ActionResult.Failure(code))
                if (input == null) return ProgramActionResult(ActionResult.Success)
                if (input.kind == SimulatedInputKind.HTTP_RESPONSE) {
                    // The real capture rules on the sample body: pointer, scalar, length, control characters.
                    val extract = (action as Action.HttpRequest).extract
                    return when (val captured = HttpCapturePolicy.capture(input.answer, extract)) {
                        is HttpCapturePolicy.Outcome.Captured ->
                            ProgramActionResult(ActionResult.Success, capturedText = captured.text)
                        is HttpCapturePolicy.Outcome.Failed -> {
                            leaves[path.value] = leaves.getValue(path.value).copy(code = captured.code)
                            ProgramActionResult(ActionResult.Failure(captured.code))
                        }
                    }
                }
                return ProgramActionResult(ActionResult.Success, capturedText = input.answer)
            }

            private fun simulatedInput(action: Action, path: ActionPath): SimulatedInput? {
                val key = DryRunAnswerKeys.forPath(path)
                val typed = (request.answers[key] as? DryRunAnswer.Text)?.value
                return when (action) {
                    is Action.InvokeLlm -> if (action.captureAs == null) {
                        null
                    } else {
                        val output = action.output
                        val default = if (output == null) {
                            request.defaultText
                        } else {
                            when (output.kind) {
                                OutputKind.CHOICE -> output.choices.firstOrNull().orEmpty()
                                OutputKind.NUMBER -> (output.min ?: 0L).toString()
                                OutputKind.BOOLEAN -> "true"
                            }
                        }
                        SimulatedInput(SimulatedInputKind.AI_ANSWER, key, typed ?: default, output)
                    }
                    is Action.RunShell -> action.captureAs?.let {
                        SimulatedInput(SimulatedInputKind.SHELL_OUTPUT, key, typed ?: request.defaultText)
                    }
                    is Action.HttpRequest -> action.captureAs?.let {
                        SimulatedInput(SimulatedInputKind.HTTP_RESPONSE, key, typed ?: request.defaultText)
                    }
                    else -> null
                }
            }

            /** `ask`: the user's choice, or no answer (the approved default, or the program stops). */
            fun question(question: ProgramQuestion): QuestionOutcome {
                val key = DryRunAnswerKeys.forPath(question.path)
                val action = question.path.resolve(actions) as? Action.Ask
                    ?: return QuestionOutcome.Unavailable(ProgramInterpreterCodes.ASK_FAILED)
                val answer = request.answers[key]
                    ?: DryRunAnswer.Text(action.defaultChoice ?: question.choices.first())
                questions[question.path.value] = DryRunStep.Question(
                    question.path, action, question.prompt, question.promptTainted, answer, code = null, key = key,
                )
                return when (answer) {
                    is DryRunAnswer.Text -> QuestionOutcome.Answered(answer.value)
                    DryRunAnswer.NoAnswer -> QuestionOutcome.TimedOut
                }
            }
        }

        /** Stored values and the user mode as this run would leave them. Memory only; reads fall through. */
        private inner class Overlay {
            private val stored = LinkedHashMap<String, StoredValue?>()
            private var mode: String? = null
            private var modeChanged = false

            suspend fun read(request: StateReadRequest): DeviceState {
                if (request.isEmpty) return DeviceState()
                val real = try {
                    readState(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    DeviceState()
                }
                val patchedStored = real.stored.toMutableMap()
                stored.forEach { (key, value) ->
                    if (key in request.storedKeys) {
                        if (value == null) patchedStored.remove(key) else patchedStored[key] = value
                    }
                }
                return real.copy(
                    stored = patchedStored,
                    userMode = real.userMode?.let { snapshot ->
                        if (modeChanged) snapshot.copy(active = mode) else snapshot
                    },
                )
            }

            /** The code that would fail a `set_stored` or `set_user_mode`; on success the overlay changes. */
            suspend fun apply(resolved: ResolvedProgramAction): String? = when (val action = resolved.action) {
                is Action.SetStored -> {
                    val current = read(StateReadRequest(storedKeys = setOf(action.key))).stored[action.key]
                    // The 64-key cap needs the whole store: not simulated (keyCount 0).
                    when (
                        val write = StoredValueOps.plan(
                            action,
                            current,
                            keyCount = 0,
                            integrity = resolved.inputIntegrity,
                            confidentiality = resolved.inputConfidentiality,
                            author = ChangeAuthor.Rule(automation.id),
                            atMillis = atMillis,
                        )
                    ) {
                        is StoredWrite.Put -> {
                            stored[action.key] = write.value
                            null
                        }
                        is StoredWrite.Remove -> {
                            stored[action.key] = null
                            null
                        }
                        StoredWrite.Unchanged -> null
                        is StoredWrite.Rejected -> write.code
                    }
                }
                is Action.SetUserMode -> if (!UserModePolicy.validLiteral(action.mode)) {
                    StoredModeActions.USER_MODE_UNKNOWN
                } else {
                    val snapshot = read(StateReadRequest(includeUserMode = true)).userMode
                    val known = snapshot?.modes?.firstOrNull { it.equals(action.mode, ignoreCase = true) }
                    when {
                        snapshot == null -> StoredModeActions.USER_MODE_UNAVAILABLE
                        known == null -> StoredModeActions.USER_MODE_UNKNOWN
                        else -> {
                            mode = known
                            modeChanged = true
                            null
                        }
                    }
                }
                else -> null
            }
        }
    }

    private fun leaves(actions: List<Action>): List<Action> = buildList {
        val pending = ArrayDeque(actions)
        while (pending.isNotEmpty()) {
            when (val action = pending.removeFirst()) {
                is Action.If -> {
                    pending.addAll(0, action.then + action.orElse)
                }
                is Action.While -> pending.addAll(0, action.body)
                else -> add(action)
            }
        }
    }

    private class StepLimitReached : RuntimeException(null, null, false, false)
}

/** The interpreter's `ask` codes the dry run needs. */
private object ProgramInterpreterCodes {
    const val ASK_FAILED = ProgramInterpreter.ASK_FAILED
}
