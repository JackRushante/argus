// AuditSink.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.AutomationId

enum class AuditKind {
    FIRED,
    SUPPRESSED_DUPLICATE,
    SUPPRESSED_COOLDOWN,
    SUPPRESSED_NOT_ELIGIBLE,
    CONDITIONS_NOT_MET,
    BLOCKED_POLICY,
    ERROR,
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
