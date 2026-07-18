package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.StateValueCoercion
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import dev.argus.engine.model.declaredType
import dev.argus.engine.model.integrity
import dev.argus.engine.model.provenance
import dev.argus.engine.safety.SafeExtractionRegex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Percorso user-readable, one-based, stabile nell'albero approvato. */
@JvmInline
value class ActionPath(val value: String) {
    init {
        require(PATH.matches(value)) { "Percorso azione non valido" }
    }

    internal fun branch(name: String, index: Int): ActionPath =
        ActionPath("$value.$name.${index + 1}")

    internal fun iteration(iteration: Int, index: Int): ActionPath =
        ActionPath("$value.while[$iteration].${index + 1}")

    companion object {
        private val PATH = Regex("^[1-9][0-9]*(?:\\.(?:then|else)\\.[1-9][0-9]*|\\.while\\[[1-9][0-9]*\\]\\.[1-9][0-9]*)*$")
        internal fun root(index: Int): ActionPath = ActionPath((index + 1).toString())
    }
}

data class ProgramActionResult(
    val result: ActionResult,
    /** Presente solo per una capture completata realmente; mai per SUBMITTED. */
    val capturedText: String? = null,
) {
    override fun toString(): String =
        "ProgramActionResult(result=$result, capturedText=${if (capturedText == null) "<absent>" else "<redacted>"})"
}

fun interface ProgramActionRunner {
    suspend fun execute(action: ResolvedProgramAction, path: ActionPath): ProgramActionResult
}

data class ProgramJournalEntry(
    val path: ActionPath,
    val actionType: String,
    val outcome: ActionJournalOutcome,
    val errorCode: String? = null,
)

fun interface ProgramExecutionJournal {
    suspend fun record(entry: ProgramJournalEntry)
}

object NoopProgramExecutionJournal : ProgramExecutionJournal {
    override suspend fun record(entry: ProgramJournalEntry) = Unit
}

data class ProgramStep(
    val path: ActionPath,
    val actionType: String,
    val result: ActionResult,
)

data class ProgramExecutionResult(
    val steps: List<ProgramStep>,
    /** null = programma completato; altrimenti codice bounded e senza payload. */
    val stopCode: String? = null,
    val stopPath: ActionPath? = null,
) {
    val completed: Boolean get() = stopCode == null
}

/**
 * Interprete deterministico P4-B. Risolve i binding una volta, rilegge lo stato a ogni condizione
 * di flusso, esegue solo foglie risolte e mantiene una deadline dura di sei ore.
 */
