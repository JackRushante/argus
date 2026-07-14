package dev.argus.automation.vm

import dev.argus.automation.ApprovalFlowReview
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.TimeSpecs
import dev.argus.engine.safety.PendingDraft
import dev.argus.engine.safety.Severity
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRow
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.UiWarning
import dev.argus.ui.presentation.RuleRenderMapper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal fun AutomationStatus.toStatusBadge(): StatusBadge = when (this) {
    AutomationStatus.PENDING_APPROVAL -> StatusBadge.PENDING_APPROVAL
    AutomationStatus.ARMED -> StatusBadge.ARMED
    AutomationStatus.DISABLED -> StatusBadge.DISABLED
    AutomationStatus.NEEDS_REVIEW -> StatusBadge.NEEDS_REVIEW
}

internal fun Automation.toAutomationRow(
    lastFiredAt: Long?,
    nowMillis: Long,
    conversationLabels: Map<String, String> = emptyMap(),
): AutomationRow {
    val render = RuleRenderMapper.map(this, conversationLabels)
    return AutomationRow(
        id = id.value,
        name = name,
        triggerIconKey = render.triggerIconKey,
        triggerSummary = render.triggerLine.removePrefix("Quando: "),
        status = status.toStatusBadge(),
        enabled = status == AutomationStatus.ARMED && enabled,
        isGenerative = actions.any { it.tier == ActionTier.GENERATIVE },
        hasWarnings = status == AutomationStatus.NEEDS_REVIEW || render.privacyNote != null,
        lastFiredLabel = lastFiredAt?.let { relativeTimeLabel(it, nowMillis) },
        nextFireLabel = nextFireLabel(this, nowMillis),
    )
}

internal fun PendingDraft.toAutomationRow(
    review: ApprovalFlowReview?,
    conversationLabels: Map<String, String> = emptyMap(),
): AutomationRow {
    val render = RuleRenderMapper.mapDraft(draft, conversationLabels)
    return AutomationRow(
        id = id.value,
        name = draft.name,
        triggerIconKey = render.triggerIconKey,
        triggerSummary = render.triggerLine.removePrefix("Quando: "),
        status = StatusBadge.PENDING_APPROVAL,
        enabled = false,
        isGenerative = render.isGenerative,
        hasWarnings = integrityError != null || review?.let(::reviewWarnings).orEmpty().isNotEmpty(),
        lastFiredLabel = null,
        nextFireLabel = null,
    )
}

internal fun reviewWarnings(review: ApprovalFlowReview): List<UiWarning> {
    val render = RuleRenderMapper.mapDraft(review.draft.snapshot.draft)
    return buildList {
        render.privacyNote?.let {
            add(UiWarning(Severity.WARNING, "privacy_generative", it))
        }
        review.draft.issues.forEach { issue ->
            add(UiWarning(issue.severity, issue.code, issue.message))
        }
        review.conflicts.forEach { conflict ->
            add(
                UiWarning(
                    Severity.WARNING,
                    "conflict_${conflict.targetKey.safeCode()}",
                    conflict.message,
                ),
            )
        }
    }.distinctBy { Triple(it.severity, it.code, it.text) }
}

internal fun AuditLogRecord.toLogRow(actions: List<ActionResultEntity>): LogRow {
    val outcome = executionStatus.toLogOutcome(kind)
    return LogRow(
        id = id.toString(),
        timeLabel = absoluteTimeLabel(atMillis),
        automationName = automationName ?: "Automazione rimossa",
        kind = kind,
        outcome = outcome,
        summary = auditSummary(this),
        expandedDetail = actions.takeIf(List<ActionResultEntity>::isNotEmpty)?.map(::actionDetail),
        automationId = automationId.takeIf { automationName != null },
        isGenerative = submittedCount.orZero() > 0 || actions.any { it.actionType == "invoke_llm" },
    )
}

private fun Int?.orZero(): Int = this ?: 0

private fun ExecutionStatus?.toLogOutcome(kind: AuditKind): LogOutcome = when {
    kind == AuditKind.ERROR || kind == AuditKind.BLOCKED_POLICY -> LogOutcome.FAILED
    this == ExecutionStatus.PARTIAL -> LogOutcome.PARTIAL
    this == ExecutionStatus.DEFERRED -> LogOutcome.DEFERRED
    this in setOf(
        ExecutionStatus.FAILED,
        ExecutionStatus.CANCELLED,
        ExecutionStatus.INTERRUPTED,
    ) -> LogOutcome.FAILED
    this in setOf(ExecutionStatus.RUNNING, ExecutionStatus.SUBMITTED) -> LogOutcome.SUBMITTED
    else -> LogOutcome.SUCCESS
}

