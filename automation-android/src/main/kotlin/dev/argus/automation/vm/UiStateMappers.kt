package dev.argus.automation.vm

import dev.argus.automation.ApprovalFlowReview
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.ActionTypeIds
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
import dev.argus.ui.presentation.RenderLanguage
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
    language: RenderLanguage = RenderLanguage.system(),
): AutomationRow {
    val render = RuleRenderMapper.map(this, conversationLabels, language)
    return AutomationRow(
        id = id.value,
        name = name,
        triggerIconKey = render.triggerIconKey,
        triggerSummary = render.triggerLine.removePrefix(RuleRenderMapper.whenPrefix(language)),
        status = status.toStatusBadge(),
        enabled = status == AutomationStatus.ARMED && enabled,
        isGenerative = actions.any { it.tier == ActionTier.GENERATIVE },
        hasWarnings = status == AutomationStatus.NEEDS_REVIEW || render.privacyNote != null,
        lastFiredLabel = lastFiredAt?.let { relativeTimeLabel(it, nowMillis, language) },
        nextFireLabel = nextFireLabel(this, nowMillis, language),
    )
}

internal fun PendingDraft.toAutomationRow(
    review: ApprovalFlowReview?,
    conversationLabels: Map<String, String> = emptyMap(),
    language: RenderLanguage = RenderLanguage.system(),
): AutomationRow {
    val render = RuleRenderMapper.mapDraft(draft, conversationLabels, language)
    return AutomationRow(
        id = id.value,
        name = draft.name,
        triggerIconKey = render.triggerIconKey,
        triggerSummary = render.triggerLine.removePrefix(RuleRenderMapper.whenPrefix(language)),
        status = StatusBadge.PENDING_APPROVAL,
        enabled = false,
        isGenerative = render.isGenerative,
        hasWarnings = integrityError != null || review?.let { reviewWarnings(it, language) }
            .orEmpty()
            .isNotEmpty(),
        lastFiredLabel = null,
        nextFireLabel = null,
    )
}

internal fun reviewWarnings(
    review: ApprovalFlowReview,
    language: RenderLanguage = RenderLanguage.system(),
): List<UiWarning> {
    val render = RuleRenderMapper.mapDraft(review.draft.snapshot.draft, language = language)
    return buildList {
        render.privacyNote?.let {
            add(UiWarning(Severity.WARNING, "privacy_generative", it))
        }
        review.draft.issues.forEach { issue ->
            add(
                UiWarning(
                    issue.severity,
                    issue.code,
                    language.pick(
                        "Validation issue: ${issue.code.safeDiagnostic()}",
                        issue.message,
                    ),
                ),
            )
        }
        review.conflicts.forEach { conflict ->
            add(
                UiWarning(
                    Severity.WARNING,
                    "conflict_${conflict.targetKey.safeCode()}",
                    language.pick(
                        "Conflict on ${conflict.targetKey.safeDiagnostic()}",
                        conflict.message,
                    ),
                ),
            )
        }
    }.distinctBy { Triple(it.severity, it.code, it.text) }
}

internal fun AuditLogRecord.toLogRow(
    actions: List<ActionResultEntity>,
    language: RenderLanguage = RenderLanguage.system(),
): LogRow {
    val outcome = executionStatus.toLogOutcome(kind)
    return LogRow(
        id = id.toString(),
        timeLabel = absoluteTimeLabel(atMillis, language = language),
        automationName = automationName ?: language.pick("Deleted automation", "Automazione rimossa"),
        kind = kind,
        outcome = outcome,
        summary = auditSummary(this, language),
        expandedDetail = actions.takeIf(List<ActionResultEntity>::isNotEmpty)
            ?.map { actionDetail(it, language) },
        automationId = automationId.takeIf { automationName != null },
        isGenerative = submittedCount.orZero() > 0 || actions.any {
            it.actionType == "invoke_llm" || it.actionType == "invoke_llm_v2"
        },
    )
}

private fun Int?.orZero(): Int = this ?: 0

