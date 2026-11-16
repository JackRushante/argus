package dev.argus.data.backup

import dev.argus.engine.model.Action
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ConfirmSpec
import dev.argus.engine.model.Day
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntentExtra
import dev.argus.engine.model.IntentExtraType
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.SecretRef
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Vocabolario 0.8 (spento): «Togli posizioni e contatti» e il backup stretto conoscono già ogni tipo. */
class PersonalDataStripper08Test {
    private fun draft(
        trigger: Trigger = Trigger.Manual,
        actions: List<Action> = listOf(Action.SetWifi(true)),
        conditions: Condition? = null,
        exit: ExitSpec? = null,
    ) = AutomationDraft(name = "t", trigger = trigger, actions = actions, conditions = conditions, exit = exit)

    private fun stripCondition(condition: Condition): Condition? =
        PersonalDataStripper.strip(draft(conditions = condition)).conditions

    private fun stripAction(action: Action): Action = PersonalDataStripper.strip(draft(actions = listOf(action))).actions.single()

    @Test
    fun `calendar trigger loses title and calendar but keeps phase and offset`() {
        val stripped = PersonalDataStripper.strip(
            draft(trigger = Trigger.CalendarEvent(CalendarPhase.START, -10, titleMatch = "Dentista", calendar = "Lavoro")),
        ).trigger
        assertEquals(Trigger.CalendarEvent(CalendarPhase.START, -10, titleMatch = "", calendar = ""), stripped)
    }

    @Test
    fun `nfc tag loses its id and is read again when the importer arms it`() {
        val stripped = PersonalDataStripper.strip(draft(trigger = Trigger.NfcTag("04:A2:19:7F", resolveTag = false))).trigger
        assertEquals(Trigger.NfcTag(tagId = "", resolveTag = true), stripped)
    }

    @Test
    fun `wifi, bluetooth and calendar conditions lose their identity, and null stays any`() {
        assertEquals(Condition.WifiConnected(ssid = ""), stripCondition(Condition.WifiConnected("CasaMia")))
        assertEquals(Condition.WifiConnected(ssid = null), stripCondition(Condition.WifiConnected(null)))
        assertEquals(Condition.BluetoothConnected(device = ""), stripCondition(Condition.BluetoothConnected("AA:BB:CC:DD:EE:FF")))
        assertEquals(
            Condition.CalendarBusy(titleMatch = "", calendar = null),
            stripCondition(Condition.CalendarBusy(titleMatch = "Riunione", calendar = null)),
        )
    }

    @Test
    fun `phone numbers and intent data are removed, secret references are kept`() {
        assertEquals(Action.SendSms(number = "", text = "arrivo"), stripAction(Action.SendSms("+393331112222", "arrivo")))
        assertEquals(Action.CallNumber(number = ""), stripAction(Action.CallNumber("+393331112222")))

        val intent = stripAction(
            Action.SendIntent(
                kind = IntentKind.ACTIVITY,
                action = "android.intent.action.VIEW",
                data = "geo:45.46,9.19",
                extras = listOf(IntentExtra("contact", IntentExtraType.STRING, "Moglie")),
            ),
        ) as Action.SendIntent
        assertEquals("", intent.data)
        assertEquals("android.intent.action.VIEW", intent.action)
        assertEquals(listOf(IntentExtra("contact", IntentExtraType.STRING, "")), intent.extras)

        val refs = listOf(SecretRef("Authorization", "home_token", "Bearer "))
        val http = stripAction(
            Action.HttpRequest(HttpMethod.GET, "https://example.org", secretHeaders = refs, secretQuery = refs),
        ) as Action.HttpRequest
        assertEquals(refs, http.secretHeaders)
        assertEquals(refs, http.secretQuery)
    }

    @Test
    fun `the exit trigger and the exit actions are stripped like the start ones`() {
        val stripped = PersonalDataStripper.strip(
            draft(exit = ExitSpec(Trigger.NfcTag("04:A2"), restore = false, actions = listOf(Action.CallNumber("+39333")))),
        ).exit
        assertEquals(ExitSpec(Trigger.NfcTag("", resolveTag = true), restore = false, actions = listOf(Action.CallNumber(""))), stripped)
    }

