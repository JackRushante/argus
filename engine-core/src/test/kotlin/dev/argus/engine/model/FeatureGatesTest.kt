package dev.argus.engine.model

import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.engine.runtime.VarScope
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.StaticShellSafety
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Vocabolario 0.8 SPENTO: ogni tipo nuovo esiste nel modello ma non si arma, non si risolve e non si valuta
 * finché il suo [FeatureGate] è off. Quando un engineer accende la sua feature, toglie il suo caso da qui e
 * scrive i test veri della feature.
 */
class FeatureGatesTest {
    private val validator = DraftValidator(setOf("whatsapp_reply", "web.search"))

    private fun draft(
        trigger: Trigger = Trigger.Manual,
        actions: List<Action> = listOf(Action.Vibrate(100)),
        conditions: Condition? = null,
        vars: List<VarBinding> = emptyList(),
        confirm: ConfirmSpec? = null,
        exit: ExitSpec? = null,
    ) = AutomationDraft(
        name = "gate",
        trigger = trigger,
        actions = actions,
        vars = vars,
        conditions = conditions,
        confirm = confirm,
        exit = exit,
    )

    private fun notAvailable(d: AutomationDraft): List<String?> =
        validator.validate(d, emptySet())
            .filter { it.code == FeatureGates.NOT_AVAILABLE_YET }
            .onEach { assertEquals(Severity.ERROR, it.severity) }
            .map { it.detail }

    private val newTriggers: Map<FeatureGate, Trigger> = linkedMapOf(
        FeatureGate.TRIGGER_NFC_TAG to Trigger.NfcTag("04:A2:19", resolveTag = false),
        FeatureGate.TRIGGER_NEXT_ALARM to Trigger.NextAlarm(15),
        FeatureGate.TRIGGER_APP_USAGE to Trigger.AppUsage("com.example.game", 60),
        FeatureGate.TRIGGER_SENSOR_SHAKE to Trigger.Sensor(SensorKind.SHAKE),
        FeatureGate.TRIGGER_SENSOR_FLIP_FACE_DOWN to Trigger.Sensor(SensorKind.FLIP_FACE_DOWN),
    )

    /** Tutte le condizioni 0.8 sono accese: la tabella resta per chi ne aggiungerà altre spente. */
    private val newConditions: Map<FeatureGate, Condition> = linkedMapOf()

    private val newActions: Map<FeatureGate, Action> = linkedMapOf(
        FeatureGate.ACTION_SEND_SMS to Action.SendSms("+393331112222", "Arrivo"),
        FeatureGate.ACTION_CALL_NUMBER to Action.CallNumber("+393331112222"),
        FeatureGate.ACTION_SEND_INTENT to Action.SendIntent(IntentKind.BROADCAST, action = "dev.example.PING"),
        FeatureGate.ACTION_SCREEN_CALL to Action.ScreenCall(ScreenCallOp.REJECT),
    )

    /** Interruttori già accesi: i loro test stanno con la feature. */
    private val switchedOn: Set<FeatureGate> = setOf(
        // Condizioni di contesto e trigger del calendario.
        FeatureGate.CONDITION_WEEKDAY,
        FeatureGate.CONDITION_WIFI_CONNECTED,
        FeatureGate.CONDITION_BLUETOOTH_CONNECTED,
        FeatureGate.CONDITION_HEADSET_CONNECTED,
        FeatureGate.CONDITION_CALENDAR_BUSY,
        FeatureGate.TRIGGER_CALENDAR_EVENT,
        // Risposte e azioni sulle notifiche.
        FeatureGate.ACTION_NOTIFICATION_REPLY,
        FeatureGate.ACTION_NOTIFICATION_ACTION,
        // Valori conservati e modalità (StoredStateTest, StoredModesRuntimeTest, DraftValidatorStoredModesTest).
        FeatureGate.VAR_STORED,
        FeatureGate.CONDITION_STORED_COMPARE,
        FeatureGate.CONDITION_USER_MODE_IS,
        FeatureGate.TRIGGER_USER_MODE_CHANGED,
        FeatureGate.ACTION_SET_STORED,
        FeatureGate.ACTION_SET_USER_MODE,
        // Conferma, domanda con pulsanti e risposte vincolate dell'AI.
        FeatureGate.CONFIRM,
        FeatureGate.ACTION_ASK,
        FeatureGate.LLM_OUTPUT,
        // Risposta HTTP in una variabile e segreti (HttpCapture08Test, HttpSecrets08Test).
        FeatureGate.HTTP_CAPTURE,
        FeatureGate.HTTP_SECRETS,
    )

