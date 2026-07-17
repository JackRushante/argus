// AuditSink.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.AutomationId

enum class AuditKind {
    FIRED,
    SUPPRESSED_DUPLICATE,
    SUPPRESSED_COOLDOWN,
    SUPPRESSED_BUDGET,
    SUPPRESSED_NOT_ELIGIBLE,
    CONDITIONS_NOT_MET,
    BLOCKED_POLICY,
    ERROR,
    // Fallimenti fuori dal fire-time (audit più ricco, task #31): perché una regola non si arma.
    VALIDATION_REJECTED,
    ARM_FAILED,
    SCHEDULING_FAILED,
    ENABLE_FAILED,
}
data class AuditEvent(
    val automationId: AutomationId,
    val kind: AuditKind,
    val atMillis: Long,
    val detail: String = "",
    val eventId: TriggerEventId? = null,
    val executionId: ExecutionId? = null,
)

/** Log di ogni scatto/soppressione/errore (spec §10.6). Impl Room in P0-B. */
interface AuditSink { suspend fun record(e: AuditEvent) }
object NoopAuditSink : AuditSink { override suspend fun record(e: AuditEvent) {} }
