package dev.argus.engine.runtime

import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Day
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Condizioni 0.8: weekday, wifi_connected, bluetooth_connected, headset_connected, calendar_busy. */
class Conditions08Test {
    // Lunedì 16 novembre 2026, 23:30 UTC = martedì 00:30 a Roma.
    private val now = Instant.parse("2026-11-16T23:30:00Z")
    private val evaluator = ConditionEvaluator(Clock.fixed(now, ZoneOffset.UTC))

    private fun result(condition: Condition, state: DeviceState = DeviceState()) =
        evaluator.result(condition, state)

    private fun facts(facts: DeviceFacts) = DeviceState(facts = facts)

    private val met = ConditionEvaluator.Result.MET
    private val notMet = ConditionEvaluator.Result.NOT_MET
    private val unknown = ConditionEvaluator.Result.STATE_UNAVAILABLE

    // --- weekday ---------------------------------------------------------------------------------

    @Test
    fun `weekday reads the day in the rule's time zone`() {
        assertEquals(met, result(Condition.Weekday(setOf(Day.MON), "UTC")))
        assertEquals(notMet, result(Condition.Weekday(setOf(Day.MON), "Europe/Rome")))
        assertEquals(met, result(Condition.Weekday(setOf(Day.TUE, Day.SUN), "Europe/Rome")))
        assertEquals(notMet, result(Condition.Not(Condition.Weekday(setOf(Day.TUE), "Europe/Rome"))))
    }

    @Test
    fun `weekday with a zone that cannot be read is unknown, also under not`() {
        assertEquals(unknown, result(Condition.Weekday(setOf(Day.MON), "Mars/Base")))
        assertEquals(unknown, result(Condition.Not(Condition.Weekday(setOf(Day.MON), "Mars/Base"))))
    }

    @Test
    fun `weekday plans no device read`() {
        assertTrue(StateReadPlanner.forCondition(Condition.Weekday(setOf(Day.MON), "UTC")).isEmpty)
    }

    // --- wifi_connected --------------------------------------------------------------------------

    @Test
    fun `wifi without ssid needs only the link`() {
        val any = Condition.WifiConnected()
        assertEquals(met, result(any, facts(DeviceFacts(wifi = WifiLink(connected = true)))))
        assertEquals(notMet, result(any, facts(DeviceFacts(wifi = WifiLink(connected = false)))))
        assertEquals(unknown, result(any))
        assertEquals(unknown, result(Condition.Not(any)))
        assertEquals(StateReadRequest(facts = setOf(DeviceFact.WIFI)), StateReadPlanner.forCondition(any))
    }

    @Test
    fun `wifi with ssid matches the name exactly and is unknown when the name cannot be read`() {
        val home = Condition.WifiConnected("Casa")
        assertEquals(met, result(home, facts(DeviceFacts(wifi = WifiLink(true, "Casa")))))
        assertEquals(notMet, result(home, facts(DeviceFacts(wifi = WifiLink(true, "casa")))))
        assertEquals(notMet, result(home, facts(DeviceFacts(wifi = WifiLink(true, "Casa 5G")))))
        // Scollegato: nessuna rete, quindi non quella.
        assertEquals(notMet, result(home, facts(DeviceFacts(wifi = WifiLink(false)))))
        // Collegato ma senza nome leggibile: mai un default.
        assertEquals(unknown, result(home, facts(DeviceFacts(wifi = WifiLink(true, null)))))
        assertEquals(unknown, result(Condition.Not(home), facts(DeviceFacts(wifi = WifiLink(true, null)))))
        assertEquals(
            StateReadRequest(facts = setOf(DeviceFact.WIFI, DeviceFact.WIFI_SSID)),
            StateReadPlanner.forCondition(home),
        )
    }

    @Test
    fun `a request without the ssid fact drops a name read for another rule`() {
        val state = facts(DeviceFacts(wifi = WifiLink(true, "Casa")))
        val projected = StateReadRequest(facts = setOf(DeviceFact.WIFI)).project(state)
        assertEquals(WifiLink(true, null), projected.facts.wifi)
        assertEquals(DeviceFacts(), StateReadRequest(keys = setOf("wifi")).project(state).facts)
    }

