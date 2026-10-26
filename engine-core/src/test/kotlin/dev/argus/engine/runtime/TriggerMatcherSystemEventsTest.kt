package dev.argus.engine.runtime

import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Matcher dei trigger di sistema senza Shizuku (0.4.1). */
class TriggerMatcherSystemEventsTest {
    private val m = TriggerMatcher()

    @Test
    fun `screen matches only its own event`() {
        val spec = Trigger.Screen(ScreenEvent.UNLOCKED)
        assertTrue(m.matches(spec, TriggerEvent.ScreenChanged(ScreenEvent.UNLOCKED)))
        assertFalse(m.matches(spec, TriggerEvent.ScreenChanged(ScreenEvent.ON)))
        assertFalse(m.matches(spec, TriggerEvent.ScreenChanged(ScreenEvent.OFF)))
    }

    @Test
    fun `battery below fires only when the level crosses the threshold downwards`() {
        val spec = Trigger.Battery(below = 20)
        assertTrue(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 20, current = 19)))
        // Un salto che scavalca la soglia conta.
        assertTrue(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 23, current = 17)))
        // Già sotto: scendere ancora non è un nuovo attraversamento.
        assertFalse(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 19, current = 18)))
        // Alla soglia esatta non è ancora "sotto".
        assertFalse(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 21, current = 20)))
        // Salire non scatta mai un below.
        assertFalse(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 19, current = 21)))
    }

    @Test
    fun `battery above fires only when the level crosses the threshold upwards`() {
        val spec = Trigger.Battery(above = 80)
        assertTrue(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 80, current = 81)))
        assertTrue(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 78, current = 83)))
        assertFalse(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 81, current = 82)))
        assertFalse(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 79, current = 80)))
        assertFalse(m.matches(spec, TriggerEvent.BatteryLevelChanged(previous = 82, current = 79)))
    }

    @Test
    fun `a malformed battery spec never fires`() {
        val event = TriggerEvent.BatteryLevelChanged(previous = 50, current = 10)
        assertFalse(m.matches(Trigger.Battery(), event))
        assertFalse(m.matches(Trigger.Battery(below = 20, above = 5), event))
    }

    @Test
    fun `audio output matches kind and state and ANY covers both personal outputs`() {
        val wired = Trigger.AudioOutput(AudioOutputKind.WIRED, ConnState.CONNECTED)
        val bluetooth = Trigger.AudioOutput(AudioOutputKind.BLUETOOTH, ConnState.CONNECTED)
        val any = Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED)
        val wiredIn = TriggerEvent.AudioOutputChanged(AudioOutputKind.WIRED, ConnState.CONNECTED)
        val bluetoothIn = TriggerEvent.AudioOutputChanged(AudioOutputKind.BLUETOOTH, ConnState.CONNECTED)

        assertTrue(m.matches(wired, wiredIn))
        assertFalse(m.matches(wired, bluetoothIn))
        assertTrue(m.matches(bluetooth, bluetoothIn))
        assertFalse(m.matches(bluetooth, wiredIn))
        assertTrue(m.matches(any, wiredIn))
        assertTrue(m.matches(any, bluetoothIn))
        assertFalse(
            m.matches(any, TriggerEvent.AudioOutputChanged(AudioOutputKind.WIRED, ConnState.DISCONNECTED)),
        )
    }

    @Test
    fun `mode changed matches the mode and the target value or any change`() {
        val toTotal = Trigger.ModeChanged(ModeKind.DND, "total")
        val anyRinger = Trigger.ModeChanged(ModeKind.RINGER)
        val dndTotal = TriggerEvent.DeviceModeChanged(ModeKind.DND, to = "total", from = "off")
        val ringerSilent = TriggerEvent.DeviceModeChanged(ModeKind.RINGER, to = "silent", from = "normal")

        assertTrue(m.matches(toTotal, dndTotal))
        assertFalse(m.matches(toTotal, TriggerEvent.DeviceModeChanged(ModeKind.DND, "priority", "off")))
        assertFalse(m.matches(toTotal, ringerSilent))
        assertTrue(m.matches(anyRinger, ringerSilent))
        assertTrue(m.matches(anyRinger, TriggerEvent.DeviceModeChanged(ModeKind.RINGER, "vibrate", null)))
        assertFalse(m.matches(anyRinger, dndTotal))
    }

    @Test
    fun `media matches its playback state`() {
        val playing = Trigger.Media(MediaPlayState.PLAYING)
        assertTrue(m.matches(playing, TriggerEvent.MediaPlaybackChanged(MediaPlayState.PLAYING)))
        assertFalse(m.matches(playing, TriggerEvent.MediaPlaybackChanged(MediaPlayState.STOPPED)))
    }

    @Test
    fun `an event of one family never matches a trigger of another`() {
        assertFalse(m.matches(Trigger.Screen(ScreenEvent.ON), TriggerEvent.MediaPlaybackChanged(MediaPlayState.PLAYING)))
        assertFalse(m.matches(Trigger.Media(MediaPlayState.PLAYING), TriggerEvent.ScreenChanged(ScreenEvent.ON)))
    }

    @Test
    fun `missed call matches only its own event`() {
        val spec = Trigger.PhoneState(PhoneEvent.MISSED_CALL, number = "333")
        assertTrue(m.matches(spec, TriggerEvent.PhoneStateChanged(PhoneEvent.MISSED_CALL, "333", sim = 1)))
        assertFalse(m.matches(spec, TriggerEvent.PhoneStateChanged(PhoneEvent.CALL_ENDED, "333", sim = 1)))
    }

    @Test
    fun `the sim filter matches the slot and fails closed when the slot is unknown`() {
        val sim2 = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, sim = 2)
        assertTrue(m.matches(sim2, TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+39", "x", sim = 2)))
        assertFalse(m.matches(sim2, TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+39", "x", sim = 1)))
        // Slot non determinabile: la regola col filtro NON scatta.
        assertFalse(m.matches(sim2, TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+39", "x", sim = null)))
        // Senza filtro il comportamento di prima: qualsiasi SIM, anche ignota.
        val anySim = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)
        assertTrue(m.matches(anySim, TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+39", "x", sim = null)))
        assertTrue(m.matches(anySim, TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+39", "x", sim = 2)))
    }
}