private fun auditSummary(record: AuditLogRecord): String = when (record.kind) {
    AuditKind.FIRED -> {
        val succeeded = record.succeededCount ?: 0
        val failed = record.failedCount ?: 0
        val submitted = record.submittedCount ?: 0
        val deferred = record.deferredCount ?: 0
        val total = succeeded + failed + submitted + deferred
        when (record.executionStatus) {
            ExecutionStatus.RUNNING -> "esecuzione in corso"
            ExecutionStatus.SUBMITTED -> "$submitted/$total azioni accodate"
            ExecutionStatus.DEFERRED -> "$deferred/$total risposte da inviare manualmente"
            ExecutionStatus.SUCCEEDED -> "$succeeded/$total azioni riuscite"
            ExecutionStatus.PARTIAL -> "$succeeded/$total azioni riuscite · $failed fallite"
            ExecutionStatus.FAILED -> "esecuzione fallita · $failed/$total azioni fallite"
            ExecutionStatus.CANCELLED -> "esecuzione annullata"
            ExecutionStatus.INTERRUPTED -> "esecuzione interrotta dal sistema"
            ExecutionStatus.SUPPRESSED_COOLDOWN -> "soppressa dal cooldown"
            ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE -> "regola non più idonea"
            null -> "esecuzione registrata"
        }
    }
    AuditKind.SUPPRESSED_DUPLICATE -> "evento duplicato ignorato"
    AuditKind.SUPPRESSED_COOLDOWN -> "soppressa dal cooldown"
    AuditKind.SUPPRESSED_NOT_ELIGIBLE -> "regola non più idonea"
    AuditKind.CONDITIONS_NOT_MET -> "condizioni non soddisfatte"
    AuditKind.BLOCKED_POLICY -> when (record.detail) {
        "stale_trigger_registration" -> "registrazione non più approvata"
        else -> "bloccata dalla policy (${record.detail.safeDiagnostic()})"
    }
    AuditKind.ERROR -> "errore di esecuzione (${record.detail.safeDiagnostic()})"
}

private fun actionDetail(action: ActionResultEntity): String {
    val outcome = when (action.outcome) {
        ActionJournalOutcome.SUCCEEDED -> "ok"
        ActionJournalOutcome.SUBMITTED -> "accodata"
        ActionJournalOutcome.DEFERRED -> "differita"
        ActionJournalOutcome.FAILED -> "fallita"
    }
    val error = action.errorCode?.let { " · ${it.safeDiagnostic()}" }.orEmpty()
    return "${action.actionIndex + 1}. ${action.actionType.replace('_', ' ')} → $outcome$error"
}

internal fun relativeTimeLabel(atMillis: Long, nowMillis: Long): String {
    val elapsed = (nowMillis - atMillis).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> "ora"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min fa"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} h fa"
        else -> absoluteTimeLabel(atMillis)
    }
}

internal fun absoluteTimeLabel(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val value = Instant.ofEpochMilli(atMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day = value.toLocalDate()
    val prefix = when {
        day == today -> "oggi"
        day == today.minusDays(1) -> "ieri"
        else -> day.format(DAY_FORMAT)
    }
    return "$prefix ${value.format(TIME_FORMAT)}"
}

private fun nextFireLabel(automation: Automation, nowMillis: Long): String? {
    if (automation.status != AutomationStatus.ARMED || !automation.enabled) return null
    val trigger = automation.trigger as? Trigger.Time ?: return null
    val next = runCatching { TimeSpecs.nextFire(trigger, Instant.ofEpochMilli(nowMillis)) }.getOrNull()
        ?: return null
    val zone = ZoneId.systemDefault()
    val value = next.atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, value.toLocalDate())
    val day = when (days) {
        0L -> "oggi"
        1L -> "domani"
        else -> value.toLocalDate().format(DAY_FORMAT)
    }
    return "$day ${value.format(TIME_FORMAT)}"
}

private fun String.safeCode(): String = lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9_]+"), "_")
    .trim('_')
    .take(48)
    .ifBlank { "unknown" }

private fun String.safeDiagnostic(): String = safeCode().replace('_', ' ')

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)