    @Test
    fun `the ssid fact is still missing after a plain wifi read`() {
        val known = StateReadRequest(facts = setOf(DeviceFact.WIFI))
        val wanted = StateReadRequest(facts = setOf(DeviceFact.WIFI, DeviceFact.WIFI_SSID))
        assertEquals(StateReadRequest(facts = setOf(DeviceFact.WIFI_SSID)), wanted.missingFrom(known))
        assertFalse(wanted.isEmpty)
    }

    @Test
    fun `wifi names stay out of logs`() {
        assertFalse("Casa" in WifiLink(true, "Casa").toString())
    }

    // --- bluetooth_connected ---------------------------------------------------------------------

    private fun bt(vararg devices: BluetoothLink, complete: Boolean = true) =
        facts(DeviceFacts(bluetooth = BluetoothLinks(devices.toList(), complete)))

    private val buds = BluetoothLink("AA:BB:CC:DD:EE:FF", "Pixel Buds")

    @Test
    fun `any bluetooth device`() {
        val any = Condition.BluetoothConnected()
        assertEquals(met, result(any, bt(buds)))
        assertEquals(notMet, result(any, bt()))
        // Lista parziale (profili non letti) e nessun dispositivo: non si può dire «nessuno».
        assertEquals(unknown, result(any, bt(complete = false)))
        assertEquals(met, result(any, bt(buds, complete = false)))
        assertEquals(unknown, result(any))
        assertEquals(StateReadRequest(facts = setOf(DeviceFact.BLUETOOTH)), StateReadPlanner.forCondition(any))
    }

    @Test
    fun `bluetooth device by name ignoring case or by mac`() {
        assertEquals(met, result(Condition.BluetoothConnected("pixel buds"), bt(buds)))
        assertEquals(met, result(Condition.BluetoothConnected("aa:bb:cc:dd:ee:ff"), bt(buds)))
        assertEquals(notMet, result(Condition.BluetoothConnected("Pixel"), bt(buds)))
        assertEquals(notMet, result(Condition.BluetoothConnected("11:22:33:44:55:66"), bt(buds)))
        assertEquals(notMet, result(Condition.BluetoothConnected("Car"), bt()))
    }

    @Test
    fun `a connected device without a readable name keeps a name filter unknown`() {
        val anonymous = BluetoothLink("11:22:33:44:55:66", null)
        assertEquals(unknown, result(Condition.BluetoothConnected("Car"), bt(anonymous)))
        assertEquals(unknown, result(Condition.Not(Condition.BluetoothConnected("Car")), bt(anonymous)))
        // Il MAC c'è: quel filtro si decide.
        assertEquals(notMet, result(Condition.BluetoothConnected("AA:BB:CC:DD:EE:01"), bt(anonymous)))
        // Un altro dispositivo combacia: vero comunque.
        assertEquals(met, result(Condition.BluetoothConnected("Pixel Buds"), bt(anonymous, buds)))
        // Lista parziale senza match: ignoto.
        assertEquals(unknown, result(Condition.BluetoothConnected("Car"), bt(buds, complete = false)))
    }

    @Test
    fun `bluetooth names and addresses stay out of logs`() {
        assertFalse("Pixel" in buds.toString() || "AA:BB" in buds.toString())
    }

    // --- headset_connected -----------------------------------------------------------------------

    @Test
    fun `headset by kind`() {
        val wired = facts(DeviceFacts(audioOutputs = setOf(AudioOutputKind.WIRED)))
        val none = facts(DeviceFacts(audioOutputs = emptySet()))
        assertEquals(met, result(Condition.HeadsetConnected(), wired))
        assertEquals(met, result(Condition.HeadsetConnected(AudioOutputKind.WIRED), wired))
        assertEquals(notMet, result(Condition.HeadsetConnected(AudioOutputKind.BLUETOOTH), wired))
        assertEquals(notMet, result(Condition.HeadsetConnected(), none))
        assertEquals(unknown, result(Condition.HeadsetConnected()))
        assertEquals(unknown, result(Condition.Not(Condition.HeadsetConnected())))
        assertEquals(
            StateReadRequest(facts = setOf(DeviceFact.AUDIO_OUTPUTS)),
            StateReadPlanner.forCondition(Condition.HeadsetConnected(AudioOutputKind.WIRED)),
        )
    }