    private val ask = Action.Ask("Esco?", listOf("Sì", "No"), "answer", defaultChoice = "No")

    private val httpCapture = Action.HttpRequest(HttpMethod.GET, "https://example.org", captureAs = "body")
    private val httpSecrets = Action.HttpRequest(
        HttpMethod.GET,
        "https://example.org",
        secretHeaders = listOf(SecretRef("Authorization", "home_token", "Bearer ")),
    )

    @Test
    fun `every gate not yet switched on is off in this version`() {
        assertEquals(switchedOn, FeatureGate.entries.filter { it.enabled }.toSet())
        assertEquals(FeatureGate.entries.size, FeatureGate.entries.map { it.wireName }.toSet().size)
    }

    @Test
    fun `the tables above cover every gate`() {
        val covered = newTriggers.keys + newConditions.keys + newActions.keys + switchedOn +
            setOf(FeatureGate.EXIT)
        assertEquals(FeatureGate.entries.toSet(), covered)
    }

    @Test
    fun `the validator rejects every new type with one shared error naming the feature`() {
        newTriggers.forEach { (gate, trigger) ->
            assertEquals(listOf(gate.wireName), notAvailable(draft(trigger = trigger)), "trigger $trigger")
        }
        newConditions.forEach { (gate, condition) ->
            assertEquals(listOf(gate.wireName), notAvailable(draft(conditions = condition)), "condizione $condition")
            // Anche annidata e negata.
            assertEquals(
                listOf(gate.wireName),
                notAvailable(draft(conditions = Condition.Not(Condition.And(listOf(condition))))),
            )
        }
        newActions.forEach { (gate, action) ->
            assertEquals(listOf(gate.wireName), notAvailable(draft(actions = listOf(action))), "azione $action")
            // Anche dentro un if.
            val nested = Action.If(Condition.BooleanLiteral(true), then = listOf(action))
            assertTrue(gate.wireName in notAvailable(draft(actions = listOf(nested))), "azione annidata $action")
        }
        assertEquals(listOf(FeatureGate.EXIT.wireName), notAvailable(draft(exit = ExitSpec(Trigger.Manual))))
        // Un tipo spento nel trigger o nelle azioni d'uscita conta anche lui.
        assertEquals(
            listOf(FeatureGate.EXIT.wireName, FeatureGate.TRIGGER_NFC_TAG.wireName, FeatureGate.ACTION_CALL_NUMBER.wireName),
            notAvailable(draft(exit = ExitSpec(Trigger.NfcTag("04"), actions = listOf(Action.CallNumber("+39"))))),
        )
    }

