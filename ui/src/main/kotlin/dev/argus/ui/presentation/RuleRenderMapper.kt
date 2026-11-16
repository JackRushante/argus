package dev.argus.ui.presentation

import java.util.Locale
import dev.argus.engine.model.Action
import dev.argus.engine.model.AppPackages
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ClockVariable
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.Condition
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.InterpolationPolicy
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.integrity
import dev.argus.engine.model.declaredType
import dev.argus.engine.model.provenance
import dev.argus.engine.runtime.Engine
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.AppRef
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.VarRow
import dev.argus.ui.model.VarSensitivity

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

    // ---------------------------------------------------------------------------------------------
    // Nota privacy (S2): composta da PARTI, ognuna presente solo se quel dato lascia davvero il
    // telefono verso il servizio AI configurato (il brain può essere Hermes oppure un provider
    // diretto). Con una sola parte "testo delle notifiche" il testo coincide verbatim con la nota
    // storica di design §7.3.
    // ---------------------------------------------------------------------------------------------

    private enum class PrivacyPart { NOTIFICATION_TEXT, DEVICE_STATE, GOAL_VARIABLES }

    private enum class PrivacyResult { REPLY, NOTIFICATION, VALUE }

    /**
     * Null se la regola non ha azioni generative (a qualunque profondità). Altrimenti elenca le
     * parti: testo della notifica (trigger Notification + contesto `notification`), valori di stato
     * (contesto `state` o invoke_llm_v2), valori delle variabili usate nell'obiettivo (`${…}`).
     * invoke_llm_v2 risponde sempre a una notifica e ne riceve il testo come contesto implicito.
     */
    private fun privacyNote(trigger: Trigger, actions: List<Action>, l: RenderLanguage): String? {
        val parts = java.util.EnumSet.noneOf(PrivacyPart::class.java)
        val results = java.util.EnumSet.noneOf(PrivacyResult::class.java)
        val pending = ArrayDeque<Action>()
        actions.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            when (val action = pending.removeFirst()) {
                is Action.InvokeLlm -> {
                    if (trigger is Trigger.Notification &&
                        GenerativeContract.CONTEXT_NOTIFICATION in action.contextSources
                    ) parts += PrivacyPart.NOTIFICATION_TEXT
                    if (GenerativeContract.CONTEXT_STATE in action.contextSources) {
                        parts += PrivacyPart.DEVICE_STATE
                    }
                    if (InterpolationPolicy.containsInterpolation(action.goal)) {
                        parts += PrivacyPart.GOAL_VARIABLES
                    }
                    results += when (action.deliver) {
                        GenerativeDeliverMode.WHATSAPP_REPLY -> PrivacyResult.REPLY
                        GenerativeDeliverMode.LOCAL_NOTIFICATION -> PrivacyResult.NOTIFICATION
                        GenerativeDeliverMode.CAPTURE_ONLY -> PrivacyResult.VALUE
                    }
                }
                is Action.InvokeLlmV2 -> {
                    if (trigger is Trigger.Notification) parts += PrivacyPart.NOTIFICATION_TEXT
                    parts += PrivacyPart.DEVICE_STATE
                    if (InterpolationPolicy.containsInterpolation(action.goal)) {
                        parts += PrivacyPart.GOAL_VARIABLES
                    }
                    results += PrivacyResult.REPLY
                }
                is Action.If -> {
                    action.then.forEach(pending::addLast)
                    action.orElse.forEach(pending::addLast)
                }
                is Action.While -> action.body.forEach(pending::addLast)
                else -> Unit
            }
        }
        if (results.isEmpty()) return null

        val result = when (results.singleOrNull()) {
            PrivacyResult.REPLY -> l.pick("the reply", "la risposta")
            PrivacyResult.NOTIFICATION -> l.pick("the notification", "la notifica")
            PrivacyResult.VALUE -> l.pick("the value", "il valore")
            null -> l.pick("the result", "il risultato")
        }
        if (parts.isEmpty()) {
            // Nessun dato dinamico: parte comunque l'obiettivo approvato, e lo si dice.
            return l.pick(
                "The goal of this rule will be sent to the configured AI service to generate $result.",
                "L'obiettivo di questa regola verrà inviato al servizio AI configurato per generare $result.",
            )
        }
        val items = parts.map { privacyPartText(it, l) }
        val list = when (items.size) {
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + l.pick(" and ", " e ") + items.last()
        }
        val plural = parts.size > 1 || parts.single() != PrivacyPart.NOTIFICATION_TEXT
        return l.pick(
            "${list.replaceFirstChar(Char::uppercaseChar)} will be sent to the configured AI service " +
                "to generate $result.",
            "${list.replaceFirstChar(Char::uppercaseChar)} " +
                "${if (plural) "verranno inviati" else "verrà inviato"} al servizio AI configurato " +
                "per generare $result.",
        )
    }

    private fun privacyPartText(part: PrivacyPart, l: RenderLanguage): String = when (part) {
        PrivacyPart.NOTIFICATION_TEXT -> l.pick("the notification text", "il testo delle notifiche")
        PrivacyPart.DEVICE_STATE -> l.pick(
            "values from the device state readers",
            "i valori dei reader di stato del dispositivo",
        )
        PrivacyPart.GOAL_VARIABLES -> l.pick(
            "the values of the variables used in the goal",
            "i valori delle variabili usate nell'obiettivo",
        )
    }

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
    ): RuleRender = render(a.trigger, a.actions, a.conditions, a.vars, a.cooldownMs, conversationLabels, language)

    fun mapDraft(
        d: AutomationDraft,
        conversationLabels: Map<String, String> = emptyMap(),
        language: RenderLanguage = RenderLanguage.system(),
    ): RuleRender = render(d.trigger, d.actions, d.conditions, d.vars, d.cooldownMs, conversationLabels, language)

    private fun render(
        trigger: Trigger,
        actions: List<Action>,
        conditions: Condition?,
        vars: List<VarBinding>,
        cooldownMs: Long,
        conversationLabels: Map<String, String>,
        l: RenderLanguage,
    ): RuleRender {
        val isGenerative = actions.any { it.tier == ActionTier.GENERATIVE }
        // S8: la pausa che l'engine applica davvero (Engine.effectiveCooldown: almeno 60 s se generativa).
        val effectiveCooldownMs = if (isGenerative) {
            maxOf(cooldownMs, Engine.MIN_GENERATIVE_COOLDOWN_MS)
        } else {
            cooldownMs
        }
        // 0.7.0: i package sostituibili portano il loro slot («Cambia app»); l'ordine è quello di AppPackages.
        val slots = AppPackages.slotsByAction(trigger, actions)
        return RuleRender(
            triggerLine = triggerLine(trigger, conversationLabels, l),
            triggerIconKey = triggerIconKey(trigger),
            conditionLines = conditions?.let { flattenConditions(it, 0, l) } ?: emptyList(),
            actions = actions.map { withAppRef(actionRow(it, l), it, slots) },
            isGenerative = isGenerative,
            privacyNote = if (isGenerative) privacyNote(trigger, actions, l) else null,
            vars = vars.map { varRow(it, trigger, l) },
            program = programNodes(actions, l, slots),
            triggerAppRef = (trigger as? Trigger.AppEvent)?.let { event ->
                AppPackages.triggerSlot(event)?.let { AppRef(it, event.pkg) }
            },
            cooldownLine = effectiveCooldownMs.takeIf { it > 0 }?.let { cooldownLine(it, l) },
        )
    }

    /** S8: dopo uno scatto gli eventi entro la pausa si scartano; con un ritardo lungo va detto chiaro. */
    private fun cooldownLine(ms: Long, l: RenderLanguage): String {
        val span = humanizeDuration(ms, l)
        return l.pick(
            "After it fires, it ignores new events for $span",
            "Dopo ogni scatto ignora i nuovi eventi per $span",
        )
    }

    /** Package nominato da un'azione sostituibile, se [slots] gliene assegna uno. */
    private fun withAppRef(row: ActionRow, action: Action, slots: Map<Action, Int>): ActionRow {
        val slot = slots[action] ?: return row
        val pkg = when (action) {
            is Action.LaunchApp -> action.pkg
            is Action.AppControl -> action.pkg
            is Action.AppPermission -> action.pkg
            is Action.UiSteps -> action.pkg
            else -> null
        } ?: return row
        return row.copy(appRef = AppRef(slot, pkg))
    }

    // ---------------------------------------------------------------------------------------------
    // Programma P4 — albero ricorsivo (if/while/wait/foglie). Ogni stringa è resa dai TIPI; nessuna
    // foglia annidata può restare nascosta (lo verifica lo snapshot test). Wait è una foglia.
    // ---------------------------------------------------------------------------------------------

    private fun programNodes(actions: List<Action>, l: RenderLanguage, slots: Map<Action, Int>): List<ProgramNode> =
        actions.map { programNode(it, l, slots) }

    private fun programNode(a: Action, l: RenderLanguage, slots: Map<Action, Int>): ProgramNode = when (a) {
        is Action.If -> ProgramNode.IfNode(
            title = l.pick("If:", "Se:"),
            conditionLines = flattenConditions(a.condition, 0, l),
            thenTitle = l.pick("Then:", "Allora:"),
            then = programNodes(a.then, l, slots),
            elseTitle = if (a.orElse.isNotEmpty()) l.pick("Otherwise:", "Altrimenti:") else null,
            orElse = programNodes(a.orElse, l, slots),
        )
        is Action.While -> ProgramNode.WhileNode(
            title = l.pick(
                "Repeat ${whileCountEn(a)}" +
                    (if (a.delayBetweenMs > 0) " · ${a.delayBetweenMs} ms between" else ""),
                "Ripeti ${whileCountIt(a)}" +
                    (if (a.delayBetweenMs > 0) " · ${a.delayBetweenMs} ms tra le iterazioni" else ""),
            ),
            // S8: l'engine rivaluta la condizione prima di OGNI giro: «finché», non il «solo se» di un cancello unico.
            conditionLines = flattenConditions(a.condition, 0, l, ConditionLead.WHILE),
            body = programNodes(a.body, l, slots),
        )
        else -> ProgramNode.Leaf(withAppRef(actionRow(a, l), a, slots))
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
        // S1: il letterale approvato si mostra INTEGRALE; per gli altri tipi mai un valore.
        valueText = (v as? VarBinding.Literal)?.value,
        sourceText = varSourceText(v, l),
        isExternal = v.integrity == IntegrityLabel.TAINTED,
        sensitivity = when (v.confidentiality) {
            ConfidentialityLabel.PUBLIC -> VarSensitivity.PUBLIC
            ConfidentialityLabel.PRIVATE -> VarSensitivity.PRIVATE
            ConfidentialityLabel.SECRET -> VarSensitivity.SECRET
        },
    )

    /** Da dove nasce il valore, reso dai tipi (nessun valore runtime, §5.1). */
    private fun varSourceText(v: VarBinding, l: RenderLanguage): String = when (v) {
        is VarBinding.TriggerPayload -> {
            val field = when (v.field) {
                TriggerField.TEXT -> l.pick("text field", "campo testo")
                TriggerField.TITLE -> l.pick("title field", "campo titolo")
                TriggerField.SENDER -> l.pick("sender field", "campo mittente")
                TriggerField.NUMBER -> l.pick("number field", "campo numero")
            }
            // La regex è il criterio reale di estrazione: resta visibile integrale (§5).
            field + (v.extractionRegex?.let { l.pick(" · extraction: $it", " · estrazione: $it") } ?: "")
        }
        is VarBinding.State -> "${queryLabel(v.query, l)} [${valueTypeLabel(v.valueType, l)}]"
        is VarBinding.RandomInt -> l.pick(
            "random number from 0 to ${v.max - 1}",
            "numero casuale da 0 a ${v.max - 1}",
        )
        is VarBinding.Literal -> l.pick("fixed value", "valore fisso")
        // 0.5.0: ora allo scatto. Fuso e formato sono quelli approvati, mostrati interi.
        is VarBinding.ClockTime -> {
            val format = v.pattern ?: ClockVariable.DEFAULT_PATTERN
            val zone = v.tz
            if (zone == null) {
                l.pick(
                    "current time on the phone, format $format",
                    "ora attuale del telefono, formato $format",
                )
            } else {
                l.pick(
                    "current time in $zone, format $format",
                    "ora attuale in $zone, formato $format",
                )
            }
        }
    }

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
        ValueProvenance.CREDENTIAL -> l.pick("credential", "credenziale")
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
        is Trigger.Screen, is Trigger.Battery, is Trigger.AudioOutput, is Trigger.ModeChanged,
        is Trigger.Media,
        -> DeviceTriggerRender.iconKey(t)
        is Trigger.Manual -> "manual"
        is Trigger.AppEvent -> AppEventTriggerRender.iconKey(t)
    }

    private fun triggerLine(t: Trigger, conversationLabels: Map<String, String>, l: RenderLanguage): String = when (t) {
        is Trigger.Notification -> notificationLine(t, conversationLabels, l)
        is Trigger.Time -> timeLine(t, l)
        is Trigger.Immediate -> l.pick("Once, on activation", "Una volta, all'attivazione")
        is Trigger.Geofence -> geofenceLine(t, l)
        is Trigger.PhoneState -> phoneStateLine(t, l)
        is Trigger.Connectivity -> connectivityLine(t, l)
        is Trigger.Sensor -> sensorLine(t, l)
        is Trigger.Screen, is Trigger.Battery, is Trigger.AudioOutput, is Trigger.ModeChanged,
        is Trigger.Media,
        -> DeviceTriggerRender.line(t, l)
        is Trigger.Manual -> whenPrefix(l) + l.pick(
            "you run it by hand (Run now, a Home-screen shortcut, a quick-settings tile or the widget)",
            "la lanci tu a mano (Esegui ora, una scorciatoia sulla Home, un riquadro rapido o il widget)",
        )
        is Trigger.AppEvent -> AppEventTriggerRender.line(t, l)
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
            // Con sender E conversationId il matcher usa SOLO l'id (precedenza esclusiva): senza
            // label fidata l'id reale va mostrato integrale accanto al nome proposto (S1).
            val id = if (t.sender != null) {
                t.conversationId?.let { l.pick(" · conversation id $it", " · id conversazione $it") } ?: ""
            } else {
                ""
            }
            l.pick(
                "$prefix$app notification from $who ($chat)$id",
                "${prefix}notifica $app da $who ($chat)$id",
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
        val solar = t.solar
        val line = when {
            cron != null -> humanizeCron(cron, t.tz, l)
            at != null -> l.pick(
                "Once on ${at.replace("T", " at ")} (${t.tz})",
                "Una volta il ${at.replace("T", " alle ")} (${t.tz})",
            )
            // Ritardo relativo one-shot (ri-armabile): nessun orario assoluto da mostrare, solo la
            // durata umanizzata (l'ancora reale è congelata a runtime nel ScheduledTimeAlarm).
            afterMs != null -> l.pick(
                "Once, in ${humanizeDuration(afterMs, l)}",
                "Una volta, tra ${humanizeDuration(afterMs, l)}",
            )
            solar != null -> SolarTriggerRender.line(solar, l)
            else -> return l.pick("Time not specified (${t.tz})", "Orario non specificato (${t.tz})")
        }
        // S1: la puntualità cambia l'esecuzione e va dichiarata. B14: FLEXIBLE (il default) è una sveglia
        // inesatta che Android può ritardare: non si legge come un orario puntuale.
        return line + when (t.precision) {
            TimePrecision.EXACT -> l.pick(" · exact time", " · orario esatto")
            TimePrecision.FLEXIBLE -> l.pick(" · about (Android may delay it)", " · circa (Android può ritardarlo)")
        }
    }

    /**
     * Umanizza una durata con TUTTE le unità non nulle, dalla più grande ai millisecondi (B14: «1 giorno e
     * 30 minuti», mai «1 giorno»), con singolare e plurale per lingua: 5_400_000 → «1 ora e 30 minuti»,
     * 90_061_000 → «1 giorno, 1 ora, 1 minuto e 1 secondo». Zero → «0 secondi».
     */
    internal fun humanizeDuration(ms: Long, l: RenderLanguage): String {
        val totalSeconds = ms / 1_000
        val units = listOf(
            totalSeconds / 86_400 to (l.pick("day", "giorno") to l.pick("days", "giorni")),
            (totalSeconds % 86_400) / 3_600 to (l.pick("hour", "ora") to l.pick("hours", "ore")),
            (totalSeconds % 3_600) / 60 to (l.pick("minute", "minuto") to l.pick("minutes", "minuti")),
            totalSeconds % 60 to (l.pick("second", "secondo") to l.pick("seconds", "secondi")),
            ms % 1_000 to (l.pick("millisecond", "millisecondo") to l.pick("milliseconds", "millisecondi")),
        )
        val parts = units
            .filter { (value, _) -> value != 0L }
            .map { (value, names) -> "$value ${if (value == 1L) names.first else names.second}" }
        return when (parts.size) {
            0 -> l.pick("0 seconds", "0 secondi")
            1 -> parts.single()
            else -> parts.dropLast(1).joinToString(", ") + l.pick(" and ", " e ") + parts.last()
        }
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
            PhoneEvent.MISSED_CALL -> l.pick("missed call", "chiamata persa")
        }
        val from = t.number?.let { l.pick("from $it", "da $it") } ?: l.pick("from anyone", "da chiunque")
        // §5: i filtri che restringono il match restano visibili integrali in review.
        val text = t.textMatch?.let { l.pick(" · text \"$it\"", " · testo \"$it\"") } ?: ""
        val sim = t.sim?.let { " · SIM $it" } ?: ""
        return "${whenPrefix(l)}$event $from$text$sim"
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

    /**
     * Come si apre una riga di condizione: [ONLY_IF] per un cancello valutato una volta (condizioni della
     * regola, `if`), [WHILE] per la condizione di un `while`, rivalutata prima di ogni giro (S8).
     */
    private enum class ConditionLead { ONLY_IF, WHILE }

    private fun leadText(lead: ConditionLead, l: RenderLanguage): String = when (lead) {
        ConditionLead.ONLY_IF -> l.pick("Only if", "Solo se")
        ConditionLead.WHILE -> l.pick("While", "Finché")
    }

    private fun flattenConditions(
        c: Condition,
        depth: Int,
        l: RenderLanguage,
        lead: ConditionLead = ConditionLead.ONLY_IF,
    ): List<String> {
        val pad = "  ".repeat(depth)
        val first = leadText(lead, l)
        return when (c) {
            is Condition.TimeWindow -> listOf(
                when (lead) {
                    ConditionLead.ONLY_IF -> l.pick(
                        "${pad}Only between ${c.startLocal} and ${c.endLocal} (${c.tz})",
                        "${pad}Solo tra le ${c.startLocal} e le ${c.endLocal} (${c.tz})",
                    )
                    ConditionLead.WHILE -> l.pick(
                        "${pad}While it is between ${c.startLocal} and ${c.endLocal} (${c.tz})",
                        "${pad}Finché è tra le ${c.startLocal} e le ${c.endLocal} (${c.tz})",
                    )
                },
            )
            is Condition.StateEquals -> listOf(
                "$pad$first ${c.key} ${opLabel(c.op, l)} ${c.value}",
            )
            is Condition.StateCompare -> listOf(
                "$pad$first ${queryLabel(c.query, l)} [${valueTypeLabel(c.valueType, l)}] " +
                    "${opLabel(c.op, l)} ${c.expected}",
            )
            is Condition.AppInForeground -> listOf(
                l.pick(
                    "$pad$first ${appLabel(c.pkg)} is in the foreground",
                    "$pad$first ${appLabel(c.pkg)} è in primo piano",
                ),
            )
            is Condition.LocationIn -> listOf(
                l.pick(
                    "$pad$first you are within ${fmtNum(c.radiusM)} m of ${c.lat},${c.lng}",
                    "$pad$first ti trovi entro ${fmtNum(c.radiusM)} m da ${c.lat},${c.lng}",
                ),
            )
            is Condition.And ->
                listOf(l.pick("${pad}All of the following:", "${pad}Tutte le seguenti:")) +
                    c.all.flatMap { flattenConditions(it, depth + 1, l, lead) }
            is Condition.Or ->
                listOf(l.pick("${pad}At least one of the following:", "${pad}Almeno una delle seguenti:")) +
                    c.any.flatMap { flattenConditions(it, depth + 1, l, lead) }
            is Condition.Not ->
                listOf(l.pick("${pad}Must not hold:", "${pad}Non deve valere:")) +
                    flattenConditions(c.cond, depth + 1, l, lead)
            is Condition.BooleanLiteral ->
                listOf("$pad${if (c.value) l.pick("Always", "Sempre") else l.pick("Never", "Mai")}")
            // P4: confronto su variabile di programma. La destra è un'altra variabile (${nome}) o un
            // letterale. Rendering minimale a una riga (l'espansione ricca dei badge provenienza è P4-E).
            is Condition.VarCompare -> {
                // IS_EVEN/IS_ODD sono UNARI: nessun operando destro da rendere.
                val unary = c.op == CmpOp.IS_EVEN || c.op == CmpOp.IS_ODD
                // S8: il letterale va tra virgolette (un valore vuoto o di soli spazi resta visibile) e su
                // una riga sola (un a capo diventa ⏎ e non imita una riga di condizione).
                val literal = c.expected?.let { "\"${VisibleText.singleLine(it)}\"" }
                val right = if (unary) "" else " " + (c.expectedVar?.let { "\${$it}" } ?: literal ?: "<?>")
                listOf("$pad$first \${${c.varName}} ${opLabel(c.op, l)}$right")
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
            label = l.pick("Set ringer mode: ", "Imposta la suoneria: ") + ringerModeLabel(a.mode, l),
        )
        is Action.LaunchApp -> row(
            iconKey = "launch_app",
            label = l.pick("Open app", "Apri app"),
            detail = appLabel(a.pkg),
        )
        is Action.OpenUrl -> row(
            iconKey = "open_url",
            label = l.pick("Open URL", "Apri URL"),
            // S8: l'host che il link apre DAVVERO, su una riga sua (`https://banca.it@altro.example/` apre altro.example).
            detail = a.url + (urlHostLine(a.url, l)?.let { "\n$it" } ?: ""),
        )
        is Action.ShowNotification -> row(
            iconKey = "notify",
            label = l.pick("Show notification", "Mostra notifica"),
            // S1: titolo E testo, integrali (il titolo prima non compariva in review).
            detail = if (a.text.isEmpty()) {
                l.pick("Title: ${a.title}", "Titolo: ${a.title}")
            } else {
                l.pick(
                    "Title: ${a.title} · Text: ${a.text}",
                    "Titolo: ${a.title} · Testo: ${a.text}",
                )
            },
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
            // S8: un a capo nel testo da digitare si vede come ⏎.
            detail = VisibleText.singleLine(a.text),
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
                (a.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                clockUiSuffix(a.skipUi, l),
        )
        is Action.SetTimer -> row(
            iconKey = "timer",
            label = l.pick("Start timer", "Avvia timer"),
            detail = humanizeDuration(a.seconds * 1_000L, l) +
                (a.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                clockUiSuffix(a.skipUi, l),
        )
        is Action.SetVolume -> row(
            iconKey = "volume",
            label = l.pick("Set volume", "Imposta volume"),
            // B14: il livello è una percentuale del massimo dello stream.
            detail = "${volumeStreamLabel(a.stream, l)} · ${a.level}%",
        )
        is Action.SetFlashlight -> row(
            iconKey = "flashlight",
            label = if (a.on) l.pick("Turn on flashlight", "Accendi torcia")
            else l.pick("Turn off flashlight", "Spegni torcia"),
            detail = if (a.on) l.pick("on", "accesa") else l.pick("off", "spenta"),
        )
        is Action.SetDarkMode -> row(
            iconKey = "settings",
            label = l.pick("Set dark mode", "Imposta modalità scura"),
            detail = when (a.mode) {
                NightMode.OFF -> l.pick("off", "disattivata")
                NightMode.ON -> l.pick("on", "attivata")
                NightMode.AUTO -> l.pick("automatic", "automatica")
            },
        )
        is Action.OpenSettingsScreen -> row(
            iconKey = "settings",
            label = l.pick("Open Settings", "Apri Impostazioni"),
            detail = settingsScreenLabel(a.screen, l) +
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
                GenerativeDeliverMode.CAPTURE_ONLY ->
                    l.pick("Generate a value", "Genera un valore")
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
                GenerativeDeliverMode.CAPTURE_ONLY -> l.pick(
                    "Internal value · Goal: ${a.goal} · tools: ${a.allowedTools.joinToString(", ")}",
                    "Valore interno · Obiettivo: ${a.goal} · tool: ${a.allowedTools.joinToString(", ")}",
                )
            } + contextSuffix(a.contextSources, l) + replyBindingSuffix(a.replyTargetSender, l) +
                captureSuffix(a.captureAs, l),
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
                            "${integrityLabel(context.integrity, l)}, ${confidentialityLabel(context.confidentiality, l)}]"
                    },
                )
                append(l.pick(" · tools: ", " · tool: "))
                append(a.allowedTools.joinToString(", "))
                append(replyBindingSuffix(a.replyTargetSender, l))
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
        // 0.4.1 (azioni): resa in ActionRows041.
        is Action.Speak -> ActionRows041.speak(a, l)
        is Action.MediaControl -> ActionRows041.mediaControl(a, l)
        is Action.SetBrightness -> ActionRows041.setBrightness(a, l)
        is Action.SetRotation -> ActionRows041.setRotation(a, l)
        is Action.HttpRequest -> ActionRows041.httpRequest(a, l)
        // 0.5.1 (azioni privilegiate tipizzate): resa in ActionRows051.
        is Action.AppControl -> ActionRows051.appControl(a, l)
        is Action.SetAirplane -> ActionRows051.setAirplane(a, l)
        is Action.SetLocation -> ActionRows051.setLocation(a, l)
        is Action.SetNfc -> ActionRows051.setNfc(a, l)
        is Action.SetBatterySaver -> ActionRows051.setBatterySaver(a, l)
        is Action.LockScreen -> ActionRows051.lockScreen(l)
        is Action.Screenshot -> ActionRows051.screenshot(l)
        is Action.AppPermission -> ActionRows051.appPermission(a, l)
        // 0.5.1: macro sull'interfaccia, resa in ActionRowsUiSteps.
        is Action.UiSteps -> ActionRowsUiSteps.uiSteps(a, l)
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

    /** `skipUi=false` apre l'app Orologio in primo piano: cambia l'esecuzione, quindi si dichiara. */
    private fun clockUiSuffix(skipUi: Boolean, l: RenderLanguage): String =
        if (skipUi) "" else l.pick(" · opens the clock app", " · apre l'app Orologio")

    // captureAs: dove l'azione salva il proprio output (P4). Il produttore è l'azione stessa.
    /** Standalone (RunShell, che non ha altro detail). Null se non cattura. */
    private fun captureLabel(captureAs: String?, l: RenderLanguage): String? =
        captureAs?.let { l.pick("captured as $it", "catturato come $it") }

    /** S1/S2: le fonti di contesto che l'azione generativa invia al servizio AI («contesto: …»). */
    private fun contextSuffix(contextSources: List<String>, l: RenderLanguage): String =
        if (contextSources.isEmpty()) {
            ""
        } else {
            val joined = contextSources.joinToString(", ") { contextSourceLabel(it, l) }
            l.pick(" · context: $joined", " · contesto: $joined")
        }

    /** B14: le fonti note hanno un nome nella lingua dell'utente; una sconosciuta resta com'è scritta. */
    private fun contextSourceLabel(source: String, l: RenderLanguage): String = when (source) {
        GenerativeContract.CONTEXT_NOTIFICATION -> l.pick("notification text", "testo della notifica")
        GenerativeContract.CONTEXT_STATE -> l.pick("device state", "stato del dispositivo")
        else -> source
    }

    /** B14: modalità della suoneria per nome; un valore fuori elenco (il validator lo rifiuta) resta tra virgolette. */
    private fun ringerModeLabel(mode: String, l: RenderLanguage): String = when (mode) {
        "normal" -> l.pick("sound", "suono")
        "vibrate" -> l.pick("vibrate", "vibrazione")
        "silent" -> l.pick("silent", "silenzioso")
        else -> "\"$mode\""
    }

    private fun volumeStreamLabel(stream: VolumeStream, l: RenderLanguage): String = when (stream) {
        VolumeStream.MEDIA -> l.pick("media", "contenuti multimediali")
        VolumeStream.RING -> l.pick("ringtone", "suoneria")
        VolumeStream.ALARM -> l.pick("alarm", "sveglia")
        VolumeStream.NOTIFICATION -> l.pick("notifications", "notifiche")
    }

    private fun settingsScreenLabel(screen: SettingsScreen, l: RenderLanguage): String = when (screen) {
        SettingsScreen.WIFI -> "Wi-Fi"
        SettingsScreen.BLUETOOTH -> "Bluetooth"
        SettingsScreen.DISPLAY -> l.pick("Display", "Schermo")
        SettingsScreen.SOUND -> l.pick("Sound", "Suoni")
        SettingsScreen.LOCATION -> l.pick("Location", "Posizione")
        SettingsScreen.BATTERY -> l.pick("Battery", "Batteria")
        SettingsScreen.DATE -> l.pick("Date and time", "Data e ora")
        SettingsScreen.APP_DETAILS -> l.pick("App info", "Informazioni app")
        SettingsScreen.SETTINGS -> l.pick("Main settings", "Impostazioni principali")
    }

    /**
     * S8: per un URL letterale, la riga con l'host che il link apre davvero (dopo un eventuale `utente@`),
     * con la forma ASCII se è un nome internazionalizzato (un sosia Unicode si tradisce). null se l'URL
     * contiene `${…}` (l'host si decide allo scatto).
     */
    internal fun urlHostLine(url: String, l: RenderLanguage): String? {
        if (InterpolationPolicy.containsInterpolation(url)) return null
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull()
        // `URI.host` è null per un host non ASCII: lo si ricava dall'autorità, dopo l'ultima `@`.
        val host = (
            uri?.host ?: uri?.rawAuthority?.substringAfterLast('@')?.let { authority ->
                if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
            }
            )?.takeIf { it.isNotBlank() }
            ?: return l.pick("Opens on: host not recognised", "Si apre su: host non riconosciuto")
        val ascii = runCatching { java.net.IDN.toASCII(host) }.getOrNull()
        val shown = if (ascii != null && !ascii.equals(host, ignoreCase = true)) "$host ($ascii)" else host
        return l.pick("Opens on: $shown", "Si apre su: $shown")
    }

    /** S1: la risposta è vincolata al mittente del trigger (non a un destinatario libero). */
    private fun replyBindingSuffix(replyTargetSender: Boolean, l: RenderLanguage): String =
        if (replyTargetSender) l.pick(" · reply bound to the sender", " · risposta vincolata al mittente") else ""

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
        return l.pick("Cron schedule '$expr' ($tz)", "Pianificazione cron '$expr' ($tz)")
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
        is StateQuery.Setting -> l.pick(
            "setting ${query.namespace.name.lowercase()}:${query.key}",
            "impostazione ${query.namespace.name.lowercase()}:${query.key}",
        )
        is StateQuery.SystemProperty -> l.pick("system property ${query.name}", "proprietà di sistema ${query.name}")
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
