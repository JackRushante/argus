package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8 regole-intervallo: quali azioni cambiano un valore da rimettere e come lo si rimette. */
class SpanPolicyTest {

    @Test
    fun `every value the spec lists has a slot, one per stream and per setting key`() {
        val expected = mapOf<Action, SpanSlot>(
            Action.SetVolume(VolumeStream.MEDIA, 100) to SpanSlot.Volume(VolumeStream.MEDIA),
            Action.SetVolume(VolumeStream.RING, 0) to SpanSlot.Volume(VolumeStream.RING),
            Action.SetRinger("silent") to SpanSlot.Ringer,
            Action.SetDnd(DndMode.TOTAL) to SpanSlot.Dnd,
            Action.SetBrightness(level = 80) to SpanSlot.Brightness,
            Action.SetBrightness(auto = true) to SpanSlot.Brightness,
            Action.SetRotation(true) to SpanSlot.Rotation,
            Action.SetWifi(false) to SpanSlot.Wifi,
            Action.SetBluetooth(true) to SpanSlot.Bluetooth,
            Action.SetMobileData(false) to SpanSlot.MobileData,
            Action.SetAirplane(true) to SpanSlot.Airplane,
            Action.SetLocation(false) to SpanSlot.Location,
            Action.SetNfc(false) to SpanSlot.Nfc,
            Action.SetBatterySaver(true) to SpanSlot.BatterySaver,
            Action.SetDarkMode(NightMode.ON) to SpanSlot.DarkMode,
            Action.SetFlashlight(true) to SpanSlot.Flashlight,
            Action.WriteSetting(SettingNamespace.GLOBAL, "stay_on_while_plugged_in", "3") to
                SpanSlot.Setting(SettingNamespace.GLOBAL, "stay_on_while_plugged_in"),
            Action.SetUserMode("auto") to SpanSlot.UserMode,
        )
        expected.forEach { (action, slot) -> assertEquals(slot, SpanPolicy.slotOf(action), "$action") }
        assertTrue(SpanSlot.Volume(VolumeStream.MEDIA).key != SpanSlot.Volume(VolumeStream.RING).key)
        assertTrue(
            SpanSlot.Setting(SettingNamespace.GLOBAL, "a").key != SpanSlot.Setting(SettingNamespace.SECURE, "a").key,
        )
    }

    @Test
    fun `actions that leave nothing to put back have no slot`() {
        listOf(
            Action.ShowNotification("t", "x"),
            Action.RunShell("echo"),
            Action.LaunchApp("com.example.app"),
            Action.Vibrate(100),
            Action.If(Condition.BooleanLiteral(true), then = listOf(Action.SetWifi(true))),
            // Una chiave interpolata non è una chiave approvata: niente da registrare.
            Action.WriteSetting(SettingNamespace.SYSTEM, "\${key}", "1"),
        ).forEach { assertNull(SpanPolicy.slotOf(it), "$it") }
    }

    @Test
    fun `each recorded value comes back with the typed action that sets it`() {
        assertEquals(
            listOf(SpanRestoreStep.Run(Action.SetVolume(VolumeStream.ALARM, 40))),
            SpanPolicy.restoreSteps(SpanRecord(SpanSlot.Volume(VolumeStream.ALARM), SpanValue.Percent(40))),
        )
        assertEquals(
            listOf(SpanRestoreStep.Run(Action.SetDnd(DndMode.OFF))),
            SpanPolicy.restoreSteps(SpanRecord(SpanSlot.Dnd, SpanValue.Dnd(DndMode.OFF))),
        )
        assertEquals(
            listOf(SpanRestoreStep.Run(Action.SetWifi(true))),
            SpanPolicy.restoreSteps(SpanRecord(SpanSlot.Wifi, SpanValue.Flag(true))),
        )
        assertEquals(
            listOf(SpanRestoreStep.Run(Action.WriteSetting(SettingNamespace.SECURE, "k", "v1"))),
            SpanPolicy.restoreSteps(SpanRecord(SpanSlot.Setting(SettingNamespace.SECURE, "k"), SpanValue.Text("v1"))),
        )
        assertEquals(
            listOf(SpanRestoreStep.Run(Action.SetUserMode("Casa"))),
            SpanPolicy.restoreSteps(SpanRecord(SpanSlot.UserMode, SpanValue.Mode("Casa"))),
        )
    }

    @Test
    fun `automatic brightness comes back after its level, so the end state is automatic again`() {
        assertEquals(
            listOf(
                SpanRestoreStep.Run(Action.SetBrightness(level = 30)),
                SpanRestoreStep.Run(Action.SetBrightness(auto = true)),
            ),
            SpanPolicy.restoreSteps(SpanRecord(SpanSlot.Brightness, SpanValue.Brightness(30, auto = true))),
        )
        assertEquals(
            listOf(SpanRestoreStep.Run(Action.SetBrightness(level = 30))),
            SpanPolicy.restoreSteps(SpanRecord(SpanSlot.Brightness, SpanValue.Brightness(30, auto = false))),
        )
    }

