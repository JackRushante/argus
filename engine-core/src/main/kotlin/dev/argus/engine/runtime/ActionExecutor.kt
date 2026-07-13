// ActionExecutor.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId

data class FireContext(
    val event: TriggerEvent,
    val state: DeviceState,
    val automationId: AutomationId,
    val eventId: TriggerEventId,
    val executionId: ExecutionId,
)

sealed interface ActionResult {
    /** Azione deterministica completata in modo sincrono. */
    data object Success : ActionResult
    /** Azione GENERATIVA accodata nella lane async (spec §6/C3): execute() NON deve bloccare
     *  10-30 s; l'esito reale della lane viene riportato all'AuditSink. */
    data object Submitted : ActionResult
    data class Failure(val reason: String) : ActionResult
}

fun interface ActionExecutor { suspend fun execute(action: Action, ctx: FireContext): ActionResult }
