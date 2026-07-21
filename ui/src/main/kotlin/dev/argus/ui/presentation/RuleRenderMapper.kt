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
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.integrity
import dev.argus.engine.model.declaredType
import dev.argus.engine.model.provenance
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.VarRow

/**
 * DETERMINISTIC rendering of an automation into [RuleRender] — direttiva sicurezza §5.1.
 *
 * The approval screen renders the rule from its TYPES, never from the LLM prose (`rationale`).
 * This mapper is therefore a security control: it must describe *faithfully and unambiguously*
 * what the rule does. Every [Trigger]/[Condition]/[Action] variant is rendered via exhaustive
 * `when` (no `else`), nothing is dropped or softened — in particular shell commands are shown
 * integrally and any generative/cloud flag is surfaced.
 *
 * Pure JVM presentation logic: no `android.*`, no `Context`, no string resources. Il testo è
 * bilingue via [RenderLanguage] (tabelle EN/IT affiancate al call-site): produzione = locale di
 * sistema, test = lingua esplicita. La nota privacy IT resta verbatim da design §7.3.
 */
object RuleRenderMapper {

    /** Disclosure provider-neutral: il brain può essere Hermes oppure un provider diretto. */
    private fun privacyNote(l: RenderLanguage): String = l.pick(
        "The notification text will be sent to the configured AI service to generate the reply.",
        "Il testo delle notifiche verrà inviato al servizio AI configurato per generare la risposta.",
    )

    private fun statePrivacyNote(l: RenderLanguage): String = l.pick(
        "The notification text and values from the listed state readers will be sent to the " +
            "configured AI service to generate the reply.",
        "Il testo delle notifiche e i valori prodotti dai reader di stato elencati verranno " +
            "inviati al servizio AI configurato per generare la risposta.",
    )

    /**
     * [conversationLabels] mappa conversationId → display name proveniente dallo store whitelist
     * FIDATO (mai dalla prosa LLM): quando il trigger punta a un id whitelistato la review mostra
     * il nome leggibile al posto dell'hash. Lookup deterministico, opzionale per i caller che non
     * hanno accesso allo store (fallback: hash integrale, onesto).
     */
    fun map(
        a: Automation,
        conversationLabels: Map<String, String> = emptyMap(),
        language: RenderLanguage = RenderLanguage.system(),
    ): RuleRender = render(a.trigger, a.actions, a.conditions, a.vars, conversationLabels, language)

    fun mapDraft(
        d: AutomationDraft,
        conversationLabels: Map<String, String> = emptyMap(),
        language: RenderLanguage = RenderLanguage.system(),
    ): RuleRender = render(d.trigger, d.actions, d.conditions, d.vars, conversationLabels, language)

