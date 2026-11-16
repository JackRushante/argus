package dev.argus.automation.vm

import dev.argus.engine.model.Action
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfirmSpec
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Day
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.IntentExtra
import dev.argus.engine.model.IntentExtraType
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.MediaCommand
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.ScrollDirection
import dev.argus.engine.model.SecretRef
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.SolarEvent
import dev.argus.engine.model.SolarSpec
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.UiStep
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VolumeStream
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleRender
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.RuleRenderMapper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1 · test di copertura dell'approvazione.
 *
 * La review DEVE mostrare tutto ciò che la regola esegue (invariante 4: resa dai tipi). Per ogni
 * sottotipo concreto di Action, Trigger, Condition, VarBinding (e StateQuery) il test costruisce
 * istanze con valori riconoscibili (`XYZ-…`) e verifica che ogni campo serializzato compaia nel
 * [RuleRender] prodotto, in inglese e in italiano. Un campo che non compare va reso oppure
 * dichiarato in `notRendered` con la ragione.
 *
 * I sottotipi e i campi si ricavano dai SerialDescriptor di kotlinx.serialization, non da una
 * lista scritta a mano: aggiungere un sottotipo (o un campo) senza aggiornare questo test lo fa
 * fallire, quindi nessun comportamento nuovo può arrivare in approvazione senza essere reso.
 */
@OptIn(ExperimentalSerializationApi::class)
class RuleRenderCoverageTest {

    /** Frammento atteso nel render; `it` differisce da `en` solo se il testo dipende dalla lingua. */
    private data class Marker(val en: String, val it: String = en)

    private fun m(text: String) = Marker(text)
    private fun m(en: String, it: String) = Marker(en, it)

    private class Variant<out T>(val example: T, val rendered: Map<String, List<Marker>>)

    private class Case<out T>(
        val variants: List<Variant<T>>,
        /** Campo serializzato -> perché non serve mostrarlo (nessun effetto sull'esecuzione). */
        val notRendered: Map<String, String> = emptyMap(),
    )

    private fun <T> case(
        example: T,
        rendered: Map<String, List<Marker>>,
        notRendered: Map<String, String> = emptyMap(),
    ) = Case(listOf(Variant(example, rendered)), notRendered)

    private fun <T> case(
        vararg variants: Variant<T>,
        notRendered: Map<String, String> = emptyMap(),
    ) = Case(variants.toList(), notRendered)

    private fun <T> v(example: T, vararg rendered: Pair<String, List<Marker>>) =
        Variant(example, rendered.toMap())

