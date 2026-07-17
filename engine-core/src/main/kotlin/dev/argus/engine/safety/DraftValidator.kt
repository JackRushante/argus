package dev.argus.engine.safety

import dev.argus.engine.model.*
import dev.argus.engine.runtime.CronSchedule
import java.net.URI
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class Severity { ERROR, WARNING }
data class ValidationIssue(val severity: Severity, val code: String, val message: String)

/**
 * Gate di dominio per i draft LLM. ERROR blocca l'arm; WARNING è mostrato in approvazione.
 *
 * [whitelistedIds] è intenzionalmente obbligatoria: un caller che dimentica la policy destinatari
 * non deve ottenere implicitamente una whitelist aperta. Per draft senza reply passare [emptySet].
 */
class DraftValidator(
    private val knownTools: Set<String>,
    private val stateKeys: Set<String> = StateKeys.ALL.keys,
    private val deniedNotificationPackages: Set<String> = setOf(ARGUS_PACKAGE),
) {
    companion object {
        val FORBIDDEN_IN_INVOKE_LLM = setOf("shell.run", "app.install")
        const val FORBIDDEN_PREFIX = "automation."
        const val FORBIDDEN_PREFIX_BARE = "automation"

        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        val REPLY_TOOLS = setOf("whatsapp_reply")
        const val ARGUS_PACKAGE = "dev.argus"

        private const val MAX_NAME_LENGTH = 120
        private const val MAX_ACTIONS = 32
        private const val MAX_CONDITIONS = 64
        private const val MAX_CONDITION_DEPTH = 8
        private const val MAX_TEXT_LENGTH = 4_000
        private const val MAX_COMMAND_LENGTH = 8_192
        private const val MAX_TOOL_COUNT = 32
        private const val MAX_GEOFENCE_RADIUS_M = 100_000.0
        private const val MAX_LOITERING_MS = 86_400_000L
        private const val MAX_LLM_TIMEOUT_MS = 120_000L
        private const val MAX_TIMER_SECONDS = 86_400
        private const val MAX_VOLUME_LEVEL = 100
        private const val MAX_VIBRATE_MS = 10_000

        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }

    fun validate(d: AutomationDraft, whitelistedIds: Set<String>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun err(code: String, msg: String) { issues += ValidationIssue(Severity.ERROR, code, msg) }
        fun warn(code: String, msg: String) { issues += ValidationIssue(Severity.WARNING, code, msg) }

        if (d.name.isBlank() || d.name.length > MAX_NAME_LENGTH)
            err("name_invalid", "Nome regola obbligatorio e lungo al massimo $MAX_NAME_LENGTH caratteri")
        if (d.actions.isEmpty()) err("no_actions", "Il draft non contiene azioni")
        if (d.actions.size > MAX_ACTIONS) err("too_many_actions", "Massimo $MAX_ACTIONS azioni per regola")
        if (d.cooldownMs < 0) err("cooldown_invalid", "Cooldown non può essere negativo")

        validateTrigger(d.trigger, ::err, ::warn)
        if (d.trigger is Trigger.Sensor &&
            d.cooldownMs !in SensorTriggerPolicy.MIN_COOLDOWN_MS..SensorTriggerPolicy.MAX_COOLDOWN_MS
        ) {
            err(
                "sensor_cooldown_invalid",
                "I trigger sensore richiedono un cooldown tra " +
                    "${SensorTriggerPolicy.MIN_COOLDOWN_MS} e ${SensorTriggerPolicy.MAX_COOLDOWN_MS} ms",
            )
        }

        var conditionCount = 0
        fun countCondition() = ++conditionCount
        checkConditions(d.conditions, 1, ::countCondition, ::err)
        if (conditionCount > MAX_CONDITIONS)
            err("conditions_too_many", "Massimo $MAX_CONDITIONS condizioni per regola")

        for (a in d.actions) validateAction(a, d.trigger, whitelistedIds, ::err, ::warn)

        if (d.actions.any { it.tier == ActionTier.GENERATIVE } && d.cooldownMs < 60_000)
            warn("cooldown_raised", "Cooldown sotto 60 s su regola generativa: l'engine imporrà 60 s")

        return issues
    }

    private fun validateTrigger(
        trigger: Trigger,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        when (trigger) {
            // Immediate non ha campi propri (nessun cron/at/tz): è valido di per sé, fira all'arm.
            is Trigger.Immediate -> Unit
            is Trigger.Time -> {
                if ((trigger.cron == null) == (trigger.at == null))
                    err("time_spec", "Time richiede esattamente uno tra cron e at")
                runCatching { ZoneId.of(trigger.tz) }
                    .onFailure { err("tz_invalid", "Timezone '${trigger.tz}' non valida") }
                trigger.cron?.let { cron ->
                    runCatching { CronSchedule.parse(cron) }
                        .onFailure { err("cron_invalid", "Cron '$cron' non valido: ${it.message}") }
                }
                trigger.at?.let { at ->
                    runCatching { LocalDateTime.parse(at) }
                        .onFailure { err("at_invalid", "Datetime '$at' non valido (ISO locale atteso)") }
                }
            }

            is Trigger.Geofence -> {
                if (!trigger.radiusM.isFinite() || trigger.radiusM <= 0 || trigger.radiusM > MAX_GEOFENCE_RADIUS_M)
                    err("radius_invalid", "Raggio geofence deve essere finito e compreso tra 0 e ${MAX_GEOFENCE_RADIUS_M.toInt()} m")
                else if (trigger.radiusM < 100)
                    warn(
                        "radius_small",
                        "Raggio ${trigger.radiusM.toInt()} m sotto i 100 m consigliati; " +
                            "lo scatto in background può arrivare con minuti di ritardo",
                    )

                if (trigger.loiteringDelayMs < 0 || trigger.loiteringDelayMs > MAX_LOITERING_MS)
                    err("loitering_invalid", "Loitering delay fuori intervallo")
                if (trigger.transition == Transition.DWELL)
                    err(
                        "geofence_transition_unsupported",
                        "Il runtime geofence supporta ENTER/EXIT; DWELL non è disponibile",
                    )
                if (trigger.loiteringDelayMs != 0L)
                    err(
                        "geofence_loitering_unsupported",
                        "Il loitering delay richiede DWELL, non disponibile nel runtime corrente",
                    )

                if (!trigger.resolveCurrentLocation) {
                    val valid = trigger.lat.isFinite() && trigger.lng.isFinite() &&
                        trigger.lat in -90.0..90.0 && trigger.lng in -180.0..180.0 &&
                        !(trigger.lat == 0.0 && trigger.lng == 0.0)
                    if (!valid) err("geofence_coords", "Coordinate geofence mancanti o fuori intervallo")
                }
            }

            is Trigger.Notification -> {
                validatePackage(trigger.pkg, "pkg_blank", err)
                if (trigger.pkg in deniedNotificationPackages)
                    err("notification_own_package", "Il package Argus non può innescare proprie automazioni")
                if (trigger.conversationId != null && trigger.conversationId.isBlank())
                    err("conversation_id_invalid", "conversationId non può essere vuoto")
                if (trigger.conversationId == null && !trigger.sender.isNullOrBlank())
                    warn("sender_spoofable", "Match per display name: spoofabile, preferire conversationId")
                validateOptionalText(trigger.sender, "sender_invalid", err)
                validateOptionalText(trigger.titleMatch, "title_match_invalid", err)
                validateOptionalText(trigger.textMatch, "text_match_invalid", err)
            }

            is Trigger.PhoneState -> {
                validateOptionalText(trigger.number, "number_invalid", err)
                validateOptionalText(trigger.textMatch, "sms_text_match_invalid", err)
                if (trigger.textMatch != null && trigger.event != PhoneEvent.SMS_RECEIVED)
                    err("sms_text_match_invalid", "Il filtro sul testo vale solo per gli SMS in arrivo")
            }
            is Trigger.Connectivity -> validateOptionalText(trigger.match, "match_invalid", err)
            is Trigger.Sensor -> {
                if (!SensorTriggerPolicy.validEventCount(trigger.kind, trigger.minimumEventCount)) {
                    err(
                        "sensor_event_count_invalid",
                        "Conteggio eventi non valido per ${trigger.kind.wireName}; " +
                            "i sensori motion sono one-shot e gli step ammettono 1.." +
                            SensorTriggerPolicy.MAX_EVENT_COUNT,
                    )
                }
                if (trigger.samplingPeriodUs != null || trigger.maxReportLatencyUs != null) {
                    err(
                        "sensor_sampling_unsupported",
                        "Sampling continuo/high-rate non disponibile: usare soltanto i kind " +
                            "event-driven approvati",
                    )
                }
            }
        }
    }

    private fun checkConditions(
        condition: Condition?,
        depth: Int,
        count: () -> Int,
        err: (String, String) -> Unit,
    ) {
        if (condition == null) return
        count()
        if (depth > MAX_CONDITION_DEPTH) {
            err("condition_too_deep", "Albero condizioni oltre profondità $MAX_CONDITION_DEPTH")
            return
        }
        when (condition) {
            is Condition.And -> {
                if (condition.all.isEmpty()) err("condition_empty", "AND senza condizioni")
                condition.all.forEach { checkConditions(it, depth + 1, count, err) }
            }
            is Condition.Or -> {
                if (condition.any.isEmpty()) err("condition_empty", "OR senza condizioni")
                condition.any.forEach { checkConditions(it, depth + 1, count, err) }
            }
            is Condition.Not -> checkConditions(condition.cond, depth + 1, count, err)
            is Condition.StateEquals -> validateStateCondition(condition, err)
            is Condition.StateCompare -> validateStateCompare(condition, err)
            is Condition.TimeWindow -> {
                runCatching {
                    ZoneId.of(condition.tz)
                    LocalTime.parse(condition.startLocal)
                    LocalTime.parse(condition.endLocal)
                }.onFailure {
                    err("time_window_invalid", "Finestra temporale non valida: usare HH:mm e timezone IANA")
                }
            }
            is Condition.AppInForeground -> validatePackage(condition.pkg, "package_invalid", err)
            is Condition.LocationIn -> {
                if (!condition.lat.isFinite() || !condition.lng.isFinite() ||
                    condition.lat !in -90.0..90.0 || condition.lng !in -180.0..180.0)
                    err("location_invalid", "Coordinate condizione fuori intervallo")
                if (!condition.radiusM.isFinite() || condition.radiusM <= 0 || condition.radiusM > MAX_GEOFENCE_RADIUS_M)
                    err("radius_invalid", "Raggio condizione non valido")
            }
        }
    }

    private fun validateStateCondition(c: Condition.StateEquals, err: (String, String) -> Unit) {
        if (c.key !in stateKeys) {
            err("state_key_unknown", "Chiave di stato '${c.key}' fuori dal registry StateKeys")
            return
        }
        val valid = when (c.key) {
            StateKeys.BATTERY -> c.op in setOf(CmpOp.EQ, CmpOp.NEQ, CmpOp.GT, CmpOp.LT) &&
                c.value.toIntOrNull()?.let { it in 0..100 } == true
            else -> c.op in setOf(CmpOp.EQ, CmpOp.NEQ) &&
                c.value in StateKeys.ALL.getValue(c.key).split('|')
        }
        if (!valid) err("state_value_invalid", "Valore/op '${c.op}:${c.value}' non valido per '${c.key}'")
    }

    private fun validateStateCompare(c: Condition.StateCompare, err: (String, String) -> Unit) {
        if (c.policyVersion != StateQueryPolicy.VERSION) {
            err("state_query_policy_incompatible", "Versione policy reader non compatibile")
            return
        }
        if (!StateQueryPolicy.validQuery(c.query, stateKeys)) {
            err("state_query_invalid", "Reader di stato o parametri non validi")
            return
        }
        if (!StateQueryPolicy.validComparison(
                c.query,
                c.valueType,
                c.op,
                c.expected,
                stateKeys,
            )
        ) {
            err("state_compare_invalid", "Tipo, operatore o valore atteso non validi per il reader")
        }
    }

    private fun validateAction(
        action: Action,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        when (action) {
            is Action.SetWifi, is Action.SetBluetooth, is Action.SetDnd -> Unit
            is Action.SetRinger -> if (action.mode !in setOf("normal", "vibrate", "silent"))
                err("ringer_mode_invalid", "Modalità suoneria non valida")
            is Action.LaunchApp -> validatePackage(action.pkg, "package_invalid", err)
            is Action.OpenUrl -> if (!validHttpUrl(action.url))
                err("url_invalid", "URL non valido o schema non consentito")
            is Action.ShowNotification -> {
                validateRequiredText(action.title, 120, "title_invalid", err)
                // Il corpo può restare vuoto (notifica di solo titolo, come ammette il bridge):
                // qui conta solo il bound.
                if (action.text.length > MAX_TEXT_LENGTH)
                    err("text_invalid", "Testo notifica oltre $MAX_TEXT_LENGTH caratteri")
            }
            is Action.Tap -> if (action.x !in 0..10_000 || action.y !in 0..10_000)
                err("coordinates_invalid", "Coordinate tap fuori intervallo")
            is Action.InputText -> validateRequiredText(action.text, MAX_TEXT_LENGTH, "text_invalid", err)
            is Action.WhatsAppReply -> {
                validateRequiredText(action.text, MAX_TEXT_LENGTH, "text_invalid", err)
                validateReplyTarget(trigger, whitelist, err)
            }
            is Action.RunShell -> {
                validateRequiredText(action.cmd, MAX_COMMAND_LENGTH, "shell_invalid", err)
                if ('\u0000' in action.cmd)
                    err("shell_invalid", "Il comando shell contiene un carattere NUL non eseguibile")
                if (!StaticShellSafety.allows(trigger, whitelist))
                    err(
                        "shell_external_trigger",
                        "La shell autonoma è ammessa con trigger Time, Geofence o Connectivity, " +
                            "oppure da una chat WhatsApp 1:1 con contatto in whitelist. SMS e " +
                            "chiamate restano esclusi: mittente e caller ID sono falsificabili",
                    )
                warn(
                    "shell_review",
                    "Comando autonomo approvato letteralmente; limite operativo 30 s e, per " +
                        "trigger broadcast, preferire comandi brevi",
                )
                // Il rischio non è più solo "cosa esegue" ma "chi può farlo partire": con un
                // trigger notification il comando parte quando decide il contatto, non tu.
                if (trigger is Trigger.Notification)
                    warn(
                        "shell_contact_trigger",
                        "Questo comando potrà essere avviato dal contatto in whitelist ogni " +
                            "volta che il messaggio corrisponde: il comando resta quello " +
                            "approvato, ma il momento lo sceglie lui",
                    )
            }
            is Action.CopyToClipboard -> {
                val textual = trigger is Trigger.Notification ||
                    (trigger is Trigger.PhoneState && trigger.event == PhoneEvent.SMS_RECEIVED)
                if (!textual)
                    err("clipboard_source_missing", "Copia negli appunti richiede un trigger con testo (SMS o notifica)")
                action.extractionRegex?.let { pattern ->
                    if (!SafeExtractionRegex.isValid(pattern))
                        err(
                            "extraction_regex_invalid",
                            "Regex non sicura/compatibile RE2 o oltre " +
                                "${SafeExtractionRegex.MAX_PATTERN_CHARS} caratteri",
                        )
                }
            }
            is Action.SetAlarm -> {
                if (action.hour !in 0..23 || action.minute !in 0..59)
                    err("alarm_time_invalid", "Ora sveglia fuori intervallo: hour 0..23, minute 0..59")
                validateOptionalText(action.label, "alarm_label_invalid", err)
            }
            is Action.SetTimer -> {
                if (action.seconds !in 1..MAX_TIMER_SECONDS)
                    err("timer_seconds_invalid", "Durata timer fuori intervallo: 1..$MAX_TIMER_SECONDS secondi")
                validateOptionalText(action.label, "timer_label_invalid", err)
            }
            is Action.SetVolume -> {
                // Stream è un enum chiuso (compile-enforced). `level` è una PERCENTUALE 0..100 che
                // l'executor mappa sul massimo reale dello stream a runtime; qui basta il range.
                if (action.level !in 0..MAX_VOLUME_LEVEL)
                    err("volume_level_invalid", "Livello volume fuori intervallo: 0..$MAX_VOLUME_LEVEL")
            }
            is Action.SetFlashlight -> Unit // solo booleano, nulla da validare
            is Action.OpenSettingsScreen -> {
                // Enum chiuso: nessuna action-string arbitraria. `pkg` è obbligatorio e valido solo
                // per APP_DETAILS; per le altre schermate va lasciato assente.
                if (action.screen == SettingsScreen.APP_DETAILS) {
                    if (action.pkg == null) err("settings_pkg_missing", "APP_DETAILS richiede un package")
                    else validatePackage(action.pkg, "settings_pkg_invalid", err)
                } else if (action.pkg != null) {
                    err("settings_pkg_unexpected", "Il package è ammesso solo per la schermata APP_DETAILS")
                }
            }
            is Action.Vibrate -> {
                if (action.durationMs !in 1..MAX_VIBRATE_MS)
                    err("vibrate_duration_invalid", "Durata vibrazione fuori intervallo: 1..$MAX_VIBRATE_MS ms")
            }
            is Action.WriteSetting -> {
                // PARAMETRICA (D0: nessuna allowlist di chiavi). Solo validazione di forma via
                // WriteSettingPolicy: key stile QUERY_NAME, value bounded, control char rifiutati.
                if (!WriteSettingPolicy.validKey(action.key))
                    err("write_setting_key_invalid", "Chiave impostazione non valida (forma/limite)")
                if (!WriteSettingPolicy.validValue(action.value))
                    err(
                        "write_setting_value_invalid",
                        "Valore impostazione vuoto, troppo lungo o con caratteri di controllo",
                    )
                // D2: la scrittura crea autorità e il valore è letterale/approvato, mai dal trigger.
                warn(
                    "write_setting_review",
                    "Scrittura impostazione approvata letteralmente: " +
                        "${action.namespace.name.lowercase()} ${action.key} = ${action.value}",
                )
            }
            is Action.InvokeLlm -> validateInvokeLlm(action, trigger, whitelist, err, warn)
            is Action.InvokeLlmV2 -> validateInvokeLlmV2(action, trigger, whitelist, err, warn)
        }
    }

    private fun validateInvokeLlmV2(
        action: Action.InvokeLlmV2,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        validateRequiredText(action.goal, MAX_TEXT_LENGTH, "goal_invalid", err)
        if (action.stateContext.isEmpty()) {
            err("state_context_empty", "InvokeLlm v2 richiede almeno un reader di stato esplicito")
        }
        if (action.stateContext.size > StateContextClassification.MAX_QUERIES) {
            err(
                "state_context_too_large",
                "InvokeLlm v2 ammette al massimo ${StateContextClassification.MAX_QUERIES} reader",
            )
        }
        if (action.stateContext.map { it.query.canonicalId }.distinct().size != action.stateContext.size) {
            err("state_context_duplicated", "Reader di stato duplicati in InvokeLlm v2")
        }
        action.stateContext.forEach { context ->
            if (context.policyVersion != StateQueryPolicy.VERSION) {
                err("state_context_policy_incompatible", "Policy reader non compatibile")
            }
            if (!StateQueryPolicy.validQuery(context.query, stateKeys)) {
                err("state_context_query_invalid", "Reader di stato o parametri non validi")
            }
            if (!StateContextClassification.validValueType(context.query, context.valueType)) {
                err("state_context_type_invalid", "Tipo dichiarato non valido per il reader")
            }
            if (context.integrity != IntegrityLabel.CLEAN) {
                err(
                    "state_context_integrity_invalid",
                    "Un reader locale approvato deve avere integrità CLEAN",
                )
            }
            val minimum = StateContextClassification.minimumConfidentiality(context.query)
            if (!StateContextClassification.covers(context.confidentiality, minimum)) {
                err(
                    "state_context_underclassified",
                    "Reader ${context.query.family.wireName} classificato sotto il minimo ${minimum.name}",
                )
            }
        }

        validateGenerativeReplyContract(
            allowedTools = action.allowedTools,
            replyTargetSender = action.replyTargetSender,
            timeoutMs = action.timeoutMs,
            trigger = trigger,
            whitelist = whitelist,
            err = err,
        )
        warn(
            "state_context_disclosure",
            "I reader elencati saranno inviati al Brain configurato con classificazione esplicita",
        )
        if (action.stateContext.any { it.confidentiality == ConfidentialityLabel.SECRET }) {
            warn(
                "secret_state_context",
                "Questa regola condivide stato classificato SECRET con Hermes/provider cloud",
            )
        }
    }

    private fun validateGenerativeReplyContract(
        allowedTools: List<String>,
        replyTargetSender: Boolean,
        timeoutMs: Long,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
    ) {
        if (!GenerativeContract.isAllowedToolset(allowedTools)) {
            err(
                "allowed_tools_unsupported",
                "allowed_tools deve contenere whatsapp_reply e al più web.search",
            )
        }
        if (allowedTools.isEmpty()) err("no_tools", "Azione generativa senza allowed_tools")
        if (allowedTools.size > MAX_TOOL_COUNT) err("too_many_tools", "Troppi tool nell'azione generativa")
        if (timeoutMs !in 1_000..MAX_LLM_TIMEOUT_MS) {
            err("timeout_invalid", "Timeout InvokeLlm fuori intervallo")
        }
        for (tool in allowedTools) {
            val norm = tool.lowercase()
            val forbidden = norm in FORBIDDEN_IN_INVOKE_LLM ||
                norm == FORBIDDEN_PREFIX_BARE || norm.startsWith(FORBIDDEN_PREFIX)
            when {
                tool.isBlank() || tool.length > 120 -> err("tool_invalid", "Nome tool non valido")
                forbidden -> err("tool_forbidden", "Tool '$tool' vietato al fire-time generativo")
                tool !in knownTools -> err("tool_unknown", "Tool '$tool' non nel catalogo")
            }
        }
        val hasReplyTool = allowedTools.any { it.lowercase() in REPLY_TOOLS }
        if (hasReplyTool && !replyTargetSender) {
            err("reply_target_unbound", "Un tool di reply deve essere vincolato al mittente del trigger")
        }
        if (replyTargetSender) {
            if (!hasReplyTool) err("reply_target_without_tool", "replyTargetSender senza tool di reply")
            validateReplyTarget(trigger, whitelist, err)
        }
    }

    private fun validateInvokeLlm(
        action: Action.InvokeLlm,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        // Controlli comuni ai due sink (goal + forma delle context sources).
        validateRequiredText(action.goal, MAX_TEXT_LENGTH, "goal_invalid", err)
        if (action.contextSources.size > MAX_TOOL_COUNT || action.contextSources.any { it.isBlank() || it.length > 120 })
            err("context_sources_invalid", "Context sources non valide o troppe")

        when (action.deliver) {
            GenerativeDeliverMode.WHATSAPP_REPLY ->
                validateInvokeLlmReplyDeliver(action, trigger, whitelist, err, warn)
            GenerativeDeliverMode.LOCAL_NOTIFICATION ->
                validateInvokeLlmNotificationDeliver(action, err)
        }

        // Difesa in profondità per-tool + timeout: valgono per ENTRAMBI i sink (web.search è in
        // knownTools; shell.run/automation.* restano vietati e i tool ignoti respinti).
        if (action.allowedTools.size > MAX_TOOL_COUNT) err("too_many_tools", "Troppi tool in InvokeLlm")
        if (action.timeoutMs !in 1_000..MAX_LLM_TIMEOUT_MS)
            err("timeout_invalid", "Timeout InvokeLlm fuori intervallo")
        for (tool in action.allowedTools) {
            val norm = tool.lowercase()
            val forbidden = norm in FORBIDDEN_IN_INVOKE_LLM ||
                norm == FORBIDDEN_PREFIX_BARE || norm.startsWith(FORBIDDEN_PREFIX)
            when {
                tool.isBlank() || tool.length > 120 -> err("tool_invalid", "Nome tool non valido")
                forbidden -> err("tool_forbidden", "Tool '$tool' vietato al fire-time generativo")
                tool !in knownTools -> err("tool_unknown", "Tool '$tool' non nel catalogo")
            }
        }
    }

    /**
     * Sink REPLY (profilo P1, invariato): l'unico profilo che la lane generativa esegue davvero
     * al fire-time. Un draft più permissivo passerebbe la review e fallirebbe con
     * action_contract_invalid.
     */
    private fun validateInvokeLlmReplyDeliver(
        action: Action.InvokeLlm,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        if (action.contextSources.isEmpty()) {
            err("context_sources_empty", "InvokeLlm richiede almeno la context source 'notification'")
        } else {
            if (GenerativeContract.CONTEXT_NOTIFICATION !in action.contextSources)
                err("context_notification_required", "Il contesto InvokeLlm deve includere 'notification'")
            if (action.contextSources.size != action.contextSources.distinct().size)
                err("context_sources_duplicated", "Context sources duplicate in InvokeLlm")
            action.contextSources.filterNot { it in GenerativeContract.CONTEXT_SOURCES }.forEach { source ->
                err("context_source_unsupported", "Context source '$source' non supportata in P1")
            }
        }
        if (!GenerativeContract.isAllowedToolset(action.allowedTools))
            err(
                "allowed_tools_unsupported",
                "allowed_tools deve contenere whatsapp_reply e al più web.search",
            )
        if (action.allowedTools.isEmpty()) err("no_tools", "InvokeLlm senza allowed_tools")

        val hasReplyTool = action.allowedTools.any { it.lowercase() in REPLY_TOOLS }
        if (hasReplyTool && !action.replyTargetSender)
            err("reply_target_unbound", "Un tool di reply deve essere vincolato al mittente del trigger")
        if (action.replyTargetSender) {
            if (!hasReplyTool) err("reply_target_without_tool", "replyTargetSender senza tool di reply")
            validateReplyTarget(trigger, whitelist, err)
        }

        if (action.allowedTools.any { it.startsWith("screen.") || it == "state.read" } && hasReplyTool)
            warn("read_plus_reply", "Tool di lettura + canale in uscita: possibile esfiltrazione")
    }

    /**
     * Sink NOTIFICA (#59): il testo generato diventa una notifica locale, da un trigger qualsiasi.
     * Il titolo è LETTERALE (dal fingerprint approvato, mai dal contenuto del trigger — D2). Nessun
     * vincolo di reply-target: la notifica È il sink, non un canale verso un contatto.
     */
    private fun validateInvokeLlmNotificationDeliver(
        action: Action.InvokeLlm,
        err: (String, String) -> Unit,
    ) {
        val title = action.notificationTitle
        if (title.isNullOrBlank()) {
            err("notification_title_invalid", "Il sink notifica richiede un titolo non vuoto")
        } else if (title.length > MAX_NAME_LENGTH || title.any(Char::isISOControl)) {
            err("notification_title_invalid", "Titolo notifica oltre $MAX_NAME_LENGTH caratteri o con caratteri di controllo")
        }
        if (!GenerativeContract.isNotificationToolset(action.allowedTools))
            err(
                "allowed_tools_unsupported",
                "Il sink notifica ammette al più web.search, mai whatsapp_reply",
            )
        if (action.replyTargetSender)
            err("reply_target_forbidden", "Il sink notifica non può vincolare un destinatario di reply")
        // contextSources: vuota OPPURE subset di {state} — mai 'notification' (il testo nasce dal goal).
        action.contextSources.filterNot { it == GenerativeContract.CONTEXT_STATE }.forEach { source ->
            err("context_source_unsupported", "Context source '$source' non supportata dal sink notifica")
        }
        if (action.contextSources.size != action.contextSources.distinct().size)
            err("context_sources_duplicated", "Context sources duplicate in InvokeLlm")
    }

    private fun validateReplyTarget(
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
    ) {
        val notification = trigger as? Trigger.Notification
        if (notification == null) {
            err("reply_target_no_notification", "Reply richiede un trigger Notification")
            return
        }
        if (notification.pkg !in WHATSAPP_PACKAGES)
            err("reply_wrong_package", "WhatsAppReply richiede un package WhatsApp riconosciuto")
        if (notification.isGroup != false)
            err("reply_target_group", "Reply automatiche solo su chat 1:1 verificata")
        val conversationId = notification.conversationId
        if (conversationId.isNullOrBlank())
            err("reply_needs_conversation_id", "Reply automatiche richiedono conversationId")
        else if (conversationId !in whitelist)
            err("target_not_whitelisted", "Conversazione non in whitelist")
    }

    private fun validatePackage(value: String, blankCode: String, err: (String, String) -> Unit) {
        if (value.isBlank()) err(blankCode, "Package mancante")
        else if (!PACKAGE_NAME.matches(value)) err("package_invalid", "Package '$value' non valido")
    }

    private fun validateOptionalText(value: String?, code: String, err: (String, String) -> Unit) {
        if (value != null && (value.isBlank() || value.length > MAX_TEXT_LENGTH))
            err(code, "Campo testuale vuoto o troppo lungo")
    }

    private fun validateRequiredText(value: String, max: Int, code: String, err: (String, String) -> Unit) {
        if (value.isBlank() || value.length > max) err(code, "Campo obbligatorio, massimo $max caratteri")
    }

    private fun validHttpUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
