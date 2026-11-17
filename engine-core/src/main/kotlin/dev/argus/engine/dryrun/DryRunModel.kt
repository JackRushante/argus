package dev.argus.engine.dryrun

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.Condition
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.SpanSlot
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionPath
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.SpanPhase

// =============================================================================
// 0.8 «Prova a secco» (dry run): simulate ONE run of a rule and say, step by step, what would happen. Nothing
// here executes: these are the inputs the user types and the report the simulator writes, all in memory.
// =============================================================================

/**
 * What the user typed for the sample event. Only the fields of the rule's trigger are used (see
 * [DryRunEvents.fields]). In memory only: never persisted, never logged, never in an exception.
 */
data class DryRunSample(
    val sender: String = "",
    val title: String = "",
    val text: String = "",
    val number: String = "",
    /** Wi-Fi network or Bluetooth device of a connectivity trigger. */
    val name: String = "",
    /** The mode a `user_mode_changed` trigger without `to` switches to. */
    val mode: String = "",
    /** An NFC scan counts as verified (it came from the system NFC service) only when the user says so. */
    val verifiedScan: Boolean = false,
) {
    override fun toString(): String = "DryRunSample(<redacted>, verifiedScan=$verifiedScan)"
}

/** The editable fields of a sample event, in form order. */
enum class DryRunField { SENDER, TITLE, TEXT, NUMBER, NAME, MODE }

/** The answer the user picked for something a real run would wait for (AI, question, confirmation, captured output). */
sealed interface DryRunAnswer {
    data class Text(val value: String) : DryRunAnswer {
        override fun toString(): String = "Text(<redacted>)"
    }

    /** Nobody answers in time (a question or a confirmation that expires). */
    data object NoAnswer : DryRunAnswer
}

object DryRunAnswerKeys {
    /** The answer to `confirm` on the rule. */
    const val CONFIRM = "confirm"
    const val CONFIRM_RUN = "run"
    const val CONFIRM_CANCEL = "cancel"

    /** The answer for the step at [path] (one per loop iteration: the path carries it). */
    fun forPath(path: ActionPath): String = "step:${path.value}"
}

/**
 * Read-only facts about the phone that the executor checks at fire time. Read once per dry run; values only,
 * never a handle to anything that could act.
 */
data class DryRunFacts(
    /** The whitelisted conversations (who may trigger shell commands and macros). */
    val whitelistedConversationIds: Set<String> = emptySet(),
    /** A privileged backend (Shizuku, Sui or verified root) is ready. */
    val privilegedReady: Boolean = false,
    /** The NAMES of the saved secrets. Never a value. */
    val secretNames: Set<String> = emptySet(),
    val hasTelephony: Boolean = false,
    val canSendSms: Boolean = false,
    val canCall: Boolean = false,
    /** Argus's own package, never a target of an intent. */
    val ownPackage: String? = null,
)

/** Reads [DryRunFacts]. Implementations only read. */
fun interface DryRunFactsSource {
    suspend fun read(): DryRunFacts

    companion object {
        /** Nothing is known: every check that needs a fact fails closed. */
        val NONE = DryRunFactsSource { DryRunFacts() }
    }
}

/** One dry run to simulate. */
data class DryRunRequest(
    val automation: Automation,
    /** START, or EXIT for the end of a span rule (ignored for a rule without `exit`). */
    val phase: SpanPhase = SpanPhase.START,
    val sample: DryRunSample = DryRunSample(),
    /** Answers by [DryRunAnswerKeys]; a missing answer takes the default described on each step. */
    val answers: Map<String, DryRunAnswer> = emptyMap(),
    /** Free text used when the user has not typed a simulated AI answer, shell output or HTTP response. */
    val defaultText: String = "",
    /** The trusted display name of the rule's whitelisted chat, if any (prefills the sender). */
    val conversationLabel: String? = null,
) {
    override fun toString(): String = "DryRunRequest(automation=${automation.id.value}, phase=$phase, <redacted>)"
}

/** How the simulated run ends. */
enum class DryRunEnd {
    COMPLETED,

    /** The sample event does not match the trigger: the rule would not fire. */
    NOT_MATCHED,

