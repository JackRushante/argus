package dev.argus.ui.presentation

import java.util.Locale
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.Condition
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.RuleRender

/**
 * DETERMINISTIC rendering of an automation into [RuleRender] — direttiva sicurezza §5.1.
 *
 * The approval screen renders the rule from its TYPES, never from the LLM prose (`rationale`).
 * This mapper is therefore a security control: it must describe *faithfully and unambiguously*
 * what the rule does. Every [Trigger]/[Condition]/[Action] variant is rendered via exhaustive
 * `when` (no `else`), nothing is dropped or softened — in particular shell commands are shown
 * integrally and any generative/cloud flag is surfaced.
 *
 * Pure JVM presentation logic: no `android.*`, no `Context`, no string resources. Italian copy
 * is inline; the privacy note is verbatim from design §7.3.
 */
object RuleRenderMapper {

    /** Verbatim da design §7.3 — mai parafrasare (invariante di sicurezza handoff §5). */
    private const val PRIVACY_NOTE =
        "Il testo delle notifiche verrà inviato a Hermes e ai provider cloud per generare la risposta."
    private const val STATE_PRIVACY_NOTE =
        "Il testo delle notifiche e i reader di stato elencati verranno inviati a Hermes e ai " +
            "provider cloud per generare la risposta."

    /**
     * [conversationLabels] mappa conversationId → display name proveniente dallo store whitelist
     * FIDATO (mai dalla prosa LLM): quando il trigger punta a un id whitelistato la review mostra
     * il nome leggibile al posto dell'hash. Lookup deterministico, opzionale per i caller che non
     * hanno accesso allo store (fallback: hash integrale, onesto).
     */
    fun map(a: Automation, conversationLabels: Map<String, String> = emptyMap()): RuleRender =
        render(a.trigger, a.actions, a.conditions, conversationLabels)

    fun mapDraft(d: AutomationDraft, conversationLabels: Map<String, String> = emptyMap()): RuleRender =
        render(d.trigger, d.actions, d.conditions, conversationLabels)

