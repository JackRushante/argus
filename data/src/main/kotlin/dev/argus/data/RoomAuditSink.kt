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
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_BUDGET ->
            event.detail.takeIf { BUDGET_DETAIL.matches(it) } ?: ""
        dev.argus.engine.runtime.AuditKind.BLOCKED_POLICY ->
            event.detail.takeIf { it in SAFE_POLICY_CODES } ?: "policy_blocked"
        dev.argus.engine.runtime.AuditKind.ERROR -> "execution_error"
        // Validazione rifiutata: lista (bounded) di `code` di DraftValidator.ValidationIssue.
        // Sono costanti snake_case: accettiamo solo token di FORMA chiusa e scartiamo il resto.
        dev.argus.engine.runtime.AuditKind.VALIDATION_REJECTED ->
            event.detail.split(',')
                .filter { REASON_CODE.matches(it) }
                .take(MAX_VALIDATION_CODES)
                .joinToString(",")
        dev.argus.engine.runtime.AuditKind.ARM_FAILED ->
            event.detail.takeIf { it in SCHEDULING_REASONS } ?: "arm_failed"
        dev.argus.engine.runtime.AuditKind.SCHEDULING_FAILED ->
            event.detail.takeIf { it in SCHEDULING_REASONS } ?: "scheduling_failed"
        dev.argus.engine.runtime.AuditKind.ENABLE_FAILED ->
            event.detail.takeIf { it in ENABLE_REASONS } ?: "enable_failed"
        dev.argus.engine.runtime.AuditKind.FIRED,
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_DUPLICATE,
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_NOT_ELIGIBLE,
        dev.argus.engine.runtime.AuditKind.CONDITIONS_NOT_MET -> ""
    }

    private companion object {
        val RETRY_AT = Regex("^retry_at=[0-9]{1,19}$")
        val BUDGET_DETAIL = Regex("^(hour|day|month_cost):(global|[a-z][a-z0-9_]{0,32})$")
        /** Codice snake_case a vocabolario chiuso (ValidationIssue.code o reason interno). */
        val REASON_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
        const val MAX_VALIDATION_CODES = 8
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
        /** Reason chiusi condivisi da ARM_FAILED e SCHEDULING_FAILED. */
        val SCHEDULING_REASONS = setOf(
            "capability_unavailable",
            "scheduling_failed",
            "expired",
            "dispatch_failed",
            "reschedule_failed",
            "reschedule_expired",
            "registrar_failed",
        )
        val ENABLE_REASONS = setOf(
            "scheduling_failed",
            "review_required",
        )
    }
}