    /** Paused, refused by the fire policy, or the caller is out of scope: nothing would run. */
    BLOCKED,
    CONDITIONS_NOT_MET,

    /** A condition could not be read: it fails closed (also under `not`). */
    CONDITIONS_UNKNOWN,

    /** The confirmation is cancelled or expires. */
    CANCELLED,

    /** The program stops with a closed code. */
    STOPPED,

    /** The simulation stopped after [DryRunSimulator.MAX_STEPS] steps (a real run would go on). */
    STEP_LIMIT,
}

data class DryRunReport(
    /** The rule as simulated (armed, with its fingerprint). */
    val automation: Automation,
    val phase: SpanPhase,
    val atMillis: Long,
    /** The rule is not armed now (a draft, disabled or to review): simulated as if it were. */
    val simulatedAsArmed: Boolean,
    val steps: List<DryRunStep>,
    val end: DryRunEnd,
    /** The closed code of [end] when it has one. */
    val endCode: String? = null,
)

/** A condition with its result, evaluated with the real evaluator on the state read for the run. */
data class ConditionCheck(
    val condition: Condition,
    val result: ConditionEvaluator.Result,
    val children: List<ConditionCheck> = emptyList(),
    /** The value read for a leaf (never a location, never a SECRET stored value); null = none or unreadable. */
    val observed: String? = null,
)

enum class SimulatedInputKind { AI_ANSWER, SHELL_OUTPUT, HTTP_RESPONSE }

/** A value the user supplies instead of a real AI answer, shell output or server response. */
data class SimulatedInput(
    val kind: SimulatedInputKind,
    val key: String,
    /** What was used: the user's answer or the default. */
    val answer: String,
    /** The approved answer shape of a constrained `invoke_llm`. */
    val output: OutputSpec? = null,
) {
    override fun toString(): String = "SimulatedInput(kind=$kind, key=$key, answer=<redacted>)"
}

sealed interface DryRunStep {
    data class TriggerCheck(val trigger: Trigger, val phase: SpanPhase, val matched: Boolean) : DryRunStep

    /** The run stops before any action: pause, fire policy or caller scope, with the closed code. */
    data class Blocked(val code: String) : DryRunStep

    data class Conditions(val check: ConditionCheck, val result: ConditionEvaluator.Result) : DryRunStep

    data class Confirm(val timeoutMs: Long, val answer: DryRunAnswer, val key: String = DryRunAnswerKeys.CONFIRM) :
        DryRunStep

    /** The end of a span: the values the start changed would be put back, last first. [blocked]: slot key → code. */
    data class Restore(val slots: List<SpanSlot>, val blocked: Map<String, String>) : DryRunStep

    /** The condition of an `if` or (with [loop]) a `while`, with the values of this run. */
    data class Flow(
        val path: ActionPath,
        val action: Action,
        val loop: Boolean,
        val result: ConditionEvaluator.Result,
    ) : DryRunStep

    /**
     * An action. [resolved] is what the executor would receive (after the real interpolator), null when the
     * interpolator refused it; [tainted]: field label → the parts that come from external data. [code] null =
     * it would run; otherwise the closed code that would block or fail it. [stopsRun]: the program stops here.
     */
    data class Leaf(
        val path: ActionPath,
        val action: Action,
        val resolved: Action?,
        val tainted: Map<String, List<IntRange>>,
        val code: String?,
        val stopsRun: Boolean,
        val input: SimulatedInput?,
    ) : DryRunStep {
        override fun toString(): String = "Leaf(path=${path.value}, code=$code, stopsRun=$stopsRun, values=<redacted>)"
    }

    data class Wait(val path: ActionPath, val durationMs: Long) : DryRunStep

    /** An `ask`: the resolved question, the answer used and, when it stops the program, the code. */
    data class Question(
        val path: ActionPath,
        val action: Action.Ask,
        val prompt: String?,
        val promptTainted: List<IntRange>,
        val answer: DryRunAnswer,
        val code: String?,
        val key: String,
    ) : DryRunStep {
        override fun toString(): String = "Question(path=${path.value}, code=$code, values=<redacted>)"
    }

    /** The program stops with [code] where no action step says it (a variable that cannot be filled, …). */
    data class Stopped(val path: ActionPath?, val code: String) : DryRunStep
}
