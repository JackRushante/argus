package dev.argus.engine.runtime

import dev.argus.engine.model.Action

enum class ExecutionStatus {
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    SUBMITTED,
    CANCELLED,
    SUPPRESSED_COOLDOWN,
    SUPPRESSED_NOT_ELIGIBLE,
    INTERRUPTED,
}

enum class ActionJournalOutcome { SUCCEEDED, FAILED, SUBMITTED }

data class ActionJournalEntry(
    val executionId: ExecutionId,
    val actionIndex: Int,
    val actionType: String,
    val outcome: ActionJournalOutcome,
    val atMillis: Long,
    /** Codice diagnostico non sensibile; mai messaggi/command/contact/text raw. */
    val errorCode: String? = null,
) {
    init {
        require(actionIndex >= 0) { "actionIndex non può essere negativo" }
        require(actionType.matches(Regex("^[a-z][a-z0-9_]{0,63}$"))) { "actionType non valido" }
        require(errorCode == null || errorCode.matches(Regex("^[a-z][a-z0-9_]{0,63}$"))) {
            "errorCode non valido"
        }
        require(atMillis >= 0) { "atMillis non può essere negativo" }
    }
}

data class ExecutionCompletion(
    val executionId: ExecutionId,
    val status: ExecutionStatus,
    val completedAtMillis: Long,
    val succeededCount: Int,
    val failedCount: Int,
    val submittedCount: Int,
) {
    init {
        require(status != ExecutionStatus.RUNNING) { "Una completion non può restare RUNNING" }
        require(completedAtMillis >= 0) { "completedAtMillis non può essere negativo" }
        require(succeededCount >= 0 && failedCount >= 0 && submittedCount >= 0) {
            "I contatori azione non possono essere negativi"
        }
    }
}

interface ExecutionJournal {
    suspend fun recordAction(entry: ActionJournalEntry)
    suspend fun finish(completion: ExecutionCompletion)
}

object NoopExecutionJournal : ExecutionJournal {
    override suspend fun recordAction(entry: ActionJournalEntry) = Unit
    override suspend fun finish(completion: ExecutionCompletion) = Unit
}

internal fun Action.journalType(): String = when (this) {
    is Action.SetWifi -> "set_wifi"
    is Action.SetBluetooth -> "set_bluetooth"
    is Action.SetDnd -> "set_dnd"
    is Action.SetRinger -> "set_ringer"
    is Action.LaunchApp -> "launch_app"
    is Action.OpenUrl -> "open_url"
    is Action.ShowNotification -> "show_notification"
    is Action.Tap -> "tap"
    is Action.InputText -> "input_text"
    is Action.WhatsAppReply -> "whatsapp_reply"
    is Action.RunShell -> "run_shell"
    is Action.InvokeLlm -> "invoke_llm"
}

internal fun ActionResult.journalOutcome(): ActionJournalOutcome = when (this) {
    ActionResult.Success -> ActionJournalOutcome.SUCCEEDED
    ActionResult.Submitted -> ActionJournalOutcome.SUBMITTED
    is ActionResult.Failure -> ActionJournalOutcome.FAILED
}

internal fun List<ActionResult>.completion(
    executionId: ExecutionId,
    atMillis: Long,
    forcedStatus: ExecutionStatus? = null,
): ExecutionCompletion {
    val succeeded = count { it == ActionResult.Success }
    val failed = count { it is ActionResult.Failure }
    val submitted = count { it == ActionResult.Submitted }
    val status = forcedStatus ?: when {
        failed == size && size > 0 -> ExecutionStatus.FAILED
        failed > 0 -> ExecutionStatus.PARTIAL
        submitted > 0 -> ExecutionStatus.SUBMITTED
        else -> ExecutionStatus.SUCCEEDED
    }
    return ExecutionCompletion(executionId, status, atMillis, succeeded, failed, submitted)
}