    // --- calendar_busy ---------------------------------------------------------------------------

    private val nowMs = now.toEpochMilli()
    private val minute = 60_000L

    private fun instance(
        title: String? = "Riunione progetto",
        calendar: String? = "Lavoro",
        begin: Long = nowMs - 10 * minute,
        end: Long = nowMs + 20 * minute,
        allDay: Boolean = false,
        declined: Boolean = false,
        free: Boolean = false,
        canceled: Boolean = false,
    ) = CalendarInstance(1, begin, end, title, calendar, allDay, declined, free, canceled)

    private fun cal(vararg instances: CalendarInstance) =
        facts(DeviceFacts(calendarNow = instances.toList()))

    @Test
    fun `calendar busy when an event is in progress now`() {
        val any = Condition.CalendarBusy()
        assertEquals(met, result(any, cal(instance())))
        assertEquals(notMet, result(any, cal()))
        assertEquals(notMet, result(any, cal(instance(begin = nowMs + minute, end = nowMs + 2 * minute))))
        // La fine è esclusa.
        assertEquals(notMet, result(any, cal(instance(begin = nowMs - minute, end = nowMs))))
        assertEquals(met, result(any, cal(instance(begin = nowMs, end = nowMs + minute))))
        assertEquals(StateReadRequest(facts = setOf(DeviceFact.CALENDAR_NOW)), StateReadPlanner.forCondition(any))
    }

    @Test
    fun `all-day, declined, free and canceled events do not count`() {
        val any = Condition.CalendarBusy()
        listOf(
            instance(allDay = true),
            instance(declined = true),
            instance(free = true),
            instance(canceled = true),
        ).forEach { assertEquals(notMet, result(any, cal(it)), "$it") }
    }

    @Test
    fun `calendar filters on title and calendar name`() {
        assertEquals(met, result(Condition.CalendarBusy(titleMatch = "RIUNIONE"), cal(instance())))
        assertEquals(notMet, result(Condition.CalendarBusy(titleMatch = "palestra"), cal(instance())))
        assertEquals(met, result(Condition.CalendarBusy(calendar = "lavoro"), cal(instance())))
        assertEquals(notMet, result(Condition.CalendarBusy(calendar = "Lavoro 2"), cal(instance())))
        // Un evento senza titolo non contiene il testo cercato.
        assertEquals(notMet, result(Condition.CalendarBusy(titleMatch = "x"), cal(instance(title = null))))
        assertEquals(
            met,
            result(Condition.CalendarBusy("progetto", "LAVORO"), cal(instance(calendar = "Casa"), instance())),
        )
    }

    @Test
    fun `an unreadable calendar is unknown, also under not`() {
        assertEquals(unknown, result(Condition.CalendarBusy()))
        assertEquals(unknown, result(Condition.Not(Condition.CalendarBusy())))
    }

    @Test
    fun `calendar titles stay out of logs`() {
        assertFalse("Riunione" in instance().toString() || "Lavoro" in instance().toString())
    }

    // --- merge and projection --------------------------------------------------------------------

    @Test
    fun `projection keeps only the facts asked for`() {
        val state = DeviceState(
            facts = DeviceFacts(
                wifi = WifiLink(true, "Casa"),
                bluetooth = BluetoothLinks(listOf(buds), true),
                audioOutputs = setOf(AudioOutputKind.WIRED),
                calendarNow = listOf(instance()),
            ),
        )
        val only = StateReadRequest(facts = setOf(DeviceFact.AUDIO_OUTPUTS)).project(state).facts
        assertEquals(DeviceFacts(audioOutputs = setOf(AudioOutputKind.WIRED)), only)
        val all = StateReadRequest(facts = DeviceFact.entries.toSet()).project(state)
        assertEquals(state, all)
        assertEquals(
            setOf(DeviceFact.WIFI, DeviceFact.CALENDAR_NOW),
            (StateReadRequest(facts = setOf(DeviceFact.WIFI)) + StateReadRequest(facts = setOf(DeviceFact.CALENDAR_NOW))).facts,
        )
    }
}
