// AuditSink.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.AutomationId

enum class AuditKind { FIRED, SUPPRESSED_COOLDOWN, CONDITIONS_NOT_MET, ERROR }
data class AuditEvent(val automationId: AutomationId, val kind: AuditKind, val atMillis: Long, val detail: String = "")

/** Log di ogni scatto/soppressione/errore (spec §10.6). Impl Room in P0-B. */
interface AuditSink { suspend fun record(e: AuditEvent) }
object NoopAuditSink : AuditSink { override suspend fun record(e: AuditEvent) {} }
