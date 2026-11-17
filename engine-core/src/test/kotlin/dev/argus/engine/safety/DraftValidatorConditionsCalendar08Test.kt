package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Day
import dev.argus.engine.model.FeatureGate
import dev.argus.engine.model.FeatureGates
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.VarBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Limiti delle condizioni 0.8 di contesto e del trigger `calendar_event`. */
class DraftValidatorConditionsCalendar08Test {
    private val validator = DraftValidator(knownTools = setOf("notify.show"))

    private fun errors(
        conditions: Condition? = null,
        trigger: Trigger = Trigger.Manual,
        actions: List<Action> = listOf(Action.Vibrate(100)),
        vars: List<VarBinding> = emptyList(),
    ): List<String> = validator.validate(
        AutomationDraft(name = "r", trigger = trigger, actions = actions, conditions = conditions, vars = vars),
        emptySet(),
    ).filter { it.severity == Severity.ERROR }.map { it.code }

    @Test
    fun `the five conditions and the calendar trigger are switched on`() {
        listOf(
            FeatureGate.CONDITION_WEEKDAY,
            FeatureGate.CONDITION_WIFI_CONNECTED,
            FeatureGate.CONDITION_BLUETOOTH_CONNECTED,
            FeatureGate.CONDITION_HEADSET_CONNECTED,
            FeatureGate.CONDITION_CALENDAR_BUSY,
            FeatureGate.TRIGGER_CALENDAR_EVENT,
        ).forEach { assertTrue(it.enabled, "$it") }
    }

    @Test
    fun `valid conditions arm, also nested`() {
        listOf(
            Condition.Weekday(setOf(Day.MON, Day.FRI), "Europe/Rome"),
            Condition.WifiConnected(),
            Condition.WifiConnected("Casa"),
            Condition.BluetoothConnected(),
            Condition.BluetoothConnected("AA:BB:CC:DD:EE:FF"),
            Condition.HeadsetConnected(AudioOutputKind.BLUETOOTH),
            Condition.CalendarBusy(),
            Condition.CalendarBusy("Riunione", "Lavoro"),
        ).forEach { condition ->
            assertEquals(emptyList(), errors(condition), "$condition")
            assertEquals(emptyList(), errors(Condition.Not(Condition.And(listOf(condition)))), "$condition")
            assertTrue(FeatureGates.disabledIn(AutomationDraft("r", Trigger.Manual, listOf(Action.Vibrate(1)), conditions = condition)).isEmpty())
        }
    }

    @Test
    fun `weekday needs a day and a real zone`() {
        assertEquals(listOf("weekday_invalid"), errors(Condition.Weekday(emptySet(), "UTC")))
        listOf("", "Mars/Base", "europe/rome", "x".repeat(65)).forEach { tz ->
            assertEquals(listOf("weekday_invalid"), errors(Condition.Weekday(setOf(Day.MON), tz)), "«$tz»")
        }
    }

    @Test
    fun `the emptied personal fields of a shared rule are rejected`() {
        // Il PersonalDataStripper lascia "" nei campi da completare.
        assertEquals(listOf("wifi_ssid_invalid"), errors(Condition.WifiConnected("")))
        assertEquals(listOf("bluetooth_device_invalid"), errors(Condition.BluetoothConnected("")))
        assertEquals(listOf("calendar_filter_invalid"), errors(Condition.CalendarBusy(titleMatch = "")))
        assertEquals(listOf("calendar_filter_invalid"), errors(Condition.CalendarBusy(calendar = " ")))
        assertEquals(
            listOf("calendar_filter_invalid"),
            errors(trigger = Trigger.CalendarEvent(CalendarPhase.START, titleMatch = "")),
        )
        assertEquals(
            listOf("calendar_filter_invalid"),
            errors(trigger = Trigger.CalendarEvent(CalendarPhase.END, calendar = "")),
        )
    }

    @Test
    fun `text fields are bounded and free of control characters`() {
        assertEquals(listOf("wifi_ssid_invalid"), errors(Condition.WifiConnected("x".repeat(33))))
        assertEquals(listOf("wifi_ssid_invalid"), errors(Condition.WifiConnected("Casa\n")))
        assertEquals(listOf("bluetooth_device_invalid"), errors(Condition.BluetoothConnected("x".repeat(249))))
        assertEquals(listOf("calendar_filter_invalid"), errors(Condition.CalendarBusy(titleMatch = "x".repeat(201))))
        assertEquals(listOf("calendar_filter_invalid"), errors(Condition.CalendarBusy(calendar = "a\u0000b")))
    }

    @Test
    fun `calendar offset is bounded`() {
        listOf(-120, 0, 120).forEach { offset ->
            assertEquals(emptyList(), errors(trigger = Trigger.CalendarEvent(CalendarPhase.START, offset)), "$offset")
        }
        listOf(-121, 121, Int.MIN_VALUE).forEach { offset ->
            assertEquals(
                listOf("calendar_offset_invalid"),
                errors(trigger = Trigger.CalendarEvent(CalendarPhase.START, offset)),
                "$offset",
            )
        }
    }

    @Test
    fun `a calendar rule may never start shell commands`() {
        val codes = errors(
            trigger = Trigger.CalendarEvent(CalendarPhase.START),
            actions = listOf(Action.RunShell("echo hi")),
        )
        assertTrue("shell_external_trigger" in codes)
    }

    @Test
    fun `a calendar rule can bind the event title, as external private data`() {
        val title = VarBinding.TriggerPayload("titolo", TriggerField.TITLE, confidentiality = ConfidentialityLabel.PRIVATE)
        val notify = listOf(Action.ShowNotification("Agenda", "\${titolo}"))
        assertEquals(emptyList(), errors(trigger = Trigger.CalendarEvent(CalendarPhase.START), actions = notify, vars = listOf(title)))
        assertEquals(
            listOf("var_trigger_field_unsupported"),
            errors(
                trigger = Trigger.CalendarEvent(CalendarPhase.START),
                actions = notify,
                vars = listOf(title.copy(field = TriggerField.TEXT)),
            ),
        )
        assertEquals(
            listOf("var_trigger_underclassified"),
            errors(
                trigger = Trigger.CalendarEvent(CalendarPhase.START),
                actions = notify,
                vars = listOf(title.copy(confidentiality = ConfidentialityLabel.PUBLIC)),
            ),
        )
    }

    @Test
    fun `wifi with a network name also needs the wifi identity capability`() {
        fun caps(c: Condition) = CapabilityRequirements.derive(
            AutomationDraft("r", Trigger.Manual, listOf(Action.Vibrate(1)), conditions = c),
        )
        assertTrue(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY !in caps(Condition.WifiConnected()))
        assertTrue(CapabilityIds.STATE_WIFI_CONNECTED in caps(Condition.WifiConnected()))
        assertTrue(
            caps(Condition.WifiConnected("Casa")).containsAll(
                setOf(CapabilityIds.STATE_WIFI_CONNECTED, CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY),
            ),
        )
    }
}
