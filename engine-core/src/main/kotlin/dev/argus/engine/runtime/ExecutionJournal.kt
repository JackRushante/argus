package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import kotlinx.coroutines.flow.Flow

enum class ExecutionStatus {
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    SUBMITTED,
    DEFERRED,
    CANCELLED,
    SUPPRESSED_COOLDOWN,
    SUPPRESSED_NOT_ELIGIBLE,
    INTERRUPTED,
}

enum class ActionJournalOutcome { SUCCEEDED, FAILED, SUBMITTED, DEFERRED }

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
    val deferredCount: Int = 0,
) {
    init {
        require(status != ExecutionStatus.RUNNING) { "Una completion non può restare RUNNING" }
        require(completedAtMillis >= 0) { "completedAtMillis non può essere negativo" }
        require(
            succeededCount >= 0 && failedCount >= 0 && submittedCount >= 0 && deferredCount >= 0,
        ) {
            "I contatori azione non possono essere negativi"
        }
    }
}

data class SubmittedActionState(
    val executionStatus: ExecutionStatus,
    val actionOutcome: ActionJournalOutcome?,
) {
    val ready: Boolean
        get() = executionStatus == ExecutionStatus.SUBMITTED &&
            actionOutcome == ActionJournalOutcome.SUBMITTED
}

data class SubmittedActionCompletion(
    val executionId: ExecutionId,
    val actionIndex: Int,
    val outcome: ActionJournalOutcome,
    val atMillis: Long,
    val errorCode: String? = null,
) {
    init {
        require(actionIndex >= 0) { "actionIndex non può essere negativo" }
        require(outcome != ActionJournalOutcome.SUBMITTED) {
            "La risoluzione async non può restare SUBMITTED"
        }
        require(atMillis >= 0) { "atMillis non può essere negativo" }
        require(errorCode == null || errorCode.matches(Regex("^[a-z][a-z0-9_]{0,63}$"))) {
            "errorCode non valido"
        }
    }
}

interface ExecutionJournal {
    suspend fun recordAction(entry: ActionJournalEntry)
    suspend fun finish(completion: ExecutionCompletion)
}

/** Completion CAS delle azioni generative, separata dal percorso sincrono dell'Engine. */
interface SubmittedActionJournal {
    fun observeSubmission(
        executionId: ExecutionId,
        actionIndex: Int,
    ): Flow<SubmittedActionState?>

    suspend fun resolveSubmitted(completion: SubmittedActionCompletion): Boolean
}

object NoopExecutionJournal : ExecutionJournal {
    override suspend fun recordAction(entry: ActionJournalEntry) = Unit
    override suspend fun finish(completion: ExecutionCompletion) = Unit
}

internal fun Action.journalType(): String = when (this) {
    is Action.SetWifi -> ActionTypeIds.SET_WIFI
    is Action.SetBluetooth -> ActionTypeIds.SET_BLUETOOTH
    is Action.SetDnd -> ActionTypeIds.SET_DND
    is Action.SetRinger -> ActionTypeIds.SET_RINGER
    is Action.LaunchApp -> ActionTypeIds.LAUNCH_APP
    is Action.OpenUrl -> ActionTypeIds.OPEN_URL
    is Action.ShowNotification -> ActionTypeIds.SHOW_NOTIFICATION
    is Action.Tap -> ActionTypeIds.TAP
    is Action.InputText -> ActionTypeIds.INPUT_TEXT
    is Action.WhatsAppReply -> ActionTypeIds.WHATSAPP_REPLY
    is Action.RunShell -> ActionTypeIds.RUN_SHELL
    is Action.InvokeLlm -> ActionTypeIds.INVOKE_LLM
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
    return ExecutionCompletion(executionId, status, atMillis, succeeded, failed, submitted, 0)
}