    private fun render(
        trigger: Trigger,
        actions: List<Action>,
        conditions: Condition?,
        vars: List<VarBinding>,
        conversationLabels: Map<String, String>,
        l: RenderLanguage,
    ): RuleRender {
        val isGenerative = actions.any { it.tier == ActionTier.GENERATIVE }
        return RuleRender(
            triggerLine = triggerLine(trigger, conversationLabels, l),
            triggerIconKey = triggerIconKey(trigger),
            conditionLines = conditions?.let { flattenConditions(it, 0, l) } ?: emptyList(),
            actions = actions.map { actionRow(it, l) },
            isGenerative = isGenerative,
            privacyNote = when {
                containsInvokeLlmV2(actions) -> statePrivacyNote(l)
                isGenerative -> privacyNote(l)
                else -> null
            },
            vars = vars.map { varRow(it, trigger, l) },
            program = programNodes(actions, l),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Programma P4 — albero ricorsivo (if/while/wait/foglie). Ogni stringa è resa dai TIPI; nessuna
    // foglia annidata può restare nascosta (lo verifica lo snapshot test). Wait è una foglia.
    // ---------------------------------------------------------------------------------------------

    private fun programNodes(actions: List<Action>, l: RenderLanguage): List<ProgramNode> =
        actions.map { programNode(it, l) }

    private fun programNode(a: Action, l: RenderLanguage): ProgramNode = when (a) {
        is Action.If -> ProgramNode.IfNode(
            title = l.pick("If:", "Se:"),
            conditionLines = flattenConditions(a.condition, 0, l),
            thenTitle = l.pick("Then:", "Allora:"),
            then = programNodes(a.then, l),
            elseTitle = if (a.orElse.isNotEmpty()) l.pick("Otherwise:", "Altrimenti:") else null,
            orElse = programNodes(a.orElse, l),
        )
        is Action.While -> ProgramNode.WhileNode(
            title = l.pick(
                "Repeat ${whileCountEn(a)}" +
                    (if (a.delayBetweenMs > 0) " · ${a.delayBetweenMs} ms between" else ""),
                "Ripeti ${whileCountIt(a)}" +
                    (if (a.delayBetweenMs > 0) " · ${a.delayBetweenMs} ms tra le iterazioni" else ""),
            ),
            conditionLines = flattenConditions(a.condition, 0, l),
            body = programNodes(a.body, l),
        )
        else -> ProgramNode.Leaf(actionRow(a, l))
    }

    // Conteggio iterazioni del while: letterale ("up to N times") oppure variabile NUMBER, che
    // mostra il nome in sintassi di interpolazione col tetto runtime esplicito ("up to ${n} times
    // (max 1000)").
    private fun whileCountEn(a: Action.While): String =
        a.maxIterationsVar?.let { "up to \${$it} times (max 1000)" } ?: "up to ${a.maxIterations} times"

    private fun whileCountIt(a: Action.While): String =
        a.maxIterationsVar?.let { "fino a \${$it} volte (max 1000)" } ?: "fino a ${a.maxIterations} volte"

    // ---------------------------------------------------------------------------------------------
    // Variabili P4 — resa della DEFINIZIONE (mai valori runtime): tipo, integrità, riservatezza,
    // provenienza. L'integrità "external" segnala i dati non fidati (payload del trigger).
    // ---------------------------------------------------------------------------------------------

    private fun varRow(v: VarBinding, trigger: Trigger, l: RenderLanguage): VarRow = VarRow(
        name = v.name,
        typeLabel = varTypeLabel(v.declaredType, l),
        integrityLabel = integrityLabel(v.integrity, l),
        confidentialityLabel = confidentialityLabel(v.confidentiality, l),
        provenanceLabel = v.provenance(trigger).joinToString(", ") { provenanceLabel(it, l) },
    )

    private fun varTypeLabel(t: VarType, l: RenderLanguage): String = when (t) {
        VarType.TEXT -> l.pick("text", "testo")
        VarType.NUMBER -> l.pick("number", "numero")
        VarType.BOOLEAN -> l.pick("boolean", "booleano")
    }

    private fun integrityLabel(i: IntegrityLabel, l: RenderLanguage): String = when (i) {
        IntegrityLabel.CLEAN -> l.pick("trusted", "attendibile")
        IntegrityLabel.TAINTED -> l.pick("external", "esterno")
    }

    private fun confidentialityLabel(c: ConfidentialityLabel, l: RenderLanguage): String = when (c) {
        ConfidentialityLabel.PUBLIC -> l.pick("public", "pubblico")
        ConfidentialityLabel.PRIVATE -> l.pick("private", "privato")
        ConfidentialityLabel.SECRET -> l.pick("secret", "segreto")
    }

    private fun provenanceLabel(p: ValueProvenance, l: RenderLanguage): String = when (p) {
        ValueProvenance.LITERAL -> l.pick("fixed value", "valore fisso")
        ValueProvenance.DEVICE_STATE -> l.pick("device state", "stato dispositivo")
        ValueProvenance.NOTIFICATION -> l.pick("notification", "notifica")
        ValueProvenance.SMS -> "SMS"
        ValueProvenance.PHONE -> l.pick("call", "chiamata")
        ValueProvenance.MODEL -> l.pick("AI output", "output AI")
        ValueProvenance.SHELL -> l.pick("shell output", "output shell")
        ValueProvenance.ENGINE -> l.pick("random", "casuale")
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

    private fun triggerLine(t: Trigger, conversationLabels: Map<String, String>, l: RenderLanguage): String = when (t) {
        is Trigger.Notification -> notificationLine(t, conversationLabels, l)
        is Trigger.Time -> timeLine(t, l)
        is Trigger.Immediate -> l.pick("Once, on activation", "Una volta, all'attivazione")
        is Trigger.Geofence -> geofenceLine(t, l)
        is Trigger.PhoneState -> phoneStateLine(t, l)
        is Trigger.Connectivity -> connectivityLine(t, l)
        is Trigger.Sensor -> sensorLine(t, l)
    }

    /** Prefisso "Quando: "/"When: " condiviso con chi deriva il sommario lista (UiStateMappers). */
    fun whenPrefix(language: RenderLanguage = RenderLanguage.system()): String =
        language.pick("When: ", "Quando: ")

    private fun notificationLine(
        t: Trigger.Notification,
        conversationLabels: Map<String, String>,
        l: RenderLanguage,
    ): String {
        val chat = when (t.isGroup) {
            true -> l.pick("group", "gruppo")
            false -> l.pick("1:1 chat", "chat 1:1")
            null -> l.pick("1:1 chat or group", "chat 1:1 o gruppo")
        }
        // Il TriggerMatcher dà al conversationId precedenza ESCLUSIVA sul sender: quando l'id è
        // whitelistato il nome fidato descrive il criterio di match reale meglio del sender LLM
        // o dell'hash. Senza label si mostra l'hash INTEGRALE: onesto, mai abbreviato (§5).
        val trusted = t.conversationId?.let(conversationLabels::get)
        val prefix = whenPrefix(l)
        val app = appLabel(t.pkg)
        val base = if (trusted != null) {
            l.pick(
                "$prefix$app notification from $trusted (verified identity, $chat)",
                "${prefix}notifica $app da $trusted (identità verificata, $chat)",
            )
        } else {
            val who = t.sender ?: t.conversationId ?: l.pick("anyone", "chiunque")
            l.pick(
                "$prefix$app notification from $who ($chat)",
                "${prefix}notifica $app da $who ($chat)",
            )
        }
        // Non nascondere i filtri che restringono quando la regola scatta (§5: non "softare" nulla).
        val title = t.titleMatch?.let { l.pick(" · title \"$it\"", " · titolo \"$it\"") } ?: ""
        val text = t.textMatch?.let { l.pick(" · text \"$it\"", " · testo \"$it\"") } ?: ""
        return base + title + text
    }

    private fun timeLine(t: Trigger.Time, l: RenderLanguage): String {
        val cron = t.cron
        val at = t.at
        val afterMs = t.afterMs
        return when {
            cron != null -> humanizeCron(cron, t.tz, l)
            at != null -> l.pick(
                "Once on ${at.replace("T", " at ")} (${t.tz})",
                "Una volta il ${at.replace("T", " alle ")} (${t.tz})",
            )
            // Ritardo relativo one-shot (ri-armabile): nessun orario assoluto da mostrare, solo la
            // durata umanizzata (l'ancora reale è congelata a runtime nel ScheduledTimeAlarm).
            afterMs != null -> l.pick(
                "Once, in ${humanizeDelay(afterMs, l)}",
                "Una volta, tra ${humanizeDelay(afterMs, l)}",
            )
            else -> l.pick("Time not specified (${t.tz})", "Orario non specificato (${t.tz})")
        }
    }

    /**
     * Umanizza un ritardo relativo: unità più significativa più, se presente, quella immediatamente
     * inferiore (es. 5_400_000 → "1 ora e 30 minuti" / "1 hour and 30 minutes"), con singolare e
     * plurale corretti per lingua. Le unità sotto la seconda scelta vengono troncate.
     */
    private fun humanizeDelay(ms: Long, l: RenderLanguage): String {
        val totalSeconds = ms / 1_000
        val units = listOf(
            Triple(totalSeconds / 86_400, l.pick("day", "giorno") to l.pick("days", "giorni"), Unit),
            Triple((totalSeconds % 86_400) / 3_600, l.pick("hour", "ora") to l.pick("hours", "ore"), Unit),
            Triple((totalSeconds % 3_600) / 60, l.pick("minute", "minuto") to l.pick("minutes", "minuti"), Unit),
            Triple(totalSeconds % 60, l.pick("second", "secondo") to l.pick("seconds", "secondi"), Unit),
        )
        fun render(value: Long, names: Pair<String, String>) =
            "$value ${if (value == 1L) names.first else names.second}"

        val primaryIndex = units.indexOfFirst { it.first != 0L }
        // Difensivo: afterMs >= 1s.
        if (primaryIndex == -1) return render(0, l.pick("second", "secondo") to l.pick("seconds", "secondi"))
        val (pv, pn, _) = units[primaryIndex]
        val primary = render(pv, pn)
        val secondary = units.getOrNull(primaryIndex + 1)
            ?.takeIf { it.first != 0L }
            ?.let { (sv, sn, _) -> render(sv, sn) }
        val joiner = l.pick(" and ", " e ")
        return if (secondary != null) "$primary$joiner$secondary" else primary
    }

    private fun geofenceLine(t: Trigger.Geofence, l: RenderLanguage): String {
        val r = fmtNum(t.radiusM)
        return if (t.resolveCurrentLocation) {
            when (t.transition) {
                Transition.ENTER -> l.pick(
                    "When you enter the current location (±$r m)",
                    "Quando entri nella posizione attuale (±$r m)",
                )
                Transition.EXIT -> l.pick(
                    "When you leave the current location (±$r m)",
                    "Quando esci dalla posizione attuale (±$r m)",
                )
                Transition.DWELL -> l.pick(
                    "When you dwell at the current location (±$r m)",
                    "Quando ti trattieni nella posizione attuale (±$r m)",
                )
            }
        } else {
            val loc = "${fmtCoord(t.lat)},${fmtCoord(t.lng)}"
            when (t.transition) {
                Transition.ENTER -> l.pick(
                    "When you enter $loc (±$r m)",
                    "Quando entri in $loc (±$r m)",
                )
                Transition.EXIT -> l.pick(
                    "When you leave $loc (±$r m)",
                    "Quando esci da $loc (±$r m)",
                )
                Transition.DWELL -> l.pick(
                    "When you dwell at $loc (±$r m)",
                    "Quando ti trattieni presso $loc (±$r m)",
                )
            }
        }
    }

    private fun phoneStateLine(t: Trigger.PhoneState, l: RenderLanguage): String {
        val event = when (t.event) {
            PhoneEvent.INCOMING_CALL -> l.pick("incoming call", "chiamata in arrivo")
            PhoneEvent.CALL_ENDED -> l.pick("call ended", "chiamata terminata")
            PhoneEvent.SMS_RECEIVED -> l.pick("SMS received", "SMS ricevuto")
        }
        val from = t.number?.let { l.pick("from $it", "da $it") } ?: l.pick("from anyone", "da chiunque")
        // §5: i filtri che restringono il match restano visibili integrali in review.
        val text = t.textMatch?.let { l.pick(" · text \"$it\"", " · testo \"$it\"") } ?: ""
        return "${whenPrefix(l)}$event $from$text"
    }

    private fun connectivityLine(t: Trigger.Connectivity, l: RenderLanguage): String {
        val medium = when (t.medium) {
            ConnMedium.WIFI -> "Wi-Fi"
            ConnMedium.BT -> "Bluetooth"
            ConnMedium.POWER -> l.pick("power", "alimentazione")
        }
        val state = when (t.medium) {
            ConnMedium.POWER ->
                if (t.state == ConnState.CONNECTED) l.pick("connected", "collegata")
                else l.pick("disconnected", "scollegata")
            else ->
                if (t.state == ConnState.CONNECTED) l.pick("connected", "connesso")
                else l.pick("disconnected", "disconnesso")
        }
        val match = t.match?.let { " ($it)" } ?: ""
        return "${whenPrefix(l)}$medium $state$match"
    }

    private fun sensorLine(t: Trigger.Sensor, l: RenderLanguage): String {
        val kind = when (t.kind) {
            SensorKind.SIGNIFICANT_MOTION -> l.pick(
                "significant motion detected",
                "rilevato un movimento significativo",
            )
            SensorKind.STATIONARY_DETECT -> l.pick(
                "the device becomes stationary",
                "il dispositivo diventa fermo",
            )
            SensorKind.MOTION_DETECT -> l.pick(
                "the device starts moving again",
                "il dispositivo torna in movimento",
            )
            SensorKind.STEP_DETECTOR -> l.pick(
                "${t.minimumEventCount} steps detected",
                "rilevati ${t.minimumEventCount} passi",
            )
            SensorKind.STEP_COUNTER -> l.pick(
                "the step counter increases by ${t.minimumEventCount} steps",
                "il contatore aumenta di ${t.minimumEventCount} passi",
            )
        }
        return "${whenPrefix(l)}$kind"
    }

    // ---------------------------------------------------------------------------------------------
    // Condition tree — appiattito in righe indentate (§6)
    // ---------------------------------------------------------------------------------------------

    private fun flattenConditions(c: Condition, depth: Int, l: RenderLanguage): List<String> {
        val pad = "  ".repeat(depth)
        return when (c) {
            is Condition.TimeWindow -> listOf(
                l.pick(
                    "${pad}Only between ${c.startLocal} and ${c.endLocal} (${c.tz})",
                    "${pad}Solo tra le ${c.startLocal} e le ${c.endLocal} (${c.tz})",
                ),
            )
            is Condition.StateEquals -> listOf(
                l.pick(
                    "${pad}Only if ${c.key} ${opLabel(c.op, l)} ${c.value}",
                    "${pad}Solo se ${c.key} ${opLabel(c.op, l)} ${c.value}",
                ),
            )
            is Condition.StateCompare -> listOf(
                l.pick(
                    "${pad}Only if ${queryLabel(c.query, l)} [${valueTypeLabel(c.valueType, l)}] " +
                        "${opLabel(c.op, l)} ${c.expected}",
                    "${pad}Solo se ${queryLabel(c.query, l)} [${valueTypeLabel(c.valueType, l)}] " +
                        "${opLabel(c.op, l)} ${c.expected}",
                ),
            )
            is Condition.AppInForeground -> listOf(
                l.pick(
                    "${pad}Only if ${appLabel(c.pkg)} is in the foreground",
                    "${pad}Solo se ${appLabel(c.pkg)} è in primo piano",
                ),
            )
            is Condition.LocationIn -> listOf(
                l.pick(
                    "${pad}Only if you are within ${fmtNum(c.radiusM)} m of ${c.lat},${c.lng}",
                    "${pad}Solo se ti trovi entro ${fmtNum(c.radiusM)} m da ${c.lat},${c.lng}",
                ),
            )
            is Condition.And ->
                listOf(l.pick("${pad}All of the following:", "${pad}Tutte le seguenti:")) +
                    c.all.flatMap { flattenConditions(it, depth + 1, l) }
            is Condition.Or ->
                listOf(l.pick("${pad}At least one of the following:", "${pad}Almeno una delle seguenti:")) +
                    c.any.flatMap { flattenConditions(it, depth + 1, l) }
            is Condition.Not ->
                listOf(l.pick("${pad}Must not hold:", "${pad}Non deve valere:")) +
                    flattenConditions(c.cond, depth + 1, l)
            is Condition.BooleanLiteral ->
                listOf("$pad${if (c.value) l.pick("Always", "Sempre") else l.pick("Never", "Mai")}")
            // P4: confronto su variabile di programma. La destra è un'altra variabile (${nome}) o un
            // letterale. Rendering minimale a una riga (l'espansione ricca dei badge provenienza è P4-E).
            is Condition.VarCompare -> {
                // IS_EVEN/IS_ODD sono UNARI: nessun operando destro da rendere.
                val unary = c.op == CmpOp.IS_EVEN || c.op == CmpOp.IS_ODD
                val right = if (unary) "" else " " + (c.expectedVar?.let { "\${$it}" } ?: c.expected ?: "<?>")
                listOf(
                    l.pick(
                        "${pad}Only if \${${c.varName}} ${opLabel(c.op, l)}$right",
                        "${pad}Solo se \${${c.varName}} ${opLabel(c.op, l)}$right",
                    ),
                )
            }
        }
    }

    private fun opLabel(op: CmpOp, l: RenderLanguage): String = when (op) {
        CmpOp.EQ -> "="
        CmpOp.NEQ -> "≠"
        CmpOp.GT -> ">"
        CmpOp.LT -> "<"
        CmpOp.CONTAINS -> l.pick("contains", "contiene")
        CmpOp.IS_EVEN -> l.pick("is even", "è pari")
        CmpOp.IS_ODD -> l.pick("is odd", "è dispari")
    }

    // ---------------------------------------------------------------------------------------------
    // Action -> ActionRow
    // ---------------------------------------------------------------------------------------------

    private fun actionRow(a: Action, l: RenderLanguage): ActionRow = when (a) {
        is Action.SetWifi -> row(
            iconKey = "wifi_off",
            label = if (a.on) l.pick("Turn on Wi-Fi", "Attiva Wi-Fi")
            else l.pick("Turn off Wi-Fi", "Disattiva Wi-Fi"),
        )
        is Action.SetBluetooth -> row(
            iconKey = "bluetooth",
            label = if (a.on) l.pick("Turn on Bluetooth", "Attiva Bluetooth")
            else l.pick("Turn off Bluetooth", "Disattiva Bluetooth"),
        )
        is Action.SetMobileData -> row(
            iconKey = "mobile_data",
            label = if (a.on) l.pick("Turn on mobile data", "Attiva dati mobili")
            else l.pick("Turn off mobile data", "Disattiva dati mobili"),
        )
        is Action.SetDnd -> row(
            iconKey = "dnd",
            label = when (a.mode) {
                DndMode.OFF -> l.pick("Turn off Do Not Disturb", "Disattiva Non disturbare")
                DndMode.PRIORITY -> l.pick(
                    "Turn on Do Not Disturb (priority)",
                    "Attiva Non disturbare (priorità)",
                )
                DndMode.TOTAL -> l.pick(
                    "Turn on Do Not Disturb (total)",
                    "Attiva Non disturbare (totale)",
                )
            },
        )
        is Action.SetRinger -> row(
            iconKey = "ringer",
            label = l.pick("Set ringer (${a.mode})", "Imposta suoneria (${a.mode})"),
        )
        is Action.LaunchApp -> row(
            iconKey = "launch_app",
            label = l.pick("Open app", "Apri app"),
            detail = appLabel(a.pkg),
        )
        is Action.OpenUrl -> row(
            iconKey = "open_url",
            label = l.pick("Open URL", "Apri URL"),
            detail = a.url,
        )
        is Action.ShowNotification -> row(
            iconKey = "notify",
            label = l.pick("Show notification", "Mostra notifica"),
            detail = a.text,
        )
        is Action.Tap -> row(
            iconKey = "tap",
            label = l.pick("Tap the screen", "Tocca schermo"),
            detail = "(${a.x}, ${a.y})",
            requiresLiveConfirm = true,
        )
        is Action.InputText -> row(
            iconKey = "input_text",
            label = l.pick("Type text", "Digita testo"),
            detail = a.text,
            requiresLiveConfirm = true,
        )
        is Action.WhatsAppReply -> row(
            iconKey = "whatsapp_reply",
            label = l.pick("Reply on WhatsApp", "Rispondi su WhatsApp"),
            detail = a.text,
            requiresLiveConfirm = true,
        )
        is Action.RunShell -> row(
            iconKey = "shell",
            label = l.pick("Run shell command", "Esegui comando shell"),
            detail = captureLabel(a.captureAs, l),
            isShell = true,
            shellCommand = a.cmd, // integrale, mai troncato (invariante §5.4)
        )
        is Action.CopyToClipboard -> row(
            iconKey = "clipboard",
            label = l.pick("Copy to clipboard", "Copia negli appunti"),
            // §5: la regex è il criterio reale di estrazione, resta visibile integrale.
            detail = a.extractionRegex?.let { l.pick("extraction: $it", "estrazione: $it") }
                ?: l.pick("the full text of the message", "il testo integrale del messaggio"),
        )
        is Action.CopyText -> row(
            iconKey = "clipboard",
            label = l.pick("Copy text to clipboard", "Copia testo negli appunti"),
            // Il testo letterale approvato è il criterio reale: resta visibile integrale.
            detail = a.text,
        )
        is Action.SetAlarm -> row(
            iconKey = "alarm",
            label = l.pick("Set alarm", "Imposta sveglia"),
            detail = "%02d:%02d".format(a.hour, a.minute) +
                (a.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        )
        is Action.SetTimer -> row(
            iconKey = "timer",
            label = l.pick("Start timer", "Avvia timer"),
            detail = "${a.seconds}s" +
                (a.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        )
        is Action.SetVolume -> row(
            iconKey = "volume",
            label = l.pick("Set volume", "Imposta volume"),
            detail = "${a.stream.name.lowercase()} · ${l.pick("level", "livello")} ${a.level}",
        )
        is Action.SetFlashlight -> row(
            iconKey = "flashlight",
            label = if (a.on) l.pick("Turn on flashlight", "Accendi torcia")
            else l.pick("Turn off flashlight", "Spegni torcia"),
            detail = if (a.on) "on" else "off",
        )
        is Action.SetDarkMode -> row(
            iconKey = "settings",
            label = l.pick("Set dark mode", "Imposta modalità scura"),
            detail = when (a.mode) {
                NightMode.OFF -> l.pick("off", "off")
                NightMode.ON -> l.pick("on", "on")
                NightMode.AUTO -> l.pick("auto", "auto")
            },
        )
        is Action.OpenSettingsScreen -> row(
            iconKey = "settings",
            label = l.pick("Open Settings", "Apri Impostazioni"),
            detail = a.screen.name.lowercase() +
                (a.pkg?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        )
        is Action.Vibrate -> row(
            iconKey = "vibrate",
            label = l.pick("Vibrate", "Vibra"),
            detail = "${a.durationMs} ms",
        )
        is Action.Wait -> row(
            iconKey = "control_flow",
            label = l.pick("Wait", "Attendi"),
            detail = "${a.durationMs} ms",
        )
        is Action.WriteSetting -> row(
            iconKey = "settings",
            // D2: la review mostra namespace/key/value LETTERALI e integrali (l'utente approva
            // esattamente questa terna, mai interpolata dal trigger).
            label = l.pick("Write setting", "Scrivi impostazione"),
            detail = "${a.namespace.name.lowercase()} · ${a.key} = ${a.value}",
        )
        is Action.InvokeLlm -> row(
            iconKey = "generative",
            // §5: i due sink (reply vs notifica locale) restano distinti e non "softati" in review.
            label = when (a.deliver) {
                GenerativeDeliverMode.WHATSAPP_REPLY ->
                    l.pick("Generate and reply with AI", "Genera e rispondi con l'AI")
                GenerativeDeliverMode.LOCAL_NOTIFICATION ->
                    l.pick("Generate and notify", "Genera e notifica")
            },
            detail = when (a.deliver) {
                GenerativeDeliverMode.WHATSAPP_REPLY -> l.pick(
                    "Goal: ${a.goal} · tools: ${a.allowedTools.joinToString(", ")}",
                    "Obiettivo: ${a.goal} · tool: ${a.allowedTools.joinToString(", ")}",
                )
                // Titolo LETTERALE (D2): l'utente approva esattamente questo testo, mai dal trigger.
                GenerativeDeliverMode.LOCAL_NOTIFICATION -> l.pick(
                    "Notification title: ${a.notificationTitle.orEmpty()} · Goal: ${a.goal} · " +
                        "tools: ${a.allowedTools.joinToString(", ")}",
                    "Titolo notifica: ${a.notificationTitle.orEmpty()} · Obiettivo: ${a.goal} · " +
                        "tool: ${a.allowedTools.joinToString(", ")}",
                )
            } + captureSuffix(a.captureAs, l),
            isGenerative = true,
        )
        is Action.InvokeLlmV2 -> row(
            iconKey = "generative",
            label = l.pick("Reply with AI", "Rispondi con l'AI"),
            detail = buildString {
                append(
                    l.pick("Goal: ${a.goal} · shared state: ", "Obiettivo: ${a.goal} · stato condiviso: "),
                )
                append(
                    a.stateContext.joinToString("; ") { context ->
                        "${queryLabel(context.query, l)} [${valueTypeLabel(context.valueType, l)}, " +
                            "${context.integrity.name}, ${context.confidentiality.name}]"
                    },
                )
                append(l.pick(" · tools: ", " · tool: "))
                append(a.allowedTools.joinToString(", "))
                append(captureSuffix(a.captureAs, l))
            },
            isGenerative = true,
        )
        // P4 control-flow: nella vista COMPATTA (chip) i blocchi if/while restano un sommario a una
        // riga; il dettaglio ricorsivo completo è reso dall'albero `program` (ProgramNode) nella
        // schermata di approvazione. isGenerative dal tier aggregato tiene onesta anche la vista chip.
        is Action.If -> row(
            iconKey = "control_flow",
            label = l.pick("If (condition), run a block", "Se (condizione), esegui un blocco"),
            detail = l.pick(
                "${a.then.size} actions if true" +
                    (if (a.orElse.isNotEmpty()) ", ${a.orElse.size} otherwise" else ""),
                "${a.then.size} azioni se vera" +
                    (if (a.orElse.isNotEmpty()) ", ${a.orElse.size} altrimenti" else ""),
            ),
            isGenerative = a.tier == ActionTier.GENERATIVE,
        )
        is Action.While -> row(
            iconKey = "control_flow",
            label = l.pick(
                "Repeat (${whileCountEn(a)})",
                a.maxIterationsVar?.let { "Ripeti (fino a \${$it} volte, max 1000)" }
                    ?: "Ripeti (max ${a.maxIterations} volte)",
            ),
            detail = l.pick(
                "${a.body.size} actions per iteration" +
                    (if (a.delayBetweenMs > 0) " · every ${a.delayBetweenMs} ms" else ""),
                "${a.body.size} azioni per iterazione" +
                    (if (a.delayBetweenMs > 0) " · ogni ${a.delayBetweenMs} ms" else ""),
            ),
            isGenerative = a.tier == ActionTier.GENERATIVE,
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

    // captureAs: dove l'azione salva il proprio output (P4). Il produttore è l'azione stessa.
    /** Standalone (RunShell, che non ha altro detail). Null se non cattura. */
    private fun captureLabel(captureAs: String?, l: RenderLanguage): String? =
        captureAs?.let { l.pick("captured as $it", "catturato come $it") }

    /** Suffisso per le azioni che hanno già un detail (invoke_llm). Vuoto se non cattura. */
    private fun captureSuffix(captureAs: String?, l: RenderLanguage): String =
        captureAs?.let { l.pick(" · captured as $it", " · catturato come $it") } ?: ""

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Etichetta minimale del pacchetto (nessuna risorsa Android nel test JVM). */
    private fun appLabel(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        else -> pkg
    }

    /** Umanizza i cron comuni; fallback esplicito che mostra l'espressione integrale. */
    private fun humanizeCron(expr: String, tz: String, l: RenderLanguage): String {
        val parts = expr.trim().split(Regex("\\s+"))
        if (parts.size == 5) {
            val (min, hour, dom, mon, dow) = parts
            val m = min.toIntOrNull()
            val h = hour.toIntOrNull()
            if (m != null && h != null && m in 0..59 && h in 0..23) {
                val time = "%02d:%02d".format(h, m)
                if (dom == "*" && mon == "*" && dow == "*") {
                    return l.pick("Every day at $time ($tz)", "Ogni giorno alle $time ($tz)")
                }
                val dowName = weekdayName(dow, l)
                if (dom == "*" && mon == "*" && dowName != null) {
                    return l.pick("Every $dowName at $time ($tz)", "Ogni $dowName alle $time ($tz)")
                }
                val domDay = dom.toIntOrNull()
                if (domDay != null && domDay in 1..31 && mon == "*" && dow == "*") {
                    return l.pick(
                        "Every month on day $domDay at $time ($tz)",
                        "Ogni mese il giorno $domDay alle $time ($tz)",
                    )
                }
            }
        }
        return "Cron '$expr' ($tz)"
    }

    private fun weekdayName(dow: String, l: RenderLanguage): String? = when (dow.toIntOrNull()) {
        0, 7 -> l.pick("Sunday", "domenica")
        1 -> l.pick("Monday", "lunedì")
        2 -> l.pick("Tuesday", "martedì")
        3 -> l.pick("Wednesday", "mercoledì")
        4 -> l.pick("Thursday", "giovedì")
        5 -> l.pick("Friday", "venerdì")
        6 -> l.pick("Saturday", "sabato")
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

    private fun queryLabel(query: StateQuery, l: RenderLanguage): String = when (query) {
        is StateQuery.Builtin -> l.pick("state ${query.key}", "stato ${query.key}")
        is StateQuery.Setting -> "setting ${query.namespace.name}:${query.key}"
        is StateQuery.SystemProperty -> "property ${query.name}"
        is StateQuery.Sysfs -> "sysfs ${query.path}"
        is StateQuery.DumpsysField -> l.pick(
            "dumpsys ${query.service} · field ${query.field}",
            "dumpsys ${query.service} · campo ${query.field}",
        )
    }

    private fun valueTypeLabel(type: StateValueType, l: RenderLanguage): String = when (type) {
        StateValueType.TEXT -> l.pick("text", "testo")
        StateValueType.NUMBER -> l.pick("number", "numero")
        StateValueType.BOOLEAN -> l.pick("boolean", "booleano")
    }
}

/** Iterative because approval rendering may receive an unvalidated hostile tree. */
private fun containsInvokeLlmV2(actions: List<Action>): Boolean {
    val pending = ArrayDeque<Action>()
    actions.forEach(pending::addLast)
    while (pending.isNotEmpty()) {
        when (val action = pending.removeFirst()) {
            is Action.InvokeLlmV2 -> return true
            is Action.If -> {
                action.then.forEach(pending::addLast)
                action.orElse.forEach(pending::addLast)
            }
            is Action.While -> action.body.forEach(pending::addLast)
            else -> Unit
        }
    }
    return false
}