    @Test
    fun `new types without personal data are left exactly as they are`() {
        val triggers = listOf(
            Trigger.NextAlarm(15),
            Trigger.AppUsage("com.example.game", 60),
            Trigger.UserModeChanged("lavoro"),
            Trigger.Sensor(SensorKind.SHAKE),
        )
        triggers.forEach { trigger -> assertEquals(draft(trigger = trigger), PersonalDataStripper.strip(draft(trigger = trigger))) }

        val conditions = listOf(
            Condition.Weekday(setOf(Day.MON, Day.FRI), "Europe/Rome"),
            Condition.HeadsetConnected(AudioOutputKind.WIRED),
            Condition.StoredCompare("count", StateValueType.NUMBER, CmpOp.GT, "3"),
            Condition.UserModeIs("casa"),
        )
        conditions.forEach { condition -> assertEquals(condition, stripCondition(condition)) }

        val actions = listOf(
            Action.Ask("Esco?", listOf("Sì", "No"), "answer", defaultChoice = "No"),
            Action.SetStored("count", StoredOp.INCREMENT),
            Action.SetUserMode("lavoro"),
            Action.NotificationReply("Arrivo"),
            Action.NotificationAction(NotificationActionOp.SNOOZE, snoozeMinutes = 10),
            Action.ScreenCall(ScreenCallOp.REJECT),
            Action.InvokeLlm("scegli", emptyList(), emptyList(), false, output = OutputSpec(OutputKind.BOOLEAN)),
        )
        actions.forEach { action -> assertEquals(action, stripAction(action)) }
    }

    @Test
    fun `the strict backup codec reads every new type back unchanged`() {
        val rule = AutomationDraft(
            name = "tutto",
            trigger = Trigger.CalendarEvent(CalendarPhase.END, titleMatch = "x"),
            actions = listOf(
                Action.Ask("?", listOf("a", "b"), "answer", timeoutMs = 60_000),
                Action.SetStored("k", StoredOp.SET, "v"),
                Action.SetUserMode("m"),
                Action.NotificationReply("r"),
                Action.NotificationAction(NotificationActionOp.CLICK, label = "Archivia"),
                Action.SendSms("+39", "t"),
                Action.CallNumber("+39"),
                Action.SendIntent(IntentKind.BROADCAST, action = "a.b", extras = listOf(IntentExtra("n", IntentExtraType.INT, "1"))),
                Action.ScreenCall(ScreenCallOp.SILENCE),
                Action.HttpRequest(
                    HttpMethod.GET,
                    "https://example.org",
                    captureAs = "body",
                    extract = "/data/0",
                    secretHeaders = listOf(SecretRef("X-Key", "k")),
                ),
                Action.InvokeLlm("g", emptyList(), emptyList(), false, output = OutputSpec(OutputKind.NUMBER, min = 1, max = 5)),
            ),
            vars = listOf(VarBinding.Stored("count", "count", StateValueType.NUMBER, ConfidentialityLabel.PRIVATE)),
            conditions = Condition.And(
                listOf(
                    Condition.Weekday(setOf(Day.SAT), "UTC"),
                    Condition.WifiConnected(),
                    Condition.BluetoothConnected("Auto"),
                    Condition.HeadsetConnected(),
                    Condition.CalendarBusy(),
                    Condition.StoredCompare("k", StateValueType.TEXT, CmpOp.EQ, "v"),
                    Condition.UserModeIs("m"),
                ),
            ),
            confirm = ConfirmSpec(),
            exit = ExitSpec(Trigger.UserModeChanged(), actions = listOf(Action.SetUserMode("n"))),
        )
        val text = RulesBackupCodec.encode(listOf(BackupRule(enabled = false, draft = rule)), "2026-11-16T00:00:00Z", "test")
        val decoded = RulesBackupCodec.decode(text) as DecodedBackup.Entries
        val entry = decoded.entries.single() as DecodedEntry.Rule
        assertEquals(rule, entry.rule.draft)
        assertTrue("\"confirm\"" in text && "\"exit\"" in text)
    }
}