    private val none: List<Marker> = emptyList()
    private fun ms(vararg markers: Marker) = markers.toList()
    private fun ms(vararg texts: String) = texts.map(::Marker)

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    private val languages = listOf(RenderLanguage.EN, RenderLanguage.IT)
    private val immediate = Trigger.Immediate
    private val noopAction = Action.SetWifi(true)
    private val notificationTrigger = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)

    private fun subtypeDescriptors(root: KSerializer<*>): Map<String, SerialDescriptor> =
        root.descriptor.getElementDescriptor(1).elementDescriptors.associateBy { it.serialName }

    private fun automation(
        trigger: Trigger = immediate,
        actions: List<Action> = listOf(noopAction),
        conditions: Condition? = null,
        vars: List<VarBinding> = emptyList(),
    ) = Automation(
        id = AutomationId("cov"),
        name = "coverage",
        createdBy = CreatedBy.LLM,
        status = AutomationStatus.PENDING_APPROVAL,
        trigger = trigger,
        actions = actions,
        vars = vars,
        conditions = conditions,
    )

    private fun flatten(render: RuleRender): String {
        val out = mutableListOf<String?>()
        out += render.triggerLine
        out += render.conditionLines
        render.actions.forEach { row ->
            out += row.label
            out += row.detail
            out += row.shellCommand
        }
        out += render.privacyNote
        render.vars.forEach { row ->
            out += listOf(
                row.name, row.typeLabel, row.integrityLabel, row.confidentialityLabel,
                row.provenanceLabel, row.valueText, row.sourceText,
            )
        }
        fun node(n: ProgramNode) {
            when (n) {
                is ProgramNode.Leaf -> {
                    out += n.row.label
                    out += n.row.detail
                    out += n.row.shellCommand
                }
                is ProgramNode.IfNode -> {
                    out += n.title
                    out += n.conditionLines
                    out += n.thenTitle
                    n.then.forEach(::node)
                    out += n.elseTitle
                    n.orElse.forEach(::node)
                }
                is ProgramNode.WhileNode -> {
                    out += n.title
                    out += n.conditionLines
                    n.body.forEach(::node)
                }
            }
        }
        render.program.forEach(::node)
        // 0.8: conferma prima di eseguire e fine della regola-intervallo con le sue azioni.
        out += render.confirmLine
        out += render.exitLine
        render.exitProgram.forEach(::node)
        return out.filterNotNull().joinToString("\n")
    }

    /**
     * Verifica una gerarchia: stessi sottotipi dei descriptor, stessi campi per sottotipo, e ogni
     * marker presente nel render prodotto da [wrap] (in EN e IT).
     */
    private fun <T> check(
        hierarchy: String,
        root: KSerializer<*>,
        cases: Map<String, Case<T>>,
        wrap: (T) -> Automation,
    ) {
        val descriptors = subtypeDescriptors(root)
        assertEquals(
            descriptors.keys,
            cases.keys,
            "$hierarchy: sottotipi nuovi o rimossi, aggiorna la copertura di approvazione",
        )
        cases.forEach { (name, case) ->
            val declared = descriptors.getValue(name).elementNames.toSet()
            val covered = case.variants.flatMap { it.rendered.keys } + case.notRendered.keys
            assertEquals(
                declared,
                covered.toSet(),
                "$hierarchy/$name: campi nuovi o rimossi, rendili in approvazione o motiva perché no",
            )
            assertTrue(
                case.notRendered.keys.none { key -> case.variants.any { key in it.rendered } },
                "$hierarchy/$name: un campo non può essere insieme reso e non reso",
            )
            case.variants.forEach { variant ->
                languages.forEach { language ->
                    val text = flatten(RuleRenderMapper.map(wrap(variant.example), language = language))
                    variant.rendered.forEach { (field, markers) ->
                        markers.forEach { marker ->
                            val expected = if (language == RenderLanguage.IT) marker.it else marker.en
                            assertTrue(
                                expected in text,
                                "$hierarchy/$name.$field: «$expected» assente dal render $language:\n$text",
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Trigger
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every trigger field that changes execution is rendered`() {
        val cases: Map<String, Case<Trigger>> = mapOf(
            "geofence" to case(
                v(
                    Trigger.Geofence(
                        lat = 41.12345, lng = 12.54321, radiusM = 777.0,
                        transition = Transition.EXIT, resolveCurrentLocation = false,
                    ),
                    "lat" to ms("41.12345"),
                    "lng" to ms("12.54321"),
                    "radiusM" to ms("777"),
                    "transition" to ms(m("leave", "esci")),
                    "resolveCurrentLocation" to none,
                ),
                v(
                    Trigger.Geofence(
                        radiusM = 55.0, transition = Transition.ENTER, resolveCurrentLocation = true,
                    ),
                    "resolveCurrentLocation" to ms(m("current location", "posizione attuale")),
                ),
                notRendered = mapOf(
                    "loiteringDelayMs" to "il validator rifiuta ogni valore diverso da 0 (serve DWELL, non supportato)",
                ),
            ),
            "time" to case(
                v(
                    Trigger.Time(cron = "*/7 9-17 * * 1-5", tz = "XYZ/Zone", precision = TimePrecision.EXACT),
                    "cron" to ms("*/7 9-17 * * 1-5"),
                    "tz" to ms("XYZ/Zone"),
                    "precision" to ms(m("exact time", "orario esatto")),
                ),
                v(
                    Trigger.Time(at = "2031-05-06T07:08", tz = "UTC"),
                    "at" to ms(m("2031-05-06 at 07:08", "2031-05-06 alle 07:08")),
                ),
                v(
                    Trigger.Time(afterMs = 7_200_000, tz = "UTC"),
                    "afterMs" to ms(m("2 hours", "2 ore")),
                ),
                v(
                    Trigger.Time(tz = "UTC", solar = SolarSpec(SolarEvent.SUNSET, -45)),
                    "solar" to ms(
                        m("45 min before sunset", "45 min prima del tramonto"),
                        m("at the phone's position", "alla posizione del telefono"),
                    ),
                ),
                v(
                    Trigger.Time(tz = "UTC", solar = SolarSpec(SolarEvent.SUNRISE, 90, lat = 41.9, lng = 12.5)),
                    "solar" to ms(
                        m("1 h 30 min after sunrise", "1 h 30 min dopo l'alba"),
                        m("near 41.9, 12.5", "vicino a 41.9, 12.5"),
                    ),
                ),
            ),
            "immediate" to case(Trigger.Immediate, emptyMap()),
            "notification" to case(
                Trigger.Notification(
                    pkg = "com.xyz.pkg",
                    conversationId = "XYZ-conv",
                    sender = "XYZ-sender",
                    isGroup = true,
                    titleMatch = "XYZ-title",
                    textMatch = "XYZ-text",
                ),
                mapOf(
                    "pkg" to ms("com.xyz.pkg"),
                    "conversationId" to ms("XYZ-conv"),
                    "sender" to ms("XYZ-sender"),
                    "isGroup" to ms(m("group", "gruppo")),
                    "titleMatch" to ms("XYZ-title"),
                    "textMatch" to ms("XYZ-text"),
                ),
            ),
            "phone_state" to case(
                v(
                    Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, number = "+39XYZ", textMatch = "XYZ-sms"),
                    "event" to ms(m("SMS received", "SMS ricevuto")),
                    "number" to ms("+39XYZ"),
                    "textMatch" to ms("XYZ-sms"),
                ),
                v(
                    Trigger.PhoneState(PhoneEvent.MISSED_CALL, sim = 2),
                    "event" to ms(m("missed call", "chiamata persa")),
                    "sim" to ms("SIM 2"),
                ),
            ),
            "connectivity" to case(
                Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED, match = "XYZ-device"),
                mapOf(
                    "medium" to ms("Bluetooth"),
                    "state" to ms(m("disconnected", "disconnesso")),
                    "match" to ms("XYZ-device"),
                ),
            ),
            "sensor" to case(
                v(
                    Trigger.Sensor(SensorKind.STEP_COUNTER, minimumEventCount = 317),
                    "kind" to ms(m("step counter", "contatore")),
                    "minimumEventCount" to ms("317"),
                ),
                // 0.8: gesti one-shot (spenti finché il loro FeatureGate è off).
                v(Trigger.Sensor(SensorKind.SHAKE), "kind" to ms(m("you shake the phone", "scuoti il telefono"))),
                v(
                    Trigger.Sensor(SensorKind.FLIP_FACE_DOWN),
                    "kind" to ms(m("you turn the phone face down", "giri il telefono a faccia in giù")),
                ),
                notRendered = mapOf(
                    "samplingPeriodUs" to "il validator rifiuta ogni valore non nullo (sampling continuo non supportato)",
                    "maxReportLatencyUs" to "il validator rifiuta ogni valore non nullo (sampling batched non supportato)",
                ),
            ),
            // --- 0.4.1: trigger di sistema senza Shizuku ---
            "screen" to case(
                Trigger.Screen(ScreenEvent.UNLOCKED),
                mapOf("event" to ms(m("the phone is unlocked", "il telefono viene sbloccato"))),
            ),
            "battery" to case(
                v(
                    Trigger.Battery(below = 17),
                    "below" to ms(m("drops below 17%", "scende sotto il 17%")),
                ),
                v(
                    Trigger.Battery(above = 83),
                    "above" to ms(m("rises above 83%", "sale sopra il 83%")),
                ),
            ),
            "audio_output" to case(
                Trigger.AudioOutput(AudioOutputKind.BLUETOOTH, ConnState.DISCONNECTED),
                mapOf(
                    "kind" to ms(m("Bluetooth audio output", "uscita audio Bluetooth")),
                    "state" to ms(m("disconnected", "scollegata")),
                ),
            ),
            "mode_changed" to case(
                Trigger.ModeChanged(ModeKind.DND, "priority"),
                mapOf(
                    "what" to ms(m("Do Not Disturb", "Non disturbare")),
                    "to" to ms(m("priority only", "solo priorità")),
                ),
            ),
            "media" to case(
                Trigger.Media(MediaPlayState.STOPPED),
                mapOf("state" to ms(m("media stops playing", "finisce la riproduzione multimediale"))),
            ),
            // 0.4.1: avvio manuale, nessun campo da rendere.
            "manual" to case(Trigger.Manual, emptyMap()),
            // 0.5.1: app aperta/chiusa. Il package compare per intero, la direzione dice anche cosa significa.
            "app_event" to case(
                v(
                    Trigger.AppEvent("org.example.xyzapp", AppEventKind.OPENED),
                    "pkg" to ms("org.example.xyzapp"),
                    "event" to ms(m("is opened", "viene aperta")),
                ),
                v(
                    Trigger.AppEvent("org.example.xyzapp", AppEventKind.CLOSED),
                    "pkg" to ms("org.example.xyzapp"),
                    "event" to ms(m("is closed", "viene chiusa")),
                ),
            ),
            // --- 0.8 (spenti finché il loro FeatureGate è off, ma resi per intero) ---
            "calendar_event" to case(
                v(
                    Trigger.CalendarEvent(CalendarPhase.START, -15, titleMatch = "XYZ-title", calendar = "XYZ-cal"),
                    "phase" to ms(m("before a calendar event starts", "prima che inizi un evento del calendario")),
                    "offsetMinutes" to ms(m("15 minutes", "15 minuti")),
                    "titleMatch" to ms("XYZ-title"),
                    "calendar" to ms("XYZ-cal"),
                ),
                v(
                    Trigger.CalendarEvent(CalendarPhase.END, 20),
                    "phase" to ms(m("after a calendar event ends", "dopo la fine di un evento del calendario")),
                    "offsetMinutes" to ms(m("20 minutes", "20 minuti")),
                ),
            ),
            "nfc_tag" to case(
                v(Trigger.NfcTag("XYZ-tag"), "tagId" to ms("XYZ-tag"), "resolveTag" to none),
                v(
                    Trigger.NfcTag(resolveTag = true),
                    "resolveTag" to ms(m("read when you turn on the rule", "letto quando attivi la regola")),
                ),
            ),
            "next_alarm" to case(
                Trigger.NextAlarm(25),
                mapOf("offsetMinutes" to ms(m("25 minutes before your next alarm", "25 minuti prima della prossima sveglia"))),
            ),
            "app_usage" to case(
                Trigger.AppUsage("org.example.xyzapp", 37),
                mapOf("pkg" to ms("org.example.xyzapp"), "minutes" to ms(m("37 minutes", "37 minuti"))),
            ),
            "user_mode_changed" to case(
                v(Trigger.UserModeChanged("XYZ-mode"), "to" to ms("XYZ-mode")),
                v(Trigger.UserModeChanged(), "to" to ms(m("the user mode changes", "cambia la modalità utente"))),
            ),
        )
        check("Trigger", Trigger.serializer(), cases) { trigger -> automation(trigger = trigger) }
    }

    // ---------------------------------------------------------------------------------------------
    // Condition
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every condition field that changes execution is rendered`() {
        val statePolicyVersion = StateQueryPolicy.VERSION
        val cases: Map<String, Case<Condition>> = mapOf(
            "time_window" to case(
                Condition.TimeWindow("04:11", "05:22", "XYZ/Zone"),
                mapOf(
                    "startLocal" to ms("04:11"),
                    "endLocal" to ms("05:22"),
                    "tz" to ms("XYZ/Zone"),
                ),
            ),
            "state_equals" to case(
                Condition.StateEquals("XYZ-key", CmpOp.NEQ, "XYZ-val"),
                mapOf(
                    "key" to ms("XYZ-key"),
                    "op" to ms("≠"),
                    "value" to ms("XYZ-val"),
                ),
            ),
            "state_compare" to case(
                Condition.StateCompare(
                    StateQuery.Builtin("XYZ-bq"), StateValueType.NUMBER, CmpOp.GT, "4242", statePolicyVersion,
                ),
                mapOf(
                    "query" to ms("XYZ-bq"),
                    "valueType" to ms(m("number", "numero")),
                    "op" to ms(">"),
                    "expected" to ms("4242"),
                ),
                notRendered = mapOf(
                    "policyVersion" to "versione della policy dei reader: fissata e verificata dal validator, nessun effetto sul comportamento visibile",
                ),
            ),
            "app_in_foreground" to case(
                Condition.AppInForeground("com.xyz.fg"),
                mapOf("pkg" to ms("com.xyz.fg")),
            ),
            "location_in" to case(
                Condition.LocationIn(41.5, 12.5, 333.0),
                mapOf(
                    "lat" to ms("41.5"),
                    "lng" to ms("12.5"),
                    "radiusM" to ms("333"),
                ),
            ),
            "and" to case(
                Condition.And(listOf(Condition.AppInForeground("com.xyz.and"))),
                mapOf("all" to ms("com.xyz.and")),
            ),
            "or" to case(
                Condition.Or(listOf(Condition.AppInForeground("com.xyz.or"))),
                mapOf("any" to ms("com.xyz.or")),
            ),
            "not" to case(
                Condition.Not(Condition.AppInForeground("com.xyz.not")),
                mapOf("cond" to ms("com.xyz.not")),
            ),
            "boolean_literal" to case(
                v(Condition.BooleanLiteral(true), "value" to ms(m("Always", "Sempre"))),
                v(Condition.BooleanLiteral(false), "value" to ms(m("Never", "Mai"))),
            ),
            "var_compare" to case(
                v(
                    Condition.VarCompare("xyz_var", CmpOp.CONTAINS, expected = "XYZ-exp"),
                    "varName" to ms("xyz_var"),
                    "op" to ms(m("contains", "contiene")),
                    "expected" to ms("XYZ-exp"),
                    "expectedVar" to none,
                ),
                v(
                    Condition.VarCompare("xyz_var", CmpOp.EQ, expectedVar = "xyz_rhs"),
                    "expectedVar" to ms("xyz_rhs"),
                ),
            ),
            // --- 0.8 (spente finché il loro FeatureGate è off, ma rese per intero) ---
            "weekday" to case(
                Condition.Weekday(setOf(Day.MON, Day.SAT), "XYZ/Zone"),
                mapOf(
                    "days" to ms(m("Monday", "lunedì"), m("Saturday", "sabato")),
                    "tz" to ms("XYZ/Zone"),
                ),
            ),
            "wifi_connected" to case(
                v(Condition.WifiConnected("XYZ-ssid"), "ssid" to ms("XYZ-ssid")),
                v(Condition.WifiConnected(), "ssid" to ms(m("any Wi-Fi network", "una rete Wi-Fi qualsiasi"))),
            ),
            "bluetooth_connected" to case(
                v(Condition.BluetoothConnected("XYZ-device"), "device" to ms("XYZ-device")),
                v(
                    Condition.BluetoothConnected(),
                    "device" to ms(m("any Bluetooth device", "un dispositivo Bluetooth qualsiasi")),
                ),
            ),
            "headset_connected" to case(
                v(Condition.HeadsetConnected(AudioOutputKind.WIRED), "kind" to ms(m("wired headphones", "cuffie con il filo"))),
                v(Condition.HeadsetConnected(AudioOutputKind.BLUETOOTH), "kind" to ms(m("Bluetooth headphones", "cuffie Bluetooth"))),
                v(Condition.HeadsetConnected(), "kind" to ms(m("wired or Bluetooth", "con il filo o Bluetooth"))),
            ),
            "calendar_busy" to case(
                Condition.CalendarBusy(titleMatch = "XYZ-title", calendar = "XYZ-cal"),
                mapOf("titleMatch" to ms("XYZ-title"), "calendar" to ms("XYZ-cal")),
            ),
            "stored_compare" to case(
                Condition.StoredCompare("XYZ-skey", StateValueType.NUMBER, CmpOp.GT, "4242"),
                mapOf(
                    "key" to ms("XYZ-skey"),
                    "valueType" to ms(m("number", "numero")),
                    "op" to ms(">"),
                    "expected" to ms("4242"),
                ),
            ),
            "user_mode_is" to case(Condition.UserModeIs("XYZ-umode"), mapOf("mode" to ms("XYZ-umode"))),
        )
        check("Condition", Condition.serializer(), cases) { condition -> automation(conditions = condition) }
    }

    // ---------------------------------------------------------------------------------------------
    // VarBinding
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every variable binding field that changes execution is rendered`() {
        val cases: Map<String, Case<VarBinding>> = mapOf(
            "trigger_payload" to case(
                VarBinding.TriggerPayload(
                    name = "xyz_tp",
                    field = TriggerField.SENDER,
                    extractionRegex = "XYZ(re)",
                    confidentiality = ConfidentialityLabel.SECRET,
                ),
                mapOf(
                    "name" to ms("xyz_tp"),
                    "field" to ms(m("sender field", "campo mittente")),
                    "extractionRegex" to ms("XYZ(re)"),
                    "confidentiality" to ms(m("secret", "segreto")),
                ),
            ),
            "state" to case(
                VarBinding.State(
                    name = "xyz_st",
                    query = StateQuery.Setting(SettingNamespace.GLOBAL, "xyz_setting_key"),
                    valueType = StateValueType.BOOLEAN,
                    policyVersion = StateQueryPolicy.VERSION,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
                mapOf(
                    "name" to ms("xyz_st"),
                    "query" to ms("global:xyz_setting_key"),
                    "valueType" to ms(m("boolean", "booleano")),
                    "confidentiality" to ms(m("private", "privato")),
                ),
                notRendered = mapOf(
                    "policyVersion" to "versione della policy dei reader: fissata e verificata dal validator, nessun effetto sul comportamento visibile",
                ),
            ),
            "literal" to case(
                VarBinding.Literal(
                    name = "xyz_lit",
                    value = "XYZ-literal value; with 'quotes' and \$(chars)\nsecond line",
                    varType = VarType.TEXT,
                    confidentiality = ConfidentialityLabel.PUBLIC,
                ),
                mapOf(
                    "name" to ms("xyz_lit"),
                    "value" to ms("XYZ-literal value; with 'quotes' and \$(chars)\nsecond line"),
                    "varType" to ms(m("text", "testo")),
                    "confidentiality" to ms(m("public", "pubblico")),
                ),
            ),
            "random_int" to case(
                VarBinding.RandomInt(name = "xyz_rnd", max = 4242, confidentiality = ConfidentialityLabel.PUBLIC),
                mapOf(
                    "name" to ms("xyz_rnd"),
                    "max" to ms("4241"),
                    "confidentiality" to ms(m("public", "pubblico")),
                ),
            ),
            "clock" to case(
                v(
                    VarBinding.ClockTime(
                        name = "xyz_clock",
                        tz = "XYZ/Zone",
                        pattern = "XYZ-pattern",
                        confidentiality = ConfidentialityLabel.PUBLIC,
                    ),
                    "name" to ms("xyz_clock"),
                    "tz" to ms(m("current time in XYZ/Zone", "ora attuale in XYZ/Zone")),
                    "pattern" to ms(m("format XYZ-pattern", "formato XYZ-pattern")),
                    "confidentiality" to ms(m("public", "pubblico")),
                ),
                // Senza fuso e senza formato: il fuso del telefono e HH:mm sono comunque scritti.
                v(
                    VarBinding.ClockTime(name = "xyz_clock_default"),
                    "name" to ms("xyz_clock_default"),
                    "tz" to ms(m("current time on the phone", "ora attuale del telefono")),
                    "pattern" to ms(m("format HH:mm", "formato HH:mm")),
                    "confidentiality" to ms(m("public", "pubblico")),
                ),
            ),
            // 0.8: valore conservato fra le esecuzioni (spento finché il suo FeatureGate è off).
            "stored" to case(
                VarBinding.Stored(
                    name = "xyz_stored",
                    key = "XYZ-store-key",
                    valueType = StateValueType.BOOLEAN,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
                mapOf(
                    "name" to ms("xyz_stored"),
                    "key" to ms(m("stored value \"XYZ-store-key\"", "valore conservato \"XYZ-store-key\"")),
                    "valueType" to ms(m("boolean", "booleano")),
                    "confidentiality" to ms(m("private", "privato")),
                ),
            ),
        )
        check("VarBinding", VarBinding.serializer(), cases) { binding ->
            automation(trigger = notificationTrigger, vars = listOf(binding))
        }
    }

    @Test
    fun `a literal variable is shown in full while other bindings never expose a value`() {
        val literal = VarBinding.Literal("lit", "line one\nline two ${"x".repeat(500)}", VarType.TEXT, ConfidentialityLabel.PUBLIC)
        val others = listOf(
            VarBinding.TriggerPayload("tp", TriggerField.TEXT, null, ConfidentialityLabel.PRIVATE),
            VarBinding.State("st", StateQuery.Builtin("battery.level"), StateValueType.NUMBER, StateQueryPolicy.VERSION, ConfidentialityLabel.PRIVATE),
            VarBinding.RandomInt("rnd", 10),
        )
        languages.forEach { language ->
            val rows = RuleRenderMapper.map(
                automation(trigger = notificationTrigger, vars = listOf(literal) + others),
                language = language,
            ).vars
            assertEquals(literal.value, rows[0].valueText)
            assertTrue(rows.drop(1).all { it.valueText == null }, "solo i letterali mostrano un valore")
            assertTrue(rows.all { it.sourceText.isNotBlank() }, "ogni variabile dichiara da dove nasce")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // StateQuery (sotto-struttura di condizioni e variabili)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every state query parameter is rendered`() {
        val cases: Map<String, Case<StateQuery>> = mapOf(
            "builtin" to case(StateQuery.Builtin("XYZ-bk"), mapOf("key" to ms("XYZ-bk"))),
            "setting" to case(
                StateQuery.Setting(SettingNamespace.SECURE, "XYZ-sk"),
                mapOf("namespace" to ms("secure"), "key" to ms("XYZ-sk")),
            ),
            "system_property" to case(StateQuery.SystemProperty("XYZ-prop"), mapOf("name" to ms("XYZ-prop"))),
            "sysfs" to case(StateQuery.Sysfs("/sys/xyz/node"), mapOf("path" to ms("/sys/xyz/node"))),
            "dumpsys_field" to case(
                StateQuery.DumpsysField("xyzsvc", "XYZ-field"),
                mapOf("service" to ms("xyzsvc"), "field" to ms("XYZ-field")),
            ),
        )
        check("StateQuery", StateQuery.serializer(), cases) { query ->
            automation(
                conditions = Condition.StateCompare(
                    query, StateValueType.TEXT, CmpOp.EQ, "x", StateQueryPolicy.VERSION,
                ),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Action
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every action field that changes execution is rendered`() {
        val llmReply = Action.InvokeLlm(
            goal = "XYZ-goal",
            contextSources = listOf("xyz-source"),
            allowedTools = listOf("xyz.tool"),
            replyTargetSender = true,
            timeoutMs = 45_000,
        )
        val timeoutReason = "è solo un limite di tempo (già nel budget del validator), non cambia ciò che l'azione fa"
        val cases: Map<String, Case<Action>> = mapOf(
            "set_wifi" to case(
                v(Action.SetWifi(true), "on" to ms(m("Turn on Wi-Fi", "Attiva Wi-Fi"))),
                v(Action.SetWifi(false), "on" to ms(m("Turn off Wi-Fi", "Disattiva Wi-Fi"))),
            ),
            "set_bluetooth" to case(
                v(Action.SetBluetooth(true), "on" to ms(m("Turn on Bluetooth", "Attiva Bluetooth"))),
                v(Action.SetBluetooth(false), "on" to ms(m("Turn off Bluetooth", "Disattiva Bluetooth"))),
            ),
            "set_mobile_data" to case(
                v(Action.SetMobileData(true), "on" to ms(m("Turn on mobile data", "Attiva dati mobili"))),
                v(Action.SetMobileData(false), "on" to ms(m("Turn off mobile data", "Disattiva dati mobili"))),
            ),
            "set_dnd" to case(
                Action.SetDnd(DndMode.TOTAL),
                mapOf("mode" to ms(m("(total)", "(totale)"))),
            ),
            "set_ringer" to case(Action.SetRinger("XYZ-mode"), mapOf("mode" to ms("XYZ-mode"))),
            "launch_app" to case(Action.LaunchApp("com.xyz.app"), mapOf("pkg" to ms("com.xyz.app"))),
            "open_url" to case(
                Action.OpenUrl("https://xyz.example/path?q=1"),
                mapOf("url" to ms("https://xyz.example/path?q=1")),
            ),
            "show_notification" to case(
                Action.ShowNotification("XYZ-title", "XYZ-text"),
                mapOf("title" to ms("XYZ-title"), "text" to ms("XYZ-text")),
            ),
            "tap" to case(Action.Tap(7311, 4172), mapOf("x" to ms("7311"), "y" to ms("4172"))),
            "input_text" to case(Action.InputText("XYZ-input"), mapOf("text" to ms("XYZ-input"))),
            "whatsapp_reply" to case(Action.WhatsAppReply("XYZ-reply"), mapOf("text" to ms("XYZ-reply"))),
            "run_shell" to case(
                Action.RunShell("echo XYZ-cmd", captureAs = "xyz_out"),
                mapOf("cmd" to ms("echo XYZ-cmd"), "captureAs" to ms("xyz_out")),
            ),
            "copy_to_clipboard" to case(
                Action.CopyToClipboard("(XYZ\\d+)"),
                mapOf("extractionRegex" to ms("(XYZ\\d+)")),
            ),
            "copy_text" to case(Action.CopyText("XYZ-copy"), mapOf("text" to ms("XYZ-copy"))),
            "set_alarm" to case(
                Action.SetAlarm(hour = 13, minute = 47, label = "XYZ-alarm", skipUi = false),
                mapOf(
                    "hour" to ms("13:47"),
                    "minute" to ms("13:47"),
                    "label" to ms("XYZ-alarm"),
                    "skipUi" to ms(m("opens the clock app", "apre l'app Orologio")),
                ),
            ),
            "set_timer" to case(
                Action.SetTimer(seconds = 4321, label = "XYZ-timer", skipUi = false),
                mapOf(
                    "seconds" to ms(m("1 hour, 12 minutes and 1 second", "1 ora, 12 minuti e 1 secondo")),
                    "label" to ms("XYZ-timer"),
                    "skipUi" to ms(m("opens the clock app", "apre l'app Orologio")),
                ),
            ),
            "set_volume" to case(
                Action.SetVolume(VolumeStream.ALARM, 73),
                mapOf("stream" to ms(m("alarm", "sveglia")), "level" to ms("73%")),
            ),
            "set_flashlight" to case(
                v(Action.SetFlashlight(true), "on" to ms(m("Turn on flashlight", "Accendi torcia"))),
                v(Action.SetFlashlight(false), "on" to ms(m("Turn off flashlight", "Spegni torcia"))),
            ),
            "set_dark_mode" to case(Action.SetDarkMode(NightMode.AUTO), mapOf("mode" to ms(m("automatic", "automatica")))),
            "open_settings_screen" to case(
                Action.OpenSettingsScreen(SettingsScreen.APP_DETAILS, "com.xyz.settings"),
                mapOf("screen" to ms(m("App info", "Informazioni app")), "pkg" to ms("com.xyz.settings")),
            ),
            "vibrate" to case(Action.Vibrate(4242), mapOf("durationMs" to ms("4242 ms"))),
            "write_setting" to case(
                Action.WriteSetting(SettingNamespace.SECURE, "xyz_key", "XYZ-value"),
                mapOf(
                    "namespace" to ms("secure"),
                    "key" to ms("xyz_key"),
                    "value" to ms("XYZ-value"),
                ),
            ),
            "invoke_llm" to case(
                v(
                    llmReply,
                    "goal" to ms("XYZ-goal"),
                    "contextSources" to ms(m("context: xyz-source", "contesto: xyz-source")),
                    "allowedTools" to ms("xyz.tool"),
                    "replyTargetSender" to ms(m("reply bound to the sender", "risposta vincolata al mittente")),
                    "deliver" to ms(m("reply with AI", "rispondi con l'AI")),
                ),
                v(
                    llmReply.copy(
                        replyTargetSender = false,
                        deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
                        notificationTitle = "XYZ-ntitle",
                    ),
                    "deliver" to ms(m("Generate and notify", "Genera e notifica")),
                    "notificationTitle" to ms("XYZ-ntitle"),
                ),
                v(
                    llmReply.copy(
                        replyTargetSender = false,
                        deliver = GenerativeDeliverMode.CAPTURE_ONLY,
                        captureAs = "xyz_cap",
                    ),
                    "deliver" to ms(m("Generate a value", "Genera un valore")),
                    "captureAs" to ms("xyz_cap"),
                ),
                // 0.8: uscita vincolata (spenta finché il suo FeatureGate è off).
                v(
                    llmReply.copy(
                        replyTargetSender = false,
                        deliver = GenerativeDeliverMode.CAPTURE_ONLY,
                        captureAs = "xyz_cap",
                        output = OutputSpec(OutputKind.NUMBER, choices = listOf("XYZ-c0"), min = 3, max = 77),
                    ),
                    "output" to ms(m("a whole number from 3 up to 77", "un numero intero da 3 fino a 77"), m("XYZ-c0")),
                ),
                v(
                    llmReply.copy(output = OutputSpec(OutputKind.CHOICE, choices = listOf("XYZ-c1", "XYZ-c2"))),
                    "output" to ms(m("one of \"XYZ-c1\", \"XYZ-c2\"", "una tra \"XYZ-c1\", \"XYZ-c2\"")),
                ),
                v(
                    llmReply.copy(output = OutputSpec(OutputKind.BOOLEAN)),
                    "output" to ms(m("yes or no", "sì o no")),
                ),
                notRendered = mapOf("timeoutMs" to timeoutReason),
            ),
            "invoke_llm_v2" to case(
                Action.InvokeLlmV2(
                    goal = "XYZ-goal2",
                    stateContext = listOf(
                        ApprovedStateContext(
                            query = StateQuery.Builtin("XYZ-ctxkey"),
                            valueType = StateValueType.TEXT,
                            policyVersion = StateQueryPolicy.VERSION,
                            integrity = IntegrityLabel.CLEAN,
                            confidentiality = ConfidentialityLabel.SECRET,
                        ),
                    ),
                    allowedTools = listOf("xyz.tool2"),
                    replyTargetSender = true,
                    timeoutMs = 45_000,
                    captureAs = "xyz_cap2",
                ),
                mapOf(
                    "goal" to ms("XYZ-goal2"),
                    "stateContext" to ms(m("XYZ-ctxkey"), m("trusted", "attendibile"), m("secret", "segreto")),
                    "allowedTools" to ms("xyz.tool2"),
                    "replyTargetSender" to ms(m("reply bound to the sender", "risposta vincolata al mittente")),
                    "captureAs" to ms("xyz_cap2"),
                ),
                notRendered = mapOf("timeoutMs" to timeoutReason),
            ),
            "wait" to case(Action.Wait(4343), mapOf("durationMs" to ms("4343 ms"))),
            "if" to case(
                Action.If(
                    condition = Condition.VarCompare("xyz_cond", CmpOp.EQ, expected = "XYZ-ifexp"),
                    then = listOf(Action.Vibrate(1111)),
                    orElse = listOf(Action.Vibrate(2222)),
                ),
                mapOf(
                    "condition" to ms("xyz_cond", "XYZ-ifexp"),
                    "then" to ms("1111 ms"),
                    "orElse" to ms("2222 ms"),
                ),
            ),
            "while" to case(
                v(
                    Action.While(
                        condition = Condition.VarCompare("xyz_wc", CmpOp.LT, expected = "9"),
                        body = listOf(Action.Vibrate(3333)),
                        maxIterations = 77,
                        delayBetweenMs = 555,
                    ),
                    "condition" to ms("xyz_wc"),
                    "body" to ms("3333 ms"),
                    "maxIterations" to ms("77"),
                    "delayBetweenMs" to ms("555 ms"),
                ),
                v(
                    Action.While(
                        condition = Condition.BooleanLiteral(true),
                        body = listOf(Action.Vibrate(3333)),
                        maxIterationsVar = "xyz_iter",
                    ),
                    "maxIterationsVar" to ms("xyz_iter"),
                ),
            ),
            // --- 0.4.1 (azioni) ---
            "speak" to case(Action.Speak("XYZ-speak"), mapOf("text" to ms("XYZ-speak"))),
            "media_control" to case(
                Action.MediaControl(MediaCommand.PREVIOUS),
                mapOf("command" to ms(m("previous track", "traccia precedente"))),
            ),
            "set_brightness" to case(
                v(Action.SetBrightness(level = 37), "level" to ms("37%"), "auto" to none),
                v(
                    Action.SetBrightness(auto = true),
                    "level" to none,
                    "auto" to ms(m("automatic", "automatica")),
                ),
            ),
            "set_rotation" to case(
                v(Action.SetRotation(true), "auto" to ms(m("on", "attiva"))),
                v(Action.SetRotation(false), "auto" to ms(m("off", "disattivata"))),
            ),
            "http_request" to case(
                v(
                    Action.HttpRequest(
                        method = HttpMethod.PUT,
                        url = "https://xyz.example.org/XYZ-path?q=1",
                        headers = mapOf("X-XYZ-Key" to "secret-value-XYZ-wxyz", "X-XYZ-Plain" to "XYZ-plain-value"),
                        body = "XYZ-body",
                        contentType = "application/xyz",
                    ),
                    "method" to ms("PUT"),
                    "url" to ms("https://xyz.example.org/XYZ-path?q=1"),
                    // Il valore di un'intestazione segreta compare mascherato, mai per intero.
                    "headers" to ms("X-XYZ-Key: ••••wxyz", "X-XYZ-Plain: XYZ-plain-value"),
                    "body" to ms(m("Body: XYZ-body", "Corpo: XYZ-body")),
                    "contentType" to ms("Content-Type: application/xyz"),
                ),
                // 0.8: cattura della risposta e valori dalla cassaforte (solo i nomi; spenti finché i
                // loro FeatureGate sono off).
                v(
                    Action.HttpRequest(
                        method = HttpMethod.GET,
                        url = "https://xyz.example.org/data",
                        captureAs = "xyz_resp",
                        extract = "/xyz/ptr",
                        secretHeaders = listOf(SecretRef("X-XYZ-Auth", "xyz_vault_token", "Bearer ")),
                        secretQuery = listOf(SecretRef("xyz_q", "xyz_vault_q")),
                    ),
                    "captureAs" to ms(m("Response captured as xyz_resp", "Risposta catturata come xyz_resp")),
                    "extract" to ms(m("JSON pointer /xyz/ptr", "puntatore JSON /xyz/ptr")),
                    "secretHeaders" to ms(
                        m("X-XYZ-Auth: Bearer ⟨secret xyz_vault_token⟩", "X-XYZ-Auth: Bearer ⟨segreto xyz_vault_token⟩"),
                    ),
                    "secretQuery" to ms(
                        m(
                            "Query parameter xyz_q = ⟨secret xyz_vault_q⟩",
                            "Parametro di query xyz_q = ⟨segreto xyz_vault_q⟩",
                        ),
                    ),
                ),
            ),
            // --- 0.5.1 (azioni privilegiate tipizzate) ---
            "app_control" to case(
                v(
                    Action.AppControl("com.xyz.app", AppControlOp.FORCE_STOP),
                    "pkg" to ms("com.xyz.app"),
                    "op" to ms(m("Force stop", "Forza l'arresto di")),
                ),
                v(
                    Action.AppControl("com.xyz.app", AppControlOp.FREEZE),
                    "pkg" to ms("com.xyz.app"),
                    "op" to ms(m("Freeze (disable)", "Congela (disattiva)")),
                ),
                v(
                    Action.AppControl("com.xyz.app", AppControlOp.UNFREEZE),
                    "pkg" to ms("com.xyz.app"),
                    "op" to ms(m("Unfreeze (re-enable)", "Scongela (riattiva)")),
                ),
            ),
            "set_airplane" to case(
                v(Action.SetAirplane(true), "on" to ms(m("Turn on airplane mode", "Attiva la modalità aereo"))),
                v(Action.SetAirplane(false), "on" to ms(m("Turn off airplane mode", "Disattiva la modalità aereo"))),
            ),
            "set_location" to case(
                v(Action.SetLocation(true), "on" to ms(m("Turn on location", "Attiva la posizione"))),
                v(Action.SetLocation(false), "on" to ms(m("Turn off location", "Disattiva la posizione"))),
            ),
            "set_nfc" to case(
                v(Action.SetNfc(true), "on" to ms(m("Turn on NFC", "Attiva l'NFC"))),
                v(Action.SetNfc(false), "on" to ms(m("Turn off NFC", "Disattiva l'NFC"))),
            ),
            "set_battery_saver" to case(
                v(Action.SetBatterySaver(true), "on" to ms(m("Turn on battery saver", "Attiva il risparmio energetico"))),
                v(Action.SetBatterySaver(false), "on" to ms(m("Turn off battery saver", "Disattiva il risparmio energetico"))),
            ),
            "lock_screen" to case(
                Action.LockScreen,
                emptyMap(),
            ),
            "screenshot" to case(
                Action.Screenshot,
                emptyMap(),
            ),
            "app_permission" to case(
                v(
                    Action.AppPermission("com.xyz.app", "android.permission.RECORD_AUDIO", grant = true),
                    "pkg" to ms("com.xyz.app"),
                    "permission" to ms("android.permission.RECORD_AUDIO"),
                    "grant" to ms(m("Grant the microphone permission to", "Concedi il microfono a")),
                ),
                v(
                    Action.AppPermission("com.xyz.app", "android.permission.RECORD_AUDIO", grant = false),
                    "pkg" to ms("com.xyz.app"),
                    "permission" to ms("android.permission.RECORD_AUDIO"),
                    "grant" to ms(m("Revoke the microphone permission from", "Revoca il microfono a")),
                ),
            ),
            // --- 0.5.1 (macro sullo schermo) ---
            "ui_steps" to case(
                v(
                    Action.UiSteps(
                        pkg = "com.xyz.shop",
                        steps = listOf(
                            UiStep.WaitFor(text = "XYZ-wait", id = "xyz_wait_id", timeoutMs = 8_000),
                            UiStep.Click(text = "XYZ-click", id = "xyz_click_id", desc = "XYZ-desc", index = 2),
                            UiStep.Type("XYZ-typed"),
                            UiStep.Scroll(ScrollDirection.UP),
                            UiStep.Check(text = "XYZ-check", present = false),
                            UiStep.Back,
                            UiStep.Home,
                        ),
                    ),
                    "pkg" to ms(m("Opens com.xyz.shop first", "Prima apre com.xyz.shop")),
                    "steps" to ms(
                        m(
                            """Wait up to 8 s for text "XYZ-wait" and id "xyz_wait_id"""",
                            """Aspetta fino a 8 s: testo "XYZ-wait" e id "xyz_wait_id"""",
                        ),
                        m(
                            """Tap the element with text "XYZ-click" and id "xyz_click_id" and description "XYZ-desc" (match number 3)""",
                            """Tocca l'elemento con testo "XYZ-click" e id "xyz_click_id" e descrizione "XYZ-desc" (corrispondenza numero 3)""",
                        ),
                        m("""Type "XYZ-typed" in the focused field""", """Scrivi "XYZ-typed" nel campo attivo"""),
                        m("Scroll up", "Scorri in su"),
                        m(
                            """Check that "XYZ-check" is NOT on the screen, otherwise stop""",
                            """Controlla che "XYZ-check" NON sia sullo schermo, altrimenti fermati""",
                        ),
                        m("Press Back", "Premi Indietro"),
                        m("Press Home", "Premi Home"),
                        m(
                            "Acts on the screen like you would",
                            "Agisce sullo schermo come faresti tu",
                        ),
                    ),
                ),
                v(
                    Action.UiSteps(pkg = null, steps = listOf(UiStep.Scroll(ScrollDirection.DOWN))),
                    "pkg" to ms(
                        m("Works on the screen as it is", "Lavora sullo schermo così com'è"),
                    ),
                ),
            ),
            // --- 0.8 (spente finché il loro FeatureGate è off, ma rese per intero) ---
            "ask" to case(
                v(
                    Action.Ask("XYZ-question", listOf("XYZ-yes", "XYZ-no"), "xyz_answer", 90_000, "XYZ-no"),
                    "prompt" to ms("XYZ-question"),
                    "choices" to ms("\"XYZ-yes\", \"XYZ-no\""),
                    "captureAs" to ms("xyz_answer"),
                    "timeoutMs" to ms(m("1 minute and 30 seconds", "1 minuto e 30 secondi")),
                    "defaultChoice" to ms(
                        m(
                            "With no answer within 1 minute and 30 seconds: \"XYZ-no\"",
                            "Senza risposta entro 1 minuto e 30 secondi: \"XYZ-no\"",
                        ),
                    ),
                ),
                v(
                    Action.Ask("XYZ-question", listOf("XYZ-yes"), "xyz_answer"),
                    "defaultChoice" to ms(m("the rule stops", "la regola si ferma")),
                ),
            ),
            "set_stored" to case(
                v(
                    Action.SetStored("XYZ-key", StoredOp.SET, "XYZ-val"),
                    "key" to ms("XYZ-key"),
                    "op" to ms(m("Set \"XYZ-key\"", "Imposta \"XYZ-key\"")),
                    "value" to ms("XYZ-val"),
                ),
                v(
                    Action.SetStored("XYZ-key", StoredOp.INCREMENT),
                    "op" to ms(m("Increase \"XYZ-key\"", "Aumenta \"XYZ-key\""), m("by 1", "di 1")),
                ),
                v(Action.SetStored("XYZ-key", StoredOp.TOGGLE), "op" to ms(m("Toggle \"XYZ-key\"", "Inverti \"XYZ-key\""))),
                v(Action.SetStored("XYZ-key", StoredOp.CLEAR), "op" to ms(m("Delete \"XYZ-key\"", "Cancella \"XYZ-key\""))),
            ),
            "set_user_mode" to case(Action.SetUserMode("XYZ-mode"), mapOf("mode" to ms("XYZ-mode"))),
            "notification_reply" to case(Action.NotificationReply("XYZ-nreply"), mapOf("text" to ms("XYZ-nreply"))),
            "notification_action" to case(
                v(
                    Action.NotificationAction(NotificationActionOp.CLICK, label = "XYZ-button"),
                    "op" to ms(m("Tap a button of the notification", "Tocca un pulsante della notifica")),
                    "label" to ms("XYZ-button"),
                    "snoozeMinutes" to none,
                ),
                v(
                    Action.NotificationAction(NotificationActionOp.SNOOZE, snoozeMinutes = 47),
                    "op" to ms(m("Snooze the notification", "Posticipa la notifica")),
                    "snoozeMinutes" to ms(m("for 47 minutes", "per 47 minuti")),
                ),
                v(
                    Action.NotificationAction(NotificationActionOp.DISMISS),
                    "op" to ms(m("Dismiss the notification", "Chiudi la notifica")),
                ),
            ),
            "send_sms" to case(
                Action.SendSms("+39XYZ111", "XYZ-sms-text"),
                mapOf("number" to ms("+39XYZ111"), "text" to ms("XYZ-sms-text")),
            ),
            "call_number" to case(Action.CallNumber("+39XYZ222"), mapOf("number" to ms("+39XYZ222"))),
            "send_intent" to case(
                v(
                    Action.SendIntent(
                        kind = IntentKind.ACTIVITY,
                        action = "xyz.intent.ACTION",
                        pkg = "com.xyz.intent",
                        cls = "com.xyz.intent.XyzActivity",
                        data = "xyz://data/1",
                        mimeType = "xyz/type",
                        extras = listOf(IntentExtra("xyz_extra", IntentExtraType.INT, "4242")),
                    ),
                    "kind" to ms(m("Start an activity", "Avvia un'activity")),
                    "action" to ms("xyz.intent.ACTION"),
                    "pkg" to ms("com.xyz.intent"),
                    "cls" to ms("com.xyz.intent.XyzActivity"),
                    "data" to ms("xyz://data/1"),
                    "mimeType" to ms("xyz/type"),
                    "extras" to ms("xyz_extra (INT): 4242"),
                ),
                v(
                    Action.SendIntent(IntentKind.BROADCAST),
                    "kind" to ms(m("Send a broadcast Intent", "Invia un Intent broadcast")),
                ),
                v(
                    Action.SendIntent(IntentKind.SERVICE),
                    "kind" to ms(m("Start a service", "Avvia un servizio")),
                ),
            ),
            "screen_call" to case(
                v(
                    Action.ScreenCall(ScreenCallOp.REJECT),
                    "op" to ms(m("Reject the incoming call", "Rifiuta la chiamata in arrivo")),
                ),
                v(
                    Action.ScreenCall(ScreenCallOp.SILENCE),
                    "op" to ms(m("Silence the incoming call", "Silenzia la chiamata in arrivo")),
                ),
            ),
        )
        check("Action", Action.serializer(), cases) { action -> automation(actions = listOf(action)) }
    }

    // ---------------------------------------------------------------------------------------------
    // 0.8: campi di regola (conferma prima di eseguire, regola-intervallo)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `rule confirmation and exit span are rendered with every field`() {
        assertEquals(
            setOf("timeoutMs"),
            ConfirmSpec.serializer().descriptor.elementNames.toSet(),
            "ConfirmSpec ha un campo nuovo: rendilo in approvazione e aggiorna il test",
        )
        assertEquals(
            setOf("trigger", "restore", "actions"),
            ExitSpec.serializer().descriptor.elementNames.toSet(),
            "ExitSpec ha un campo nuovo: rendilo in approvazione e aggiorna il test",
        )
        fun span(restore: Boolean) = Automation(
            id = AutomationId("cov-span"),
            name = "coverage",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = immediate,
            actions = listOf(noopAction),
            confirm = ConfirmSpec(timeoutMs = 125_000),
            exit = ExitSpec(Trigger.Screen(ScreenEvent.OFF), restore = restore, actions = listOf(Action.Vibrate(5151))),
        )
        val expected = listOf(
            m("2 minutes and 5 seconds", "2 minuti e 5 secondi"),
            m("Run / Cancel", "Esegui / Annulla"),
            m("the screen turns off", "lo schermo si spegne"),
            m("puts back the values", "rimette i valori"),
            m("5151 ms"),
        )
        languages.forEach { language ->
            val text = flatten(RuleRenderMapper.map(span(restore = true), language = language))
            expected.forEach { marker ->
                val wanted = if (language == RenderLanguage.IT) marker.it else marker.en
                assertTrue(wanted in text, "confirm/exit: «$wanted» assente dal render $language:\n$text")
            }
            val noRestore = flatten(RuleRenderMapper.map(span(restore = false), language = language))
            val wanted = if (language == RenderLanguage.IT) "non rimette" else "does not put back"
            assertTrue(wanted in noRestore, "exit.restore=false: «$wanted» assente dal render $language:\n$noRestore")

            val plain = RuleRenderMapper.map(automation(), language = language)
            assertEquals(null, plain.confirmLine)
            assertEquals(null, plain.exitLine)
            assertTrue(plain.exitProgram.isEmpty())
        }
    }

    @Test
    fun `approved state context metadata is rendered per field`() {
        val descriptor = ApprovedStateContext.serializer().descriptor
        assertEquals(
            setOf("query", "valueType", "policyVersion", "integrity", "confidentiality"),
            descriptor.elementNames.toSet(),
            "ApprovedStateContext ha un campo nuovo: rendilo nel dettaglio di invoke_llm_v2 e aggiorna il test",
        )
    }
}