private fun ExecutionStatus?.toLogOutcome(kind: AuditKind): LogOutcome = when {
    kind == AuditKind.ERROR || kind == AuditKind.BLOCKED_POLICY ||
        kind == AuditKind.VALIDATION_REJECTED || kind == AuditKind.ARM_FAILED ||
        kind == AuditKind.SCHEDULING_FAILED || kind == AuditKind.ENABLE_FAILED -> LogOutcome.FAILED
    // Quarantena di sistema: la regola sparisce dalle armate, va resa visibile come problema.
    kind == AuditKind.RULE_NEEDS_REVIEW -> LogOutcome.FAILED
    // Gli altri eventi lifecycle sono transizioni riuscite, non esiti di esecuzione.
    kind == AuditKind.RULE_ARMED || kind == AuditKind.RULE_DISABLED ||
        kind == AuditKind.RULE_ENABLED || kind == AuditKind.RULE_DELETED -> LogOutcome.SUCCESS
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

private fun auditSummary(record: AuditLogRecord, l: RenderLanguage): String = when (record.kind) {
    AuditKind.FIRED -> {
        val succeeded = record.succeededCount ?: 0
        val failed = record.failedCount ?: 0
        val submitted = record.submittedCount ?: 0
        val deferred = record.deferredCount ?: 0
        val total = succeeded + failed + submitted + deferred
        when (record.executionStatus) {
            ExecutionStatus.RUNNING -> l.pick("execution in progress", "esecuzione in corso")
            ExecutionStatus.SUBMITTED -> l.pick(
                "$submitted/$total actions queued",
                "$submitted/$total azioni accodate",
            )
            ExecutionStatus.DEFERRED -> l.pick(
                "$deferred/$total replies to deliver manually",
                "$deferred/$total risposte da inviare manualmente",
            )
            ExecutionStatus.SUCCEEDED -> l.pick(
                "$succeeded/$total actions succeeded",
                "$succeeded/$total azioni riuscite",
            )
            ExecutionStatus.PARTIAL -> l.pick(
                "$succeeded/$total actions succeeded · $failed failed",
                "$succeeded/$total azioni riuscite · $failed fallite",
            )
            ExecutionStatus.FAILED -> l.pick(
                "execution failed · $failed/$total actions failed",
                "esecuzione fallita · $failed/$total azioni fallite",
            )
            ExecutionStatus.CANCELLED -> l.pick("execution cancelled", "esecuzione annullata")
            ExecutionStatus.INTERRUPTED -> l.pick(
                "execution interrupted by the system",
                "esecuzione interrotta dal sistema",
            )
            ExecutionStatus.SUPPRESSED_COOLDOWN -> l.pick(
                "suppressed by cooldown",
                "soppressa dal cooldown",
            )
            ExecutionStatus.SUPPRESSED_BUDGET -> l.pick(
                "blocked by the LLM budget",
                "bloccata dal budget LLM",
            )
            ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE -> l.pick(
                "rule is no longer eligible",
                "regola non più idonea",
            )
            null -> l.pick("execution recorded", "esecuzione registrata")
        }
    }
    AuditKind.SUPPRESSED_DUPLICATE -> l.pick("duplicate event ignored", "evento duplicato ignorato")
    AuditKind.SUPPRESSED_COOLDOWN -> l.pick("suppressed by cooldown", "soppressa dal cooldown")
    AuditKind.SUPPRESSED_BUDGET -> if (record.detail.startsWith("unpriced_model:")) {
        l.pick(
            "blocked: the selected model has no catalog price",
            "bloccata: il modello selezionato non ha un prezzo nel catalogo",
        )
    } else {
        l.pick("blocked by the LLM budget", "bloccata dal budget LLM")
    }
    AuditKind.SUPPRESSED_NOT_ELIGIBLE -> l.pick(
        "rule is no longer eligible",
        "regola non più idonea",
    )
    AuditKind.CONDITIONS_NOT_MET -> when (record.detail) {
        "condition_state_unavailable" -> l.pick(
            "required state unavailable",
            "stato necessario non disponibile",
        )
        else -> l.pick("conditions not met", "condizioni non soddisfatte")
    }
    AuditKind.BLOCKED_POLICY -> when (record.detail) {
        "stale_trigger_registration" -> l.pick(
            "registration is no longer approved",
            "registrazione non più approvata",
        )
        else -> l.pick(
            "blocked by policy (${record.detail.safeDiagnostic()})",
            "bloccata dalla policy (${record.detail.safeDiagnostic()})",
        )
    }
    AuditKind.ERROR -> l.pick(
        "execution error (${record.detail.safeDiagnostic()})",
        "errore di esecuzione (${record.detail.safeDiagnostic()})",
    )
    AuditKind.VALIDATION_REJECTED -> l.pick(
        "Validation rejected: ${record.detail.safeCodeList(l)}",
        "Validazione rifiutata: ${record.detail.safeCodeList(l)}",
    )
    AuditKind.ARM_FAILED -> l.pick(
        "Activation failed: ${record.detail.safeDiagnostic()}",
        "Attivazione fallita: ${record.detail.safeDiagnostic()}",
    )
    AuditKind.SCHEDULING_FAILED -> l.pick(
        "Scheduling failed: ${record.detail.safeDiagnostic()}",
        "Pianificazione non riuscita: ${record.detail.safeDiagnostic()}",
    )
    AuditKind.ENABLE_FAILED -> l.pick(
        "Enable failed: ${record.detail.safeDiagnostic()}",
        "Abilitazione non riuscita: ${record.detail.safeDiagnostic()}",
    )
    // Lifecycle riuscito (task #31-B): chi/quando mette e toglie una regola.
    AuditKind.RULE_ARMED -> l.pick("Rule armed", "Regola armata")
    AuditKind.RULE_DISABLED -> when (record.detail) {
        "user" -> l.pick("Rule disabled by the user", "Regola disabilitata dall'utente")
        "one_shot_consumed" -> l.pick(
            "Rule disabled: one-shot consumed",
            "Regola disabilitata: one-shot consumata",
        )
        "expired" -> l.pick("Rule disabled: expired", "Regola disabilitata: scadenza passata")
        else -> l.pick("Rule disabled", "Regola disabilitata")
    }
    AuditKind.RULE_ENABLED -> l.pick("Rule re-enabled", "Regola riabilitata")
    AuditKind.RULE_DELETED -> l.pick("Rule deleted", "Regola eliminata")
    AuditKind.RULE_NEEDS_REVIEW -> l.pick(
        "Rule needs review (${record.detail.safeDiagnostic()})",
        "Regola da rivedere (${record.detail.safeDiagnostic()})",
    )
}

private fun actionDetail(action: ActionResultEntity, l: RenderLanguage): String {
    val outcome = when (action.outcome) {
        ActionJournalOutcome.SUCCEEDED -> "ok"
        ActionJournalOutcome.SUBMITTED -> l.pick("queued", "accodata")
        ActionJournalOutcome.DEFERRED -> l.pick("deferred", "differita")
        ActionJournalOutcome.FAILED -> l.pick("failed", "fallita")
    }
    val error = action.errorCode?.let { " · ${it.safeDiagnostic()}" }.orEmpty()
    return "${action.actionIndex + 1}. ${actionTypeLabel(action.actionType, l)} → $outcome$error"
}

private fun actionTypeLabel(type: String, l: RenderLanguage): String = when (type) {
    ActionTypeIds.SET_WIFI -> l.pick("Set Wi-Fi", "Imposta Wi-Fi")
    ActionTypeIds.SET_BLUETOOTH -> l.pick("Set Bluetooth", "Imposta Bluetooth")
    ActionTypeIds.SET_DND -> l.pick("Set Do Not Disturb", "Imposta Non disturbare")
    ActionTypeIds.SET_RINGER -> l.pick("Set ringer", "Imposta suoneria")
    ActionTypeIds.LAUNCH_APP -> l.pick("Launch app", "Apri app")
    ActionTypeIds.OPEN_URL -> l.pick("Open URL", "Apri URL")
    ActionTypeIds.SHOW_NOTIFICATION -> l.pick("Show notification", "Mostra notifica")
    ActionTypeIds.TAP -> l.pick("Tap screen", "Tocco schermo")
    ActionTypeIds.INPUT_TEXT -> l.pick("Enter text", "Inserisci testo")
    ActionTypeIds.WHATSAPP_REPLY -> l.pick("WhatsApp reply", "Risposta WhatsApp")
    ActionTypeIds.RUN_SHELL -> l.pick("Run shell command", "Esegui comando shell")
    ActionTypeIds.COPY_TO_CLIPBOARD -> l.pick("Copy to clipboard", "Copia negli appunti")
    ActionTypeIds.SET_ALARM -> l.pick("Set alarm", "Imposta sveglia")
    ActionTypeIds.SET_TIMER -> l.pick("Set timer", "Imposta timer")
    ActionTypeIds.SET_VOLUME -> l.pick("Set volume", "Imposta volume")
    ActionTypeIds.SET_FLASHLIGHT -> l.pick("Set flashlight", "Imposta torcia")
    ActionTypeIds.OPEN_SETTINGS_SCREEN -> l.pick("Open Settings", "Apri Impostazioni")
    ActionTypeIds.VIBRATE -> l.pick("Vibrate", "Vibrazione")
    ActionTypeIds.WAIT -> l.pick("Wait", "Attesa")
    ActionTypeIds.WRITE_SETTING -> l.pick("Write setting", "Scrivi impostazione")
    ActionTypeIds.INVOKE_LLM,
    ActionTypeIds.INVOKE_LLM_V2,
    -> l.pick("AI generation", "Generazione AI")
    ActionTypeIds.IF -> l.pick("Conditional block", "Blocco condizionale")
    ActionTypeIds.WHILE -> l.pick("Repeat block", "Blocco ripetuto")
    else -> type.replace('_', ' ')
}

internal fun relativeTimeLabel(
    atMillis: Long,
    nowMillis: Long,
    language: RenderLanguage = RenderLanguage.system(),
): String {
    val elapsed = (nowMillis - atMillis).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> language.pick("now", "ora")
        elapsed < 3_600_000 -> language.pick(
            "${elapsed / 60_000} min ago",
            "${elapsed / 60_000} min fa",
        )
        elapsed < 86_400_000 -> language.pick(
            "${elapsed / 3_600_000} h ago",
            "${elapsed / 3_600_000} h fa",
        )
        else -> absoluteTimeLabel(atMillis, language = language)
    }
}

