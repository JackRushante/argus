package dev.argus.data

import dev.argus.data.dao.AuditDao
import dev.argus.data.entities.AuditEntity
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditSink

/** [AuditSink] su Room (spec §10.6). Insert append-only via DAO suspend. */
class RoomAuditSink(private val dao: AuditDao) : AuditSink {
    override suspend fun record(e: AuditEvent) {
        dao.insert(
            AuditEntity(
                automationId = e.automationId.value,
                kind = e.kind,
                atMillis = e.atMillis,
                detail = redactedDetail(e),
                eventIdHash = e.eventId?.value?.let(::identifierHash),
                executionId = e.executionId?.value,
            )
        )
    }

    private fun redactedDetail(event: AuditEvent): String = when (event.kind) {
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_COOLDOWN ->
            event.detail.takeIf { RETRY_AT.matches(it) } ?: ""
        dev.argus.engine.runtime.AuditKind.BLOCKED_POLICY ->
            event.detail.takeIf { it in SAFE_POLICY_CODES } ?: "policy_blocked"
        dev.argus.engine.runtime.AuditKind.ERROR -> "execution_error"
        dev.argus.engine.runtime.AuditKind.FIRED,
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_DUPLICATE,
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_NOT_ELIGIBLE,
        dev.argus.engine.runtime.AuditKind.CONDITIONS_NOT_MET -> ""
    }

    private companion object {
        val RETRY_AT = Regex("^retry_at=[0-9]{1,19}$")
        val SAFE_POLICY_CODES = setOf(
            "approval_fingerprint_mismatch",
            "capability_snapshot_unavailable",
            "capability_unavailable",
            "live_confirmation_required",
            "reply_event_unverified",
            "reply_notification_unavailable",
            "schema_incompatible",
            "shell_external_trigger",
            "validation_failed",
        )
    }
}
