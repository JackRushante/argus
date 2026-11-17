package dev.argus.ui.model

import dev.argus.engine.dryrun.DryRunField

// =============================================================================
// 0.8 · «Prova a secco» (dry run): contracts of the stateless screen. The steps are rendered from the types of
// the simulated run (DryRunRenderMapper), never from a model's paraphrase (invariant 4).
// =============================================================================

/** How a step reads at a glance: icon and colour pair of its badge (never the only signal: the title says it). */
enum class DryRunTone {
    /** It would happen. */
    OK,

    /** An AI step: the AI is not called, the user picks the answer. */
    AI,

    /** It would wait for the user (`ask`, `confirm`): the user picks the answer. */
    ASK,

    /** It would be blocked or refused, with its reason. */
    BLOCKED,

    /** The run would stop here. */
    STOPPED,

    /** Nothing more would happen (the trigger does not match, a condition is false). */
    SKIPPED,

    /** A decision or a pause of the program. */
    INFO,
}

/** A piece of the final text an action would use; [external] = it comes from outside (untrusted, highlighted). */
data class DryRunCodeSpan(val text: String, val external: Boolean = false)

data class DryRunOptionUi(
    val value: String,
    val label: String,
    /** «No answer»: the question or confirmation expires. */
    val noAnswer: Boolean = false,
)

/** The answer the user picks (or types) for a step the dry run cannot ask for real. */
data class DryRunChoiceUi(
    val key: String,
    val options: List<DryRunOptionUi> = emptyList(),
    val selected: String? = null,
    val noAnswerSelected: Boolean = false,
    /** A text field instead of buttons (free AI answer, shell output, server response, a number). */
    val freeText: Boolean = false,
    val text: String = "",
    val hint: String? = null,
    val numeric: Boolean = false,
)

data class DryRunStepUi(
    val tone: DryRunTone,
    val title: String,
    val detail: String? = null,
    /** The final command, URL, body, message… exactly as it would go out, in full (invariant 6). */
    val code: List<DryRunCodeSpan>? = null,
    /** [code] is a shell command (labelled as such). */
    val shell: Boolean = false,
    /** What the highlighted parts are. */
    val codeNote: String? = null,
    val choice: DryRunChoiceUi? = null,
    /** Nesting in the program (inside an `if` or a `while`). */
    val depth: Int = 0,
)

data class DryRunFieldUi(val field: DryRunField, val value: String)

data class DryRunState(
    val ruleName: String = "",
    val loading: Boolean = true,
    val missing: Boolean = false,
    /** The editable sample event; empty = the trigger has no payload to type. */
    val fields: List<DryRunFieldUi> = emptyList(),
    /** The rule is a span: the user chooses to simulate its start or its end. */
    val span: Boolean = false,
    val simulateEnd: Boolean = false,
    /** NFC trigger: offers «verified scan». */
    val nfc: Boolean = false,
    val verifiedScan: Boolean = false,
    /** Notes on how this run is simulated (not armed, the end of a span, a verified scan, unreadable state). */
    val notes: List<String> = emptyList(),
    val steps: List<DryRunStepUi> = emptyList(),
)

interface DryRunCallbacks {
    fun onBack()
    fun onFieldChange(field: DryRunField, value: String)
    fun onVerifiedScanChange(verified: Boolean)
    fun onSimulateEndChange(end: Boolean)
    fun onRun()
    fun onAnswer(key: String, value: String)
    fun onNoAnswer(key: String)
}
