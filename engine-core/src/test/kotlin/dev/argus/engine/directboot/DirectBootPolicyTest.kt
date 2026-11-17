package dev.argus.engine.directboot

import dev.argus.engine.model.Action
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.ApprovalFingerprints
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
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.MediaCommand
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.SolarEvent
import dev.argus.engine.model.SolarSpec
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.UiStep
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VolumeStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Attiva anche prima dello sblocco»: the allow-list is closed and every type has an explicit decision. The coverage
 * checks fail when a new trigger, action or condition type is added without a sample (and a decision) here.
 */
class DirectBootPolicyTest {
    private val cron = Trigger.Time(cron = "0 7 * * *", tz = "Europe/Rome")

    /** Every trigger type with the decision it must get. */
    private val everyTrigger: Map<Trigger, Boolean> = mapOf(
        cron to true,
        Trigger.Geofence(radiusM = 100.0, transition = Transition.ENTER) to false,
        Trigger.Immediate to false,
        Trigger.Notification("com.whatsapp") to false,
        Trigger.PhoneState(PhoneEvent.SMS_RECEIVED) to false,
        Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED) to false,
        Trigger.Sensor(SensorKind.SHAKE) to false,
        Trigger.Screen(ScreenEvent.ON) to false,
        Trigger.Battery(below = 20) to false,
        Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED) to false,
        Trigger.ModeChanged(ModeKind.DND) to false,
        Trigger.Media(MediaPlayState.PLAYING) to false,
        Trigger.Manual to false,
        Trigger.AppEvent("com.example.app", AppEventKind.OPENED) to false,
        Trigger.CalendarEvent(CalendarPhase.START) to false,
        Trigger.NfcTag("04AABBCC") to false,
        Trigger.NextAlarm(10) to false,
        Trigger.AppUsage("com.example.app", 30) to false,
        Trigger.UserModeChanged() to false,
    )

    /** Every action type with the decision it must get. */
    private val everyAction: Map<Action, Boolean> = mapOf(
        Action.SetDnd(DndMode.PRIORITY) to true,
        Action.SetRinger("vibrate") to true,
        Action.SetVolume(VolumeStream.RING, 50) to true,
        Action.SetFlashlight(true) to true,
        Action.Vibrate(200) to true,
        Action.SetWifi(true) to false,
        Action.SetBluetooth(true) to false,
        Action.SetMobileData(false) to false,
        Action.LaunchApp("com.example.app") to false,
        Action.OpenUrl("https://example.com") to false,
        Action.ShowNotification("Argus", "text") to false,
        Action.Tap(10, 20) to false,
        Action.InputText("ciao") to false,
        Action.WhatsAppReply("ok") to false,
        Action.RunShell("date") to false,
        Action.CopyToClipboard() to false,
        Action.CopyText("text") to false,
        Action.SetAlarm(7, 30) to false,
        Action.SetTimer(60) to false,
        Action.SetDarkMode(NightMode.ON) to false,
        Action.OpenSettingsScreen(SettingsScreen.WIFI) to false,
        Action.Wait(1_000) to false,
        Action.WriteSetting(SettingNamespace.SYSTEM, "screen_brightness", "100") to false,
        Action.InvokeLlm("Summarise", listOf("notification"), emptyList(), replyTargetSender = false) to false,
        Action.InvokeLlmV2("Summarise", emptyList(), emptyList(), replyTargetSender = false, timeoutMs = 60_000) to false,
        Action.If(Condition.BooleanLiteral(true), then = listOf(Action.Vibrate(1))) to false,
        Action.While(Condition.BooleanLiteral(true), body = listOf(Action.Vibrate(1)), maxIterations = 2) to false,
        Action.Speak("text") to false,
        Action.MediaControl(MediaCommand.PAUSE) to false,
        Action.SetBrightness(level = 40) to false,
        Action.SetRotation(true) to false,
        Action.HttpRequest(HttpMethod.GET, "https://example.com/api") to false,
        Action.AppControl("com.example.app", AppControlOp.FORCE_STOP) to false,
        Action.SetAirplane(true) to false,
        Action.SetLocation(true) to false,
        Action.SetNfc(true) to false,
        Action.SetBatterySaver(true) to false,
        Action.LockScreen to false,
        Action.Screenshot to false,
        Action.AppPermission("com.example.app", "android.permission.CAMERA", grant = true) to false,
        Action.UiSteps(pkg = "com.example.app", steps = listOf(UiStep.Type("x"))) to false,
        Action.Ask("Continue?", listOf("yes", "no"), "answer") to false,
        Action.SetStored("count", StoredOp.INCREMENT) to false,
        Action.SetUserMode("Home") to false,
        Action.NotificationReply("ok") to false,
        Action.NotificationAction(NotificationActionOp.DISMISS) to false,
        Action.SendSms("+393331234567", "text") to false,
        Action.CallNumber("+393331234567") to false,
        Action.SendIntent(IntentKind.BROADCAST, action = "com.example.PING") to false,
        Action.ScreenCall(ScreenCallOp.SILENCE) to false,
    )

    /** Every condition type with the decision it must get. */
    private val everyCondition: Map<Condition, Boolean> = mapOf(
        Condition.TimeWindow("22:00", "07:00", "Europe/Rome") to true,
        Condition.Weekday(setOf(Day.MON, Day.FRI), "Europe/Rome") to true,
        Condition.And(listOf(Condition.TimeWindow("22:00", "07:00", "Europe/Rome"))) to true,
        Condition.Or(listOf(Condition.Weekday(setOf(Day.SUN), "Europe/Rome"))) to true,
        Condition.Not(Condition.Weekday(setOf(Day.SAT), "Europe/Rome")) to true,
        Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "normal") to false,
        Condition.StateCompare(StateQuery.Builtin(StateKeys.BATTERY), StateValueType.NUMBER, CmpOp.GT, "20") to false,
        Condition.AppInForeground("com.example.app") to false,
        Condition.LocationIn(41.9, 12.5, 100.0) to false,
        Condition.BooleanLiteral(true) to false,
        Condition.VarCompare("x", CmpOp.EQ, expected = "1") to false,
        Condition.WifiConnected() to false,
        Condition.BluetoothConnected() to false,
        Condition.HeadsetConnected() to false,
        Condition.CalendarBusy() to false,
        Condition.StoredCompare("count", StateValueType.NUMBER, CmpOp.EQ, "1") to false,
        Condition.UserModeIs("Home") to false,
    )

    private fun declared(type: Class<*>): Set<String> = type.declaredClasses
        .filter { type.isAssignableFrom(it) && it != type }
        .map { it.simpleName }
        .toSet()

    @Test
    fun `every trigger, action and condition type has an explicit decision`() {
        assertEquals(declared(Trigger::class.java), everyTrigger.keys.map { it.javaClass.simpleName }.toSet())
        assertEquals(declared(Action::class.java), everyAction.keys.map { it.javaClass.simpleName }.toSet())
        assertEquals(declared(Condition::class.java), everyCondition.keys.map { it.javaClass.simpleName }.toSet())
    }

    @Test
    fun `each type gets exactly the decision of the allow-list`() {
        everyTrigger.forEach { (trigger, allowed) -> assertEquals(allowed, DirectBootPolicy.allowsTrigger(trigger), "$trigger") }
        everyAction.forEach { (action, allowed) -> assertEquals(allowed, DirectBootPolicy.allowsAction(action), "$action") }
        everyCondition.forEach { (condition, allowed) ->
            assertEquals(allowed, DirectBootPolicy.allowsCondition(condition), "$condition")
        }
    }

    @Test
    fun `the Log type exists exactly for the allowed actions`() {
        everyAction.forEach { (action, allowed) ->
            if (allowed) assertNotNull(DirectBootPolicy.journalType(action), "$action") else assertNull(DirectBootPolicy.journalType(action), "$action")
        }
    }

    @Test
    fun `only a repeating clock time qualifies among the time triggers`() {
        assertTrue(DirectBootPolicy.allowsTrigger(cron.copy(precision = TimePrecision.EXACT)))
        assertFalse(DirectBootPolicy.allowsTrigger(Trigger.Time(at = "2026-12-01T07:00", tz = "Europe/Rome")))
        assertFalse(DirectBootPolicy.allowsTrigger(Trigger.Time(afterMs = 60_000, tz = "Europe/Rome")))
        assertFalse(DirectBootPolicy.allowsTrigger(Trigger.Time(tz = "Europe/Rome", solar = SolarSpec(SolarEvent.SUNSET))))
        assertFalse(DirectBootPolicy.allowsTrigger(Trigger.Time(tz = "Europe/Rome")))
    }

    @Test
    fun `a ringer template, a mixed condition tree and a deep condition tree do not qualify`() {
        assertFalse(DirectBootPolicy.allowsAction(Action.SetRinger("\${mode}")))
        assertFalse(DirectBootPolicy.allowsAction(Action.SetRinger("loud")))
        val mixed = Condition.And(
            listOf(Condition.TimeWindow("22:00", "07:00", "Europe/Rome"), Condition.Not(Condition.WifiConnected())),
        )
        assertFalse(DirectBootPolicy.allowsCondition(mixed))
        assertFalse(DirectBootPolicy.allowsCondition(Condition.And(emptyList())))
        var deep: Condition = Condition.Weekday(setOf(Day.MON), "Europe/Rome")
        repeat(9) { deep = Condition.Not(deep) }
        assertFalse(DirectBootPolicy.allowsCondition(deep))
    }

    @Test
    fun `a clock rule with switches qualifies`() {
        val rule = rule(
            actions = listOf(Action.SetDnd(DndMode.OFF), Action.SetRinger("normal"), Action.SetVolume(VolumeStream.ALARM, 80)),
            conditions = Condition.Weekday(setOf(Day.MON, Day.TUE), "Europe/Rome"),
        )
        assertTrue(DirectBootPolicy.qualifies(rule))
        assertEquals(emptySet(), DirectBootPolicy.reasons(rule))
    }

    @Test
    fun `each disqualifier has its closed reason`() {
        val base = rule()
        assertEquals(setOf(DirectBootPolicy.NO_FINGERPRINT), DirectBootPolicy.reasons(base.copy(approvalFingerprint = null)))
        assertEquals(setOf(DirectBootPolicy.TRIGGER), DirectBootPolicy.reasons(rule(trigger = Trigger.Battery(below = 10))))
        assertEquals(setOf(DirectBootPolicy.ACTIONS), DirectBootPolicy.reasons(rule(actions = listOf(Action.SetWifi(false)))))
        assertEquals(setOf(DirectBootPolicy.ACTIONS), DirectBootPolicy.reasons(rule(actions = emptyList())))
        assertEquals(
            setOf(DirectBootPolicy.ACTIONS),
            DirectBootPolicy.reasons(rule(actions = List(DirectBootPolicy.MAX_ACTIONS + 1) { Action.Vibrate(10) })),
        )
        assertEquals(
            setOf(DirectBootPolicy.CONDITIONS),
            DirectBootPolicy.reasons(rule(conditions = Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "normal"))),
        )
        assertEquals(setOf(DirectBootPolicy.CONFIRM), DirectBootPolicy.reasons(rule(confirm = ConfirmSpec(60_000))))
        assertEquals(
            setOf(DirectBootPolicy.SPAN),
            DirectBootPolicy.reasons(rule(exit = ExitSpec(Trigger.Time(cron = "0 9 * * *", tz = "Europe/Rome")))),
        )
        assertEquals(setOf(DirectBootPolicy.COOLDOWN), DirectBootPolicy.reasons(rule(cooldownMs = 60_000)))
        val withVars = rule(vars = listOf(VarBinding.TriggerPayload("msg", TriggerField.TEXT, confidentiality = ConfidentialityLabel.PRIVATE)))
        assertTrue(DirectBootPolicy.VARIABLES in DirectBootPolicy.reasons(withVars))
        assertFalse(DirectBootPolicy.qualifies(withVars))
    }

    private fun rule(
        trigger: Trigger = cron,
        actions: List<Action> = listOf(Action.SetDnd(DndMode.TOTAL)),
        conditions: Condition? = null,
        cooldownMs: Long = 0,
        confirm: ConfirmSpec? = null,
        exit: ExitSpec? = null,
        vars: List<VarBinding> = emptyList(),
    ): Automation {
        val unsigned = Automation(
            id = AutomationId("night"),
            name = "Night",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = trigger,
            actions = actions,
            vars = vars,
            conditions = conditions,
            cooldownMs = cooldownMs,
            confirm = confirm,
            exit = exit,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }
}
