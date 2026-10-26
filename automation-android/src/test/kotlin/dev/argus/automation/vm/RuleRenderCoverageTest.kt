package dev.argus.automation.vm

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.MediaCommand
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
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
                Trigger.Sensor(SensorKind.STEP_COUNTER, minimumEventCount = 317),
                mapOf(
                    "kind" to ms(m("step counter", "contatore")),
                    "minimumEventCount" to ms("317"),
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
                    "query" to ms("GLOBAL:xyz_setting_key"),
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
                mapOf("namespace" to ms("SECURE"), "key" to ms("XYZ-sk")),
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
                    "seconds" to ms("4321"),
                    "label" to ms("XYZ-timer"),
                    "skipUi" to ms(m("opens the clock app", "apre l'app Orologio")),
                ),
            ),
            "set_volume" to case(
                Action.SetVolume(VolumeStream.ALARM, 73),
                mapOf("stream" to ms("alarm"), "level" to ms("73")),
            ),
            "set_flashlight" to case(
                v(Action.SetFlashlight(true), "on" to ms(m("Turn on flashlight", "Accendi torcia"))),
                v(Action.SetFlashlight(false), "on" to ms(m("Turn off flashlight", "Spegni torcia"))),
            ),
            "set_dark_mode" to case(Action.SetDarkMode(NightMode.AUTO), mapOf("mode" to ms("auto"))),
            "open_settings_screen" to case(
                Action.OpenSettingsScreen(SettingsScreen.APP_DETAILS, "com.xyz.settings"),
                mapOf("screen" to ms("app_details"), "pkg" to ms("com.xyz.settings")),
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
                    "stateContext" to ms("XYZ-ctxkey", "CLEAN", "SECRET"),
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
                Action.HttpRequest(
                    method = HttpMethod.PUT,
                    url = "https://xyz.example.org/XYZ-path?q=1",
                    headers = mapOf("X-XYZ-Key" to "secret-value-XYZ-wxyz", "X-XYZ-Plain" to "XYZ-plain-value"),
                    body = "XYZ-body",
                    contentType = "application/xyz",
                ),
                mapOf(
                    "method" to ms("PUT"),
                    "url" to ms("https://xyz.example.org/XYZ-path?q=1"),
                    // Il valore di un'intestazione segreta compare mascherato, mai per intero.
                    "headers" to ms("X-XYZ-Key: ••••wxyz", "X-XYZ-Plain: XYZ-plain-value"),
                    "body" to ms(m("Body: XYZ-body", "Corpo: XYZ-body")),
                    "contentType" to ms("Content-Type: application/xyz"),
                ),
            ),
        )
        check("Action", Action.serializer(), cases) { action -> automation(actions = listOf(action)) }
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