internal fun absoluteTimeLabel(
    atMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    language: RenderLanguage = RenderLanguage.system(),
): String {
    val zone = ZoneId.systemDefault()
    val value = Instant.ofEpochMilli(atMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day = value.toLocalDate()
    val prefix = when {
        day == today -> language.pick("today", "oggi")
        day == today.minusDays(1) -> language.pick("yesterday", "ieri")
        else -> day.format(DAY_FORMAT)
    }
    return "$prefix ${value.format(TIME_FORMAT)}"
}

private fun nextFireLabel(
    automation: Automation,
    nowMillis: Long,
    language: RenderLanguage,
): String? {
    if (automation.status != AutomationStatus.ARMED || !automation.enabled) return null
    val trigger = automation.trigger as? Trigger.Time ?: return null
    val next = runCatching { TimeSpecs.nextFire(trigger, Instant.ofEpochMilli(nowMillis)) }.getOrNull()
        ?: return null
    val zone = ZoneId.systemDefault()
    val value = next.atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, value.toLocalDate())
    val day = when (days) {
        0L -> language.pick("today", "oggi")
        1L -> language.pick("tomorrow", "domani")
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

/** Lista di code (VALIDATION_REJECTED è join di più code); ognuno resta a vocabolario chiuso. */
private fun String.safeCodeList(language: RenderLanguage): String = split(',')
    .filter { it.isNotBlank() }
    .joinToString(", ") { it.safeDiagnostic() }
    .ifBlank { language.pick("unknown reason", "motivo sconosciuto") }

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)