    private fun render(
        trigger: Trigger,
        actions: List<Action>,
        conditions: Condition?,
        conversationLabels: Map<String, String>,
    ): RuleRender {
        val isGenerative = actions.any { it.tier == ActionTier.GENERATIVE }
        return RuleRender(
            triggerLine = triggerLine(trigger, conversationLabels),
            triggerIconKey = triggerIconKey(trigger),
            conditionLines = conditions?.let { flattenConditions(it, 0) } ?: emptyList(),
            actions = actions.map { actionRow(it) },
            isGenerative = isGenerative,
            privacyNote = when {
                actions.any { it is Action.InvokeLlmV2 } -> STATE_PRIVACY_NOTE
                isGenerative -> PRIVACY_NOTE
                else -> null
            },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Trigger
    // ---------------------------------------------------------------------------------------------

    private fun triggerIconKey(t: Trigger): String = when (t) {
        is Trigger.Notification -> "notification"
        is Trigger.Time -> "time"
        is Trigger.Immediate -> "immediate"
        is Trigger.Geofence -> "geofence"
        is Trigger.PhoneState -> "phone"
        is Trigger.Connectivity -> "connectivity"
        is Trigger.Sensor -> "sensor"
    }

    private fun triggerLine(t: Trigger, conversationLabels: Map<String, String>): String = when (t) {
        is Trigger.Notification -> notificationLine(t, conversationLabels)
        is Trigger.Time -> timeLine(t)
        is Trigger.Immediate -> "Una volta, all'attivazione"
        is Trigger.Geofence -> geofenceLine(t)
        is Trigger.PhoneState -> phoneStateLine(t)
        is Trigger.Connectivity -> connectivityLine(t)
        is Trigger.Sensor -> sensorLine(t)
    }

    private fun notificationLine(t: Trigger.Notification, conversationLabels: Map<String, String>): String {
        val chat = when (t.isGroup) {
            true -> "gruppo"
            false -> "chat 1:1"
            null -> "chat 1:1 o gruppo"
        }
        // Il TriggerMatcher dà al conversationId precedenza ESCLUSIVA sul sender: quando l'id è
        // whitelistato il nome fidato descrive il criterio di match reale meglio del sender LLM
        // o dell'hash. Senza label si mostra l'hash INTEGRALE: onesto, mai abbreviato (§5).
        val trusted = t.conversationId?.let(conversationLabels::get)
        val base = if (trusted != null) {
            "Quando: notifica ${appLabel(t.pkg)} da $trusted (identità verificata, $chat)"
        } else {
            val who = t.sender ?: t.conversationId ?: "chiunque"
            "Quando: notifica ${appLabel(t.pkg)} da $who ($chat)"
        }
        // Non nascondere i filtri che restringono quando la regola scatta (§5: non "softare" nulla).
        val title = t.titleMatch?.let { " · titolo \"$it\"" } ?: ""
        val text = t.textMatch?.let { " · testo \"$it\"" } ?: ""
        return base + title + text
    }

    private fun timeLine(t: Trigger.Time): String {
        val cron = t.cron
        val at = t.at
        return when {
            cron != null -> humanizeCron(cron, t.tz)
            at != null -> "Una volta il ${at.replace("T", " alle ")} (${t.tz})"
            else -> "Orario non specificato (${t.tz})"
        }
    }

    private fun geofenceLine(t: Trigger.Geofence): String {
        val r = fmtNum(t.radiusM)
        return if (t.resolveCurrentLocation) {
            when (t.transition) {
                Transition.ENTER -> "Quando entri nella posizione attuale (±$r m)"
                Transition.EXIT -> "Quando esci dalla posizione attuale (±$r m)"
                Transition.DWELL -> "Quando ti trattieni nella posizione attuale (±$r m)"
            }
        } else {
            val loc = "${fmtCoord(t.lat)},${fmtCoord(t.lng)}"
            when (t.transition) {
                Transition.ENTER -> "Quando entri in $loc (±$r m)"
                Transition.EXIT -> "Quando esci da $loc (±$r m)"
                Transition.DWELL -> "Quando ti trattieni presso $loc (±$r m)"
            }
        }
    }

    private fun phoneStateLine(t: Trigger.PhoneState): String {
        val event = when (t.event) {
            PhoneEvent.INCOMING_CALL -> "chiamata in arrivo"
            PhoneEvent.CALL_ENDED -> "chiamata terminata"
            PhoneEvent.SMS_RECEIVED -> "SMS ricevuto"
        }
        val from = t.number?.let { "da $it" } ?: "da chiunque"
        // §5: i filtri che restringono il match restano visibili integrali in review.
        val text = t.textMatch?.let { " · testo \"$it\"" } ?: ""
        return "Quando: $event $from$text"
    }

    private fun connectivityLine(t: Trigger.Connectivity): String {
        val medium = when (t.medium) {
            ConnMedium.WIFI -> "Wi-Fi"
            ConnMedium.BT -> "Bluetooth"
            ConnMedium.POWER -> "alimentazione"
        }
        val state = when (t.medium) {
            ConnMedium.POWER -> if (t.state == ConnState.CONNECTED) "collegata" else "scollegata"
            else -> if (t.state == ConnState.CONNECTED) "connesso" else "disconnesso"
        }
        val match = t.match?.let { " ($it)" } ?: ""
        return "Quando: $medium $state$match"
    }

    private fun sensorLine(t: Trigger.Sensor): String {
        val kind = when (t.kind) {
            SensorKind.SIGNIFICANT_MOTION -> "rilevato un movimento significativo"
            SensorKind.STATIONARY_DETECT -> "il dispositivo diventa fermo"
            SensorKind.MOTION_DETECT -> "il dispositivo torna in movimento"
            SensorKind.STEP_DETECTOR -> "rilevati ${t.minimumEventCount} passi"
            SensorKind.STEP_COUNTER -> "il contatore aumenta di ${t.minimumEventCount} passi"
        }
        return "Quando: $kind"
    }

    // ---------------------------------------------------------------------------------------------
    // Condition tree — appiattito in righe indentate (§6)
    // ---------------------------------------------------------------------------------------------

    private fun flattenConditions(c: Condition, depth: Int): List<String> {
        val pad = "  ".repeat(depth)
        return when (c) {
            is Condition.TimeWindow ->
                listOf("${pad}Solo tra le ${c.startLocal} e le ${c.endLocal} (${c.tz})")
            is Condition.StateEquals ->
                listOf("${pad}Solo se ${c.key} ${opLabel(c.op)} ${c.value}")
            is Condition.StateCompare ->
                listOf(
                    "${pad}Solo se ${queryLabel(c.query)} [${valueTypeLabel(c.valueType)}] " +
                        "${opLabel(c.op)} ${c.expected}",
                )
            is Condition.AppInForeground ->
                listOf("${pad}Solo se ${appLabel(c.pkg)} è in primo piano")
            is Condition.LocationIn ->
                listOf("${pad}Solo se ti trovi entro ${fmtNum(c.radiusM)} m da ${c.lat},${c.lng}")
            is Condition.And ->
                listOf("${pad}Tutte le seguenti:") + c.all.flatMap { flattenConditions(it, depth + 1) }
            is Condition.Or ->
                listOf("${pad}Almeno una delle seguenti:") + c.any.flatMap { flattenConditions(it, depth + 1) }
            is Condition.Not ->
                listOf("${pad}Non deve valere:") + flattenConditions(c.cond, depth + 1)
        }
    }

    private fun opLabel(op: CmpOp): String = when (op) {
        CmpOp.EQ -> "="
        CmpOp.NEQ -> "≠"
        CmpOp.GT -> ">"
        CmpOp.LT -> "<"
        CmpOp.CONTAINS -> "contiene"
    }

    // ---------------------------------------------------------------------------------------------
    // Action -> ActionRow
    // ---------------------------------------------------------------------------------------------

    private fun actionRow(a: Action): ActionRow = when (a) {
        is Action.SetWifi -> row(
            iconKey = "wifi_off",
            label = if (a.on) "Attiva Wi-Fi" else "Disattiva Wi-Fi",
        )
        is Action.SetBluetooth -> row(
            iconKey = "bluetooth",
            label = if (a.on) "Attiva Bluetooth" else "Disattiva Bluetooth",
        )
        is Action.SetDnd -> row(
            iconKey = "dnd",
            label = when (a.mode) {
                DndMode.OFF -> "Disattiva Non disturbare"
                DndMode.PRIORITY -> "Attiva Non disturbare (priorità)"
                DndMode.TOTAL -> "Attiva Non disturbare (totale)"
            },
        )
        is Action.SetRinger -> row(
            iconKey = "ringer",
            label = "Imposta suoneria (${a.mode})",
        )
        is Action.LaunchApp -> row(
            iconKey = "launch_app",
            label = "Apri app",
            detail = appLabel(a.pkg),
        )
        is Action.OpenUrl -> row(
            iconKey = "open_url",
            label = "Apri URL",
            detail = a.url,
        )
        is Action.ShowNotification -> row(
            iconKey = "notify",
            label = "Mostra notifica",
            detail = a.text,
        )
        is Action.Tap -> row(
            iconKey = "tap",
            label = "Tocca schermo",
            detail = "(${a.x}, ${a.y})",
            requiresLiveConfirm = true,
        )
        is Action.InputText -> row(
            iconKey = "input_text",
            label = "Digita testo",
            detail = a.text,
            requiresLiveConfirm = true,
        )
        is Action.WhatsAppReply -> row(
            iconKey = "whatsapp_reply",
            label = "Rispondi su WhatsApp",
            detail = a.text,
            requiresLiveConfirm = true,
        )
        is Action.RunShell -> row(
            iconKey = "shell",
            label = "Esegui comando shell",
            isShell = true,
            shellCommand = a.cmd, // integrale, mai troncato (invariante §5.4)
        )
        is Action.CopyToClipboard -> row(
            iconKey = "clipboard",
            label = "Copia negli appunti",
            // §5: la regex è il criterio reale di estrazione, resta visibile integrale.
            detail = a.extractionRegex?.let { "estrazione: $it" } ?: "il testo integrale del messaggio",
        )
        is Action.SetAlarm -> row(
            iconKey = "alarm",
            label = "Imposta sveglia",
            detail = "%02d:%02d".format(a.hour, a.minute) +
                (a.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        )
        is Action.SetTimer -> row(
            iconKey = "timer",
            label = "Avvia timer",
            detail = "${a.seconds}s" +
                (a.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        )
        is Action.SetVolume -> row(
            iconKey = "volume",
            label = "Imposta volume",
            detail = "${a.stream.name.lowercase()} · livello ${a.level}",
        )
        is Action.SetFlashlight -> row(
            iconKey = "flashlight",
            label = if (a.on) "Accendi torcia" else "Spegni torcia",
            detail = if (a.on) "on" else "off",
        )
        is Action.OpenSettingsScreen -> row(
            iconKey = "settings",
            label = "Apri Impostazioni",
            detail = a.screen.name.lowercase() +
                (a.pkg?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        )
        is Action.Vibrate -> row(
            iconKey = "vibrate",
            label = "Vibra",
            detail = "${a.durationMs} ms",
        )
        is Action.WriteSetting -> row(
            iconKey = "settings",
            // D2: la review mostra namespace/key/value LETTERALI e integrali (l'utente approva
            // esattamente questa terna, mai interpolata dal trigger).
            label = "Scrivi impostazione",
            detail = "${a.namespace.name.lowercase()} · ${a.key} = ${a.value}",
        )
        is Action.InvokeLlm -> row(
            iconKey = "generative",
            // §5: i due sink (reply vs notifica locale) restano distinti e non "softati" in review.
            label = when (a.deliver) {
                GenerativeDeliverMode.WHATSAPP_REPLY -> "Genera e rispondi con l'AI"
                GenerativeDeliverMode.LOCAL_NOTIFICATION -> "Genera e notifica"
            },
            detail = when (a.deliver) {
                GenerativeDeliverMode.WHATSAPP_REPLY ->
                    "Obiettivo: ${a.goal} · tool: ${a.allowedTools.joinToString(", ")}"
                // Titolo LETTERALE (D2): l'utente approva esattamente questo testo, mai dal trigger.
                GenerativeDeliverMode.LOCAL_NOTIFICATION ->
                    "Titolo notifica: ${a.notificationTitle.orEmpty()} · Obiettivo: ${a.goal} · " +
                        "tool: ${a.allowedTools.joinToString(", ")}"
            },
            isGenerative = true,
        )
        is Action.InvokeLlmV2 -> row(
            iconKey = "generative",
            label = "Rispondi con l'AI",
            detail = buildString {
                append("Obiettivo: ${a.goal} · stato condiviso: ")
                append(
                    a.stateContext.joinToString("; ") { context ->
                        "${queryLabel(context.query)} [${valueTypeLabel(context.valueType)}, " +
                            "${context.integrity.name}, ${context.confidentiality.name}]"
                    },
                )
                append(" · tool: ${a.allowedTools.joinToString(", ")}")
            },
            isGenerative = true,
        )
    }

    private fun row(
        iconKey: String,
        label: String,
        detail: String? = null,
        isShell: Boolean = false,
        shellCommand: String? = null,
        isGenerative: Boolean = false,
        requiresLiveConfirm: Boolean = false,
    ): ActionRow = ActionRow(
        iconKey = iconKey,
        label = label,
        detail = detail,
        isShell = isShell,
        shellCommand = shellCommand,
        isGenerative = isGenerative,
        requiresLiveConfirm = requiresLiveConfirm,
    )

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Etichetta minimale del pacchetto (nessuna risorsa Android nel test JVM). */
    private fun appLabel(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        else -> pkg
    }

    /** Umanizza i cron comuni; fallback esplicito che mostra l'espressione integrale. */
    private fun humanizeCron(expr: String, tz: String): String {
        val parts = expr.trim().split(Regex("\\s+"))
        if (parts.size == 5) {
            val (min, hour, dom, mon, dow) = parts
            val m = min.toIntOrNull()
            val h = hour.toIntOrNull()
            if (m != null && h != null && m in 0..59 && h in 0..23) {
                val time = "%02d:%02d".format(h, m)
                if (dom == "*" && mon == "*" && dow == "*") {
                    return "Ogni giorno alle $time ($tz)"
                }
                val dowName = weekdayName(dow)
                if (dom == "*" && mon == "*" && dowName != null) {
                    return "Ogni $dowName alle $time ($tz)"
                }
                val domDay = dom.toIntOrNull()
                if (domDay != null && domDay in 1..31 && mon == "*" && dow == "*") {
                    return "Ogni mese il giorno $domDay alle $time ($tz)"
                }
            }
        }
        return "Cron '$expr' ($tz)"
    }

    private fun weekdayName(dow: String): String? = when (dow.toIntOrNull()) {
        0, 7 -> "domenica"
        1 -> "lunedì"
        2 -> "martedì"
        3 -> "mercoledì"
        4 -> "giovedì"
        5 -> "venerdì"
        6 -> "sabato"
        else -> null
    }

    /** Mostra i Double "interi" senza `.0` (es. raggio 100.0 -> "100"), preservando i decimali reali. */
    private fun fmtNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /**
     * `Double.toString()` mostra la rappresentazione binaria (`15.266659599999999`) e la review
     * deve essere leggibile da un umano, non fedele all'IEEE 754. Cinque decimali valgono ~1 m:
     * su raggi da decine o centinaia di metri, il resto è rumore. `Locale.ROOT` tiene il punto
     * decimale, che è il separatore atteso per le coordinate.
     */
    private fun fmtCoord(d: Double): String {
        val rounded = String.format(Locale.ROOT, "%.5f", d).trimEnd('0').trimEnd('.')
        return if (rounded == "-0") "0" else rounded
    }

    private fun queryLabel(query: StateQuery): String = when (query) {
        is StateQuery.Builtin -> "stato ${query.key}"
        is StateQuery.Setting -> "setting ${query.namespace.name}:${query.key}"
        is StateQuery.SystemProperty -> "property ${query.name}"
        is StateQuery.Sysfs -> "sysfs ${query.path}"
        is StateQuery.DumpsysField -> "dumpsys ${query.service} · campo ${query.field}"
    }

    private fun valueTypeLabel(type: StateValueType): String = when (type) {
        StateValueType.TEXT -> "testo"
        StateValueType.NUMBER -> "numero"
        StateValueType.BOOLEAN -> "booleano"
    }
}