class ProgramInterpreter(
    private val runner: ProgramActionRunner,
    private val stateProvider: suspend (StateReadRequest) -> DeviceState,
    private val conditionEvaluator: ConditionEvaluator,
    private val interpolator: TaintAwareInterpolator = TaintAwareInterpolator(),
    private val journal: ProgramExecutionJournal = NoopProgramExecutionJournal,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun execute(
        trigger: Trigger,
        event: TriggerEvent,
        bindings: List<VarBinding>,
        actions: List<Action>,
    ): ProgramExecutionResult {
        if (!validProgram(bindings, actions)) {
            return ProgramExecutionResult(emptyList(), stopCode = "invalid_program")
        }
        if (!TriggerMatcher().matches(trigger, event)) {
            return ProgramExecutionResult(emptyList(), stopCode = "trigger_mismatch")
        }
        val runtime = Runtime(trigger, event)
        val completed = withTimeoutOrNull(MAX_EXECUTION_MILLIS) {
            runtime.initialize(bindings) && runtime.runActions(actions, ActionPath::root, depth = 0)
        }
        return when {
            completed == null -> runtime.result("deadline_exceeded", runtime.currentPath)
            runtime.stopCode != null -> runtime.result(runtime.stopCode, runtime.stopPath)
            else -> runtime.result(null, null)
        }
    }

    private inner class Runtime(
        private val trigger: Trigger,
        private val event: TriggerEvent,
    ) {
        private val scope = VarScope()
        private val steps = mutableListOf<ProgramStep>()
        var stopCode: String? = null
            private set
        var stopPath: ActionPath? = null
            private set
        var currentPath: ActionPath? = null
            private set

        suspend fun initialize(bindings: List<VarBinding>): Boolean {
            if (bindings.size > MAX_VARIABLES) return stop("invalid_program", null)
            val stateBindings = bindings.filterIsInstance<VarBinding.State>()
            val state = if (stateBindings.isEmpty()) {
                DeviceState()
            } else {
                try {
                    stateProvider(StateReadRequest(queries = stateBindings.mapTo(linkedSetOf()) { it.query }))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return stop("binding_state_unavailable", null)
                }
            }
            for (binding in bindings) {
                val value = resolveBinding(binding, state) ?: return stop("binding_unavailable", null)
                try {
                    scope.assign(binding.name, value)
                } catch (_: IllegalArgumentException) {
                    return stop("invalid_program", null)
                }
            }
            return true
        }

        suspend fun runActions(
            actions: List<Action>,
            pathFor: (Int) -> ActionPath,
            depth: Int,
        ): Boolean {
            if (depth > MAX_FLOW_DEPTH || actions.size > MAX_ACTION_NODES) {
                return stop("invalid_program", null)
            }
            for ((index, action) in actions.withIndex()) {
                val path = pathFor(index)
                when (action) {
                    is Action.If -> {
                        val result = evaluate(action.condition, path)
                        val branch = when (result) {
                            ConditionEvaluator.Result.MET -> action.then to "then"
                            ConditionEvaluator.Result.NOT_MET -> action.orElse to "else"
                            ConditionEvaluator.Result.STATE_UNAVAILABLE ->
                                return stop("condition_state_unavailable", path)
                        }
                        if (!runActions(branch.first, { child -> path.branch(branch.second, child) }, depth + 1)) {
                            return false
                        }
                    }
                    is Action.While -> {
                        if (action.maxIterations !in 1..MAX_WHILE_ITERATIONS ||
                            action.delayBetweenMs !in 0..MAX_PAUSE_MILLIS || action.body.isEmpty()
                        ) {
                            return stop("invalid_program", path)
                        }
                        var iteration = 1
                        while (iteration <= action.maxIterations) {
                            when (evaluate(action.condition, path)) {
                                ConditionEvaluator.Result.NOT_MET -> break
                                ConditionEvaluator.Result.STATE_UNAVAILABLE ->
                                    return stop("condition_state_unavailable", path)
                                ConditionEvaluator.Result.MET -> Unit
                            }
                            if (!runActions(
                                    action.body,
                                    { child -> path.iteration(iteration, child) },
                                    depth + 1,
                                )
                            ) {
                                return false
                            }
                            if (iteration < action.maxIterations && action.delayBetweenMs > 0) {
                                currentPath = path
                                pause(action.delayBetweenMs)
                                currentPath = null
                            }
                            iteration += 1
                        }
                    }
                    is Action.Wait -> {
                        if (action.durationMs !in 1..MAX_PAUSE_MILLIS) {
                            return stop("invalid_program", path)
                        }
                        currentPath = path
                        pause(action.durationMs)
                        currentPath = null
                        record(path, action, ActionResult.Success)
                    }
                    else -> if (!runLeaf(action, path)) return false
                }
            }
            return true
        }

        private suspend fun runLeaf(action: Action, path: ActionPath): Boolean {
            val resolved = when (val resolution = interpolator.resolve(action, scope)) {
                is ActionResolution.Blocked -> {
                    record(path, action, ActionResult.Failure(resolution.code))
                    return stop(resolution.code, path)
                }
                is ActionResolution.Resolved -> resolution.value
            }
            currentPath = path
            val executed = try {
                runner.execute(resolved, path).also { currentPath = null }
            } catch (error: CancellationException) {
                // Mantieni currentPath: se è la deadline interna, il result indica la foglia
                // interrotta; se è cancellazione esterna, l'eccezione continua a propagare.
                throw error
            } catch (_: Exception) {
                currentPath = null
                ProgramActionResult(ActionResult.Failure("executor_exception"))
            }

            val captureName = captureName(action)
            var result = executed.result
            if (captureName != null && result !is ActionResult.Failure) {
                if (result != ActionResult.Success || executed.capturedText == null) {
                    result = ActionResult.Failure("capture_missing")
                    record(path, action, result)
                    return stop("capture_missing", path)
                }
                val captured = capturedValue(action, resolved, executed.capturedText)
                try {
                    scope.assign(captureName, captured)
                } catch (_: IllegalArgumentException) {
                    result = ActionResult.Failure("capture_too_long")
                    record(path, action, result)
                    return stop("capture_too_long", path)
                }
            }
            record(path, action, result)
            return true // D7: un fallimento foglia non impedisce le successive.
        }

        private suspend fun evaluate(
            condition: Condition,
            path: ActionPath,
        ): ConditionEvaluator.Result {
            currentPath = path
            val request = StateReadPlanner.forCondition(condition)
            val state = if (request.isEmpty) {
                DeviceState()
            } else {
                try {
                    stateProvider(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    DeviceState()
                }
            }
            return conditionEvaluator.flowResult(condition, state, scope::get).also {
                currentPath = null
            }
        }

        private fun resolveBinding(binding: VarBinding, state: DeviceState): VarValue? {
            val raw = when (binding) {
                is VarBinding.Literal -> binding.value
                is VarBinding.State -> state.queryValues[binding.query.canonicalId]
                    ?.takeIf { StateValueCoercion.compatible(it, binding.valueType) }
                is VarBinding.TriggerPayload -> triggerValue(binding.field)?.let { payload ->
                    val pattern = binding.extractionRegex
                    if (pattern == null) {
                        payload
                    } else {
                        when (val extracted = SafeExtractionRegex.extract(pattern, payload)) {
                            is SafeExtractionRegex.Result.Match -> extracted.value
                            SafeExtractionRegex.Result.NoMatch,
                            SafeExtractionRegex.Result.InvalidPattern,
                            -> null
                        }
                    }
                }
            } ?: return null
            if (raw.length > VarScope.MAX_RUNTIME_VALUE_CHARS) return null
            return VarValue(
                text = normalize(raw, binding.declaredType) ?: return null,
                type = binding.declaredType,
                integrity = binding.integrity,
                confidentiality = binding.confidentiality,
                provenance = binding.provenance(trigger),
            )
        }

        private fun triggerValue(field: TriggerField): String? {
            val current = event
            return when {
                trigger is Trigger.Notification && current is TriggerEvent.NotificationPosted ->
                    when (field) {
                        TriggerField.TEXT -> current.text
                        TriggerField.TITLE -> current.title
                        TriggerField.SENDER -> current.sender
                        TriggerField.NUMBER -> null
                    }
                trigger is Trigger.PhoneState && current is TriggerEvent.PhoneStateChanged &&
                    trigger.event == current.event -> when (trigger.event) {
                        PhoneEvent.SMS_RECEIVED -> when (field) {
                            TriggerField.TEXT -> current.smsText
                            TriggerField.NUMBER -> current.number
                            TriggerField.TITLE, TriggerField.SENDER -> null
                        }
                        PhoneEvent.INCOMING_CALL,
                        PhoneEvent.CALL_ENDED,
                        -> if (field == TriggerField.NUMBER) current.number else null
                    }
                else -> null
            }
        }

        private suspend fun record(path: ActionPath, action: Action, result: ActionResult) {
            val step = ProgramStep(path, action.journalType(), result)
            steps += step
            try {
                journal.record(
                    ProgramJournalEntry(
                        path = path,
                        actionType = step.actionType,
                        outcome = result.journalOutcome(),
                        errorCode = (result as? ActionResult.Failure)?.reason?.boundedCode(),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Come il journal flat: l'osservabilità non cambia l'esito del programma.
            }
        }

        private fun stop(code: String, path: ActionPath?): Boolean {
            stopCode = code
            stopPath = path
            return false
        }

        fun result(code: String?, path: ActionPath?): ProgramExecutionResult =
            ProgramExecutionResult(steps.toList(), code, path)
    }

    private fun capturedValue(
        action: Action,
        resolved: ResolvedProgramAction,
        text: String,
    ): VarValue {
        val producer = when (action) {
            is Action.RunShell -> ValueProvenance.SHELL
            is Action.InvokeLlm, is Action.InvokeLlmV2 -> ValueProvenance.MODEL
            else -> error("Azione non catturabile")
        }
        val provenance = buildSet {
            addAll(resolved.inputProvenance)
            if (action is Action.InvokeLlmV2 && action.stateContext.isNotEmpty()) {
                add(ValueProvenance.DEVICE_STATE)
            }
            add(producer)
        }
        return VarValue(
            text = text,
            type = VarType.TEXT,
            integrity = IntegrityLabel.TAINTED,
            // Floor conservativo P4-A: output shell/model può riflettere qualunque input segreto.
            confidentiality = ConfidentialityLabel.SECRET,
            provenance = provenance,
        )
    }

    private fun captureName(action: Action): String? = when (action) {
        is Action.RunShell -> action.captureAs
        is Action.InvokeLlm -> action.captureAs
        is Action.InvokeLlmV2 -> action.captureAs
        else -> null
    }

    /** Defense in depth iterativa: anche un ramo non scelto deve rispettare tutti i bound. */
    private fun validProgram(bindings: List<VarBinding>, actions: List<Action>): Boolean {
        if (bindings.size > MAX_VARIABLES) return false
        val names = bindings.map { it.name }
        if (names.any { !VarBinding.NAME_REGEX.matches(it) } || names.size != names.toSet().size) {
            return false
        }
        data class Frame(val action: Action, val depth: Int)
        val pending = ArrayDeque<Frame>()
        actions.forEach { pending.addLast(Frame(it, 0)) }
        val captures = mutableListOf<String>()
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (action, depth) = pending.removeFirst()
            nodes += 1
            if (nodes > MAX_ACTION_NODES || depth > MAX_FLOW_DEPTH) return false
            captureName(action)?.let(captures::add)
            when (action) {
                is Action.If -> {
                    if (action.then.isEmpty() && action.orElse.isEmpty()) return false
                    action.then.forEach { pending.addLast(Frame(it, depth + 1)) }
                    action.orElse.forEach { pending.addLast(Frame(it, depth + 1)) }
                }
                is Action.While -> {
                    if (action.body.isEmpty() || action.maxIterations !in 1..MAX_WHILE_ITERATIONS ||
                        action.delayBetweenMs !in 0..MAX_PAUSE_MILLIS
                    ) {
                        return false
                    }
                    action.body.forEach { pending.addLast(Frame(it, depth + 1)) }
                }
                is Action.Wait -> if (action.durationMs !in 1..MAX_PAUSE_MILLIS) return false
                else -> Unit
            }
        }
        if (captures.any { !VarBinding.NAME_REGEX.matches(it) }) return false
        val allNames = names + captures
        return allNames.size <= MAX_VARIABLES && allNames.size == allNames.toSet().size
    }

    private fun normalize(raw: String, type: VarType): String? = when (type) {
        VarType.TEXT -> raw
        VarType.NUMBER -> raw.takeIf { StateValueCoercion.number(it) != null }
        VarType.BOOLEAN -> StateValueCoercion.boolean(raw)?.toString()
    }

    private fun String.boundedCode(): String =
        takeIf { it.matches(Regex("^[a-z][a-z0-9_]{0,63}$")) } ?: "action_failed"

    companion object {
        const val MAX_EXECUTION_MILLIS = 6L * 60 * 60 * 1_000
        private const val MAX_VARIABLES = 16
        private const val MAX_FLOW_DEPTH = 4
        private const val MAX_ACTION_NODES = 64
        private const val MAX_WHILE_ITERATIONS = 1_000
        private const val MAX_PAUSE_MILLIS = 3_600_000L
    }
}
