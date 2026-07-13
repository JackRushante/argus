package dev.argus.engine.safety
import dev.argus.engine.model.*
import dev.argus.engine.runtime.CronSchedule
import java.time.LocalDateTime
import java.time.ZoneId

enum class Severity { ERROR, WARNING }
data class ValidationIssue(val severity: Severity, val code: String, val message: String)

/** Gate di dominio per i draft LLM (spec §10/D7): ERROR blocca l'arm, WARNING è mostrato in approvazione.
 *  Da ri-eseguire anche al fire-time sugli allowed_tools (difesa in profondità, spec §10.4). */
class DraftValidator(
    private val knownTools: Set<String>,
    private val stateKeys: Set<String> = StateKeys.ALL.keys,
) {
    companion object {
        /** Mai ammessi al fire-time generativo (spec §7): eseguirebbero azioni arbitrarie mai approvate. */
        val FORBIDDEN_IN_INVOKE_LLM = setOf("shell.run", "app.install")
        const val FORBIDDEN_PREFIX = "automation."
        /** Prefisso nudo (senza punto): anche `automation` da solo è vietato. */
        const val FORBIDDEN_PREFIX_BARE = "automation"
    }

    fun validate(d: AutomationDraft, whitelistedIds: Set<String> = emptySet()): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun err(code: String, msg: String) { issues += ValidationIssue(Severity.ERROR, code, msg) }
        fun warn(code: String, msg: String) { issues += ValidationIssue(Severity.WARNING, code, msg) }

        if (d.actions.isEmpty()) err("no_actions", "Il draft non contiene azioni")

        when (val t = d.trigger) {
            is Trigger.Time -> {
                if ((t.cron == null) == (t.at == null)) err("time_spec", "Time richiede esattamente uno tra cron e at")
                runCatching { ZoneId.of(t.tz) }.onFailure { err("tz_invalid", "Timezone '${t.tz}' non valida") }
                t.cron?.let { c -> runCatching { CronSchedule.parse(c) }.onFailure { err("cron_invalid", "Cron '$c' non valido: ${it.message}") } }
                t.at?.let { a -> runCatching { LocalDateTime.parse(a) }.onFailure { err("at_invalid", "Datetime '$a' non valido (atteso ISO locale, es. 2026-07-15T08:00)") } }
            }
            is Trigger.Geofence -> {
                if (t.radiusM <= 0) err("radius_invalid", "Raggio geofence deve essere > 0")
                else if (t.radiusM < 100) warn("radius_small", "Raggio ${t.radiusM.toInt()} m sotto i 100 m consigliati: scatti ritardati/mancati possibili (spec E14)")
                if (!t.resolveCurrentLocation && t.lat == 0.0 && t.lng == 0.0)
                    err("geofence_coords", "Coordinate mancanti: specificare lat/lng o resolveCurrentLocation=true")
            }
            is Trigger.Notification -> {
                if (t.pkg.isBlank()) err("pkg_blank", "Package della notifica mancante")
                if (t.conversationId == null && t.sender != null)
                    warn("sender_spoofable", "Match per display name: spoofabile (spec E15), preferire conversationId")
            }
            else -> {}
        }

        checkConditions(d.conditions, ::err)

        for (a in d.actions) when (a) {
            is Action.RunShell -> warn("shell_review", "Comando shell: autonomo solo dopo approvazione del comando letterale (spec §10.2)")
            is Action.WhatsAppReply -> if (d.trigger !is Trigger.Notification)
                err("reply_no_notification", "WhatsAppReply richiede un trigger Notification (serve il RemoteInput)")
            is Action.InvokeLlm -> validateInvokeLlm(a, d.trigger, whitelistedIds, ::err, ::warn)
            else -> {}
        }

        if (d.actions.any { it.tier == ActionTier.GENERATIVE } && d.cooldownMs < 60_000)
            warn("cooldown_raised", "Cooldown sotto 60 s su regola generativa: l'engine imporrà 60 s (spec §5)")

        return issues
    }

    private fun checkConditions(c: Condition?, err: (String, String) -> Unit) {
        when (c) {
            null -> {}
            is Condition.And -> c.all.forEach { checkConditions(it, err) }
            is Condition.Or -> c.any.forEach { checkConditions(it, err) }
            is Condition.Not -> checkConditions(c.cond, err)
            is Condition.StateEquals -> if (c.key !in stateKeys)
                err("state_key_unknown", "Chiave di stato '${c.key}' fuori dal registry StateKeys")
            is Condition.TimeWindow -> runCatching { ZoneId.of(c.tz) }.onFailure { err("tz_invalid", "Timezone '${c.tz}' non valida") }
            else -> {}
        }
    }

    private fun validateInvokeLlm(
        a: Action.InvokeLlm, trigger: Trigger, whitelist: Set<String>,
        err: (String, String) -> Unit, warn: (String, String) -> Unit,
    ) {
        if (a.allowedTools.isEmpty()) err("no_tools", "InvokeLlm senza allowed_tools")
        for (tool in a.allowedTools) {
            // Case-insensitive: "SHELL.RUN"/"Automation.Create" non aggirano il gate.
            val norm = tool.lowercase()
            val forbidden = norm in FORBIDDEN_IN_INVOKE_LLM ||
                norm == FORBIDDEN_PREFIX_BARE ||
                norm.startsWith(FORBIDDEN_PREFIX)
            if (forbidden)
                err("tool_forbidden", "Tool '$tool' vietato al fire-time generativo (spec §7/§10.4)")
            else if (tool !in knownTools) err("tool_unknown", "Tool '$tool' non nel catalogo")
        }
        if (a.allowedTools.any { it.startsWith("screen.") || it == "state.read" } && "whatsapp_reply" in a.allowedTools)
            warn("read_plus_reply", "Tool di lettura + canale in uscita: possibile esfiltrazione di contesto verso il mittente (spec §10.4)")
        if (a.replyTargetSender) {
            val n = trigger as? Trigger.Notification
            when {
                n == null -> err("reply_target_no_notification", "reply_target richiede un trigger Notification")
                n.isGroup != false -> err("reply_target_group", "Reply generative solo su chat 1:1: serve isGroup=false (spec §10.3)")
                n.conversationId == null -> err("reply_needs_conversation_id", "Reply generative richiedono conversationId (spec E15)")
                whitelist.isNotEmpty() && n.conversationId !in whitelist ->
                    err("target_not_whitelisted", "Conversazione non in whitelist (spec §10.3)")
            }
        }
    }
}