    @Test
    fun `no mode before the start goes back to no mode`() {
        assertEquals(
            listOf(SpanRestoreStep.ClearUserMode),
            SpanPolicy.restoreSteps(SpanRecord(SpanSlot.UserMode, SpanValue.Mode(null))),
        )
    }

    @Test
    fun `a value of the wrong shape is never put back`() {
        listOf(
            SpanRecord(SpanSlot.Volume(VolumeStream.MEDIA), SpanValue.Flag(true)),
            SpanRecord(SpanSlot.Volume(VolumeStream.MEDIA), SpanValue.Percent(101)),
            SpanRecord(SpanSlot.Ringer, SpanValue.Ringer("loud")),
            SpanRecord(SpanSlot.Brightness, SpanValue.Brightness(-1, auto = false)),
            SpanRecord(SpanSlot.Wifi, SpanValue.Percent(1)),
            // Un valore letto con caratteri di controllo o un segnaposto non diventa una scrittura.
            SpanRecord(SpanSlot.Setting(SettingNamespace.GLOBAL, "k"), SpanValue.Text("a\nb")),
            SpanRecord(SpanSlot.Setting(SettingNamespace.GLOBAL, "k"), SpanValue.Text("\${x}")),
            SpanRecord(SpanSlot.UserMode, SpanValue.Mode("  spazi  ")),
        ).forEach { assertNull(SpanPolicy.restoreSteps(it), "$it") }
    }

    @Test
    fun `the snapshot round-trips through the wire format`() {
        val records = listOf(
            SpanRecord(SpanSlot.Volume(VolumeStream.MEDIA), SpanValue.Percent(40)),
            SpanRecord(SpanSlot.Brightness, SpanValue.Brightness(55, auto = true)),
            SpanRecord(SpanSlot.DarkMode, SpanValue.Night(NightMode.AUTO)),
            SpanRecord(SpanSlot.Setting(SettingNamespace.SYSTEM, "screen_off_timeout"), SpanValue.Text("30000")),
            SpanRecord(SpanSlot.UserMode, SpanValue.Mode(null)),
        )
        records.forEach { record ->
            val encoded = ArgusJson.encodeToString(SpanRecord.serializer(), record)
            assertEquals(record, ArgusJson.decodeFromString(SpanRecord.serializer(), encoded))
        }
    }

    @Test
    fun `a span starts many times and ends after its start`() {
        assertFalse(SpanPolicy.supportsStartTrigger(Trigger.Immediate))
        assertFalse(SpanPolicy.supportsStartTrigger(Trigger.Time(at = "2026-12-01T08:00", tz = "Europe/Rome")))
        assertFalse(SpanPolicy.supportsStartTrigger(Trigger.Time(afterMs = 60_000, tz = "Europe/Rome")))
        assertTrue(SpanPolicy.supportsStartTrigger(Trigger.Time(cron = "0 22 * * *", tz = "Europe/Rome")))

        assertFalse(SpanPolicy.supportsExitTrigger(Trigger.Immediate))
        assertFalse(SpanPolicy.supportsExitTrigger(Trigger.Manual))
        assertFalse(SpanPolicy.supportsExitTrigger(Trigger.Time(afterMs = 60_000, tz = "Europe/Rome")))
        assertFalse(
            SpanPolicy.supportsExitTrigger(Trigger.Geofence(45.0, 9.0, 100.0, Transition.DWELL, loiteringDelayMs = 60_000)),
        )
        assertTrue(SpanPolicy.supportsExitTrigger(Trigger.Geofence(45.0, 9.0, 100.0, Transition.EXIT)))
        assertTrue(SpanPolicy.supportsExitTrigger(Trigger.Time(cron = "0 7 * * *", tz = "Europe/Rome")))
        assertTrue(SpanPolicy.supportsExitTrigger(Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED)))
    }

    @Test
    fun `the listened triggers of a span are its start and its end`() {
        val start = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED, "Auto")
        val end = Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED, "Auto")
        val rule = Automation(
            AutomationId("s"), "s", CreatedBy.USER, AutomationStatus.ARMED, start, listOf(Action.SetWifi(true)),
            exit = ExitSpec(end, actions = listOf(Action.Vibrate(10))),
        )
        assertEquals(listOf(start, end), rule.listenedTriggers)
        assertEquals(listOf(Action.SetWifi(true), Action.Vibrate(10)), rule.allActions)
        assertEquals(listOf<Trigger>(start), rule.copy(exit = null).listenedTriggers)
    }
}