    @Test
    fun `rules without new types never see the gate`() {
        val existing = listOf(
            draft(),
            draft(
                trigger = Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION),
                actions = listOf(
                    Action.HttpRequest(HttpMethod.POST, "https://example.org", body = "x"),
                    Action.InvokeLlm("g", emptyList(), emptyList(), false, deliver = GenerativeDeliverMode.CAPTURE_ONLY, captureAs = "g"),
                ),
            ),
        )
        existing.forEach { assertEquals(emptyList(), notAvailable(it)) }
        existing.forEach { assertEquals(emptySet(), FeatureGates.usedBy(it)) }
    }

    @Test
    fun `the interpolator blocks every new action with the same code`() {
        val interpolator = TaintAwareInterpolator()
        newActions.forEach { (_, action) ->
            val resolution = interpolator.resolve(action, VarScope())
            assertIs<ActionResolution.Blocked>(resolution, "azione $action")
            assertEquals(FeatureGates.NOT_AVAILABLE_YET, resolution.code)
        }
        assertIs<ActionResolution.Resolved>(interpolator.resolve(Action.HttpRequest(HttpMethod.GET, "https://example.org"), VarScope()))
    }

    @Test
    fun `new conditions are unknown, so they fail closed even under not`() {
        val evaluator = ConditionEvaluator(Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC))
        newConditions.values.forEach { condition ->
            assertEquals(ConditionEvaluator.Result.STATE_UNAVAILABLE, evaluator.result(condition, DeviceState()))
            assertFalse(evaluator.eval(Condition.Not(condition), DeviceState()), "not($condition)")
        }
    }

    @Test
    fun `shell may start from an nfc tag, never from the next alarm or the other new triggers`() {
        val allowed = newTriggers.filterValues { StaticShellSafety.allows(it, emptySet()) }.keys
        assertEquals(
            setOf(
                FeatureGate.TRIGGER_NFC_TAG,
                FeatureGate.TRIGGER_SENSOR_SHAKE,
                FeatureGate.TRIGGER_SENSOR_FLIP_FACE_DOWN,
            ),
            allowed,
        )
        val fingerprint = ApprovalFingerprint("a".repeat(64))
        val id = AutomationId("r")
        assertTrue(StaticShellSafety.allows(Trigger.NfcTag("04:A2"), TriggerEvent.NfcTagScanned("04:a2"), emptySet()))
        assertFalse(StaticShellSafety.allows(Trigger.NfcTag("04:A2"), TriggerEvent.NfcTagScanned("FF"), emptySet()))
        assertFalse(StaticShellSafety.allows(Trigger.NextAlarm(5), TriggerEvent.NextAlarmFired(id, fingerprint), emptySet()))
        assertFalse(StaticShellSafety.allows(TriggerEvent.CalendarEventFired(id, CalendarPhase.START, fingerprint), emptySet()))
        assertFalse(StaticShellSafety.allows(TriggerEvent.AppUsageChanged("com.example.game", 59, 60), emptySet()))
        assertFalse(StaticShellSafety.allows(TriggerEvent.UserModeChanged("lavoro", null), emptySet()))
    }

    @Test
    fun `the matcher pairs each new trigger with its event`() {
        val matcher = TriggerMatcher()
        val fingerprint = ApprovalFingerprint("a".repeat(64))
        val id = AutomationId("r")
        assertTrue(matcher.matches(Trigger.CalendarEvent(CalendarPhase.END), TriggerEvent.CalendarEventFired(id, CalendarPhase.END, fingerprint)))
        assertFalse(matcher.matches(Trigger.CalendarEvent(CalendarPhase.END), TriggerEvent.CalendarEventFired(id, CalendarPhase.START, fingerprint)))
        assertTrue(matcher.matches(Trigger.NfcTag("04:A2"), TriggerEvent.NfcTagScanned("04:a2")))
        assertFalse(matcher.matches(Trigger.NfcTag("", resolveTag = true), TriggerEvent.NfcTagScanned("")))
        assertTrue(matcher.matches(Trigger.NextAlarm(10), TriggerEvent.NextAlarmFired(id, fingerprint)))
        assertTrue(matcher.matches(Trigger.AppUsage("com.example.game", 60), TriggerEvent.AppUsageChanged("com.example.game", 59, 61)))
        assertFalse(matcher.matches(Trigger.AppUsage("com.example.game", 60), TriggerEvent.AppUsageChanged("com.example.game", 60, 61)))
        assertTrue(matcher.matches(Trigger.UserModeChanged(), TriggerEvent.UserModeChanged("casa", "lavoro")))
        assertFalse(matcher.matches(Trigger.UserModeChanged("lavoro"), TriggerEvent.UserModeChanged("casa", null)))
    }

    @Test
    fun `each new family requires its own capability`() {
        fun caps(d: AutomationDraft) = CapabilityRequirements.derive(d)
        assertTrue(CapabilityIds.TRIGGER_CALENDAR_EVENT in caps(draft(trigger = Trigger.CalendarEvent(CalendarPhase.START, -10, "Riunione", "Lavoro"))))
        assertTrue(CapabilityIds.triggerSensor(SensorKind.SHAKE) in caps(draft(trigger = Trigger.Sensor(SensorKind.SHAKE))))
        assertTrue(CapabilityIds.STATE_STORED in caps(draft(conditions = Condition.StoredCompare("count", StateValueType.NUMBER, CmpOp.GT, "3"))))
        assertTrue(CapabilityIds.ACTION_SEND_SMS in caps(draft(actions = listOf(newActions.getValue(FeatureGate.ACTION_SEND_SMS)))))
        assertTrue(CapabilityIds.ACTION_HTTP_REQUEST_SECRETS in caps(draft(actions = listOf(httpSecrets))))
        assertTrue(CapabilityIds.RULE_CONFIRM in caps(draft(confirm = ConfirmSpec())))
        val span = caps(draft(exit = ExitSpec(Trigger.NfcTag("04"), actions = listOf(Action.SetUserMode("casa")))))
        assertTrue(span.containsAll(setOf(CapabilityIds.RULE_EXIT, CapabilityIds.TRIGGER_NFC_TAG, CapabilityIds.ACTION_SET_USER_MODE)))
        // weekday usa l'orologio del motore, come time_window.
        assertEquals(caps(draft()), caps(draft(conditions = Condition.Weekday(setOf(Day.MON, Day.TUE), "Europe/Rome"))))
        // Nessuna azione nuova chiede lo shell privilegiato.
        newActions.values.forEach { assertEquals(ActionPrivilege.BASE, ActionPrivileges.of(it), "$it") }
    }

    @Test
    fun `capturing and stored-value actions make the rule a p4 program`() {
        listOf(
            ask,
            Action.SetStored("count", StoredOp.INCREMENT),
            httpCapture,
        ).forEach { action ->
            assertEquals(AUTOMATION_SCHEMA_VERSION_P4, AutomationSchema.versionFor(draft(actions = listOf(action))), "$action")
        }
        assertEquals(
            AUTOMATION_SCHEMA_VERSION_P4,
            AutomationSchema.versionFor(draft(exit = ExitSpec(Trigger.Manual, actions = listOf(Action.Wait(100))))),
        )
        assertEquals(AUTOMATION_SCHEMA_VERSION_V1, AutomationSchema.versionFor(draft(actions = listOf(Action.SetUserMode("m")))))
    }

    @Test
    fun `every new type round trips through the wire with its discriminator`() {
        val json = ArgusJson
        newTriggers.values.forEach { trigger ->
            assertEquals(trigger, json.decodeFromString(Trigger.serializer(), json.encodeToString(Trigger.serializer(), trigger)))
        }
        newConditions.values.forEach { condition ->
            assertEquals(condition, json.decodeFromString(Condition.serializer(), json.encodeToString(Condition.serializer(), condition)))
        }
        newActions.values.forEach { action ->
            assertEquals(action, json.decodeFromString(Action.serializer(), json.encodeToString(Action.serializer(), action)))
        }
        assertEquals(
            """{"type":"ask","prompt":"Esco?","choices":["Sì","No"],"captureAs":"answer","timeoutMs":120000,"defaultChoice":"No"}""",
            json.encodeToString(Action.serializer(), ask),
        )
        assertEquals(
            """{"type":"weekday","days":["MON","TUE"],"tz":"Europe/Rome"}""",
            json.encodeToString(Condition.serializer(), Condition.Weekday(setOf(Day.MON, Day.TUE), "Europe/Rome")),
        )
        // I nuovi enum leggono anche il minuscolo, come gli enum 0.4.1.
        assertEquals(
            Action.ScreenCall(ScreenCallOp.SILENCE),
            json.decodeFromString(Action.serializer(), """{"type":"screen_call","op":"silence"}"""),
        )
    }

    @Test
    fun `toDraft carries confirm and exit, and a rule without them keeps its bytes`() {
        val rule = Automation(
            id = AutomationId("span"),
            name = "span",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Manual,
            actions = listOf(Action.Vibrate(100)),
            confirm = ConfirmSpec(60_000),
            exit = ExitSpec(Trigger.Manual, restore = false),
        )
        val draft = rule.toDraft()
        assertEquals(rule.confirm, draft.confirm)
        assertEquals(rule.exit, draft.exit)
        val plain = ArgusJson.encodeToString(Automation.serializer(), rule.copy(confirm = null, exit = null))
        assertFalse("\"confirm\"" in plain || "\"exit\"" in plain)
    }
}
