// ActionExecutor.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ApprovalFingerprint

data class FireContext(
    val event: TriggerEvent,
    val state: DeviceState,
    val automationId: AutomationId,
    /** Firma della revisione approvata che ha generato questa esecuzione. */
    val approvalFingerprint: ApprovalFingerprint,
    val eventId: TriggerEventId,
    val executionId: ExecutionId,
    /** Indice stabile dell'azione nello snapshot approvato. */
    val actionIndex: Int,
    val priority: Int = 0,
) {
    init {
        require(actionIndex >= 0) { "actionIndex non può essere negativo" }
    }
}

sealed interface ActionResult {
    /** Azione deterministica completata in modo sincrono. */
    data object Success : ActionResult
    /** Azione GENERATIVA accodata nella lane async (spec §6/C3): execute() NON deve bloccare
     *  10-30 s; l'esito reale della lane viene riportato all'AuditSink. */
    data object Submitted : ActionResult
    data class Failure(val reason: String) : ActionResult
}

fun interface ActionExecutor { suspend fun execute(action: Action, ctx: FireContext): ActionResult }
