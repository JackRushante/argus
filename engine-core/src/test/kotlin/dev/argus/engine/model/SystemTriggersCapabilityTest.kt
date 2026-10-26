package dev.argus.engine.model

import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.ConflictDetector
import dev.argus.engine.safety.StaticShellSafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requisiti di capacità, conflitti, shell e parser telefonia dei trigger 0.4.1. */
class SystemTriggersCapabilityTest {
    private fun required(trigger: Trigger) = CapabilityRequirements.derive(trigger, emptyList())

    @Test
    fun `each system trigger requires only its own capability`() {
        assertEquals(setOf(CapabilityIds.TRIGGER_SCREEN), required(Trigger.Screen(ScreenEvent.ON)))
        assertEquals(setOf(CapabilityIds.TRIGGER_BATTERY), required(Trigger.Battery(below = 20)))
        assertEquals(
            setOf(CapabilityIds.TRIGGER_AUDIO_OUTPUT),
            required(Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_MODE_CHANGED),
            required(Trigger.ModeChanged(ModeKind.DND)),
        )
        assertEquals(setOf(CapabilityIds.TRIGGER_MEDIA), required(Trigger.Media(MediaPlayState.PLAYING)))
    }

    @Test
    fun `a missed call needs the call grants and the sim filter adds its own capability`() {
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_CALL),
            required(Trigger.PhoneState(PhoneEvent.MISSED_CALL)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_CALL, CapabilityIds.TRIGGER_PHONE_SIM),
            required(Trigger.PhoneState(PhoneEvent.MISSED_CALL, sim = 2)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_SMS, CapabilityIds.TRIGGER_PHONE_SIM),
            required(Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, sim = 1)),
        )
        // Una regola esistente senza `sim` mantiene i requisiti di prima (non finisce «da rivedere»).
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_SMS),
            required(Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, number = "+39")),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_CALL),
            required(Trigger.PhoneState(PhoneEvent.INCOMING_CALL)),
        )
    }

    @Test
    fun `screen on and off rules are complementary and do not conflict`() {
        fun rule(id: String, event: ScreenEvent, wifi: Boolean) = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Screen(event),
            actions = listOf(Action.SetWifi(wifi)),
        )
        val detector = ConflictDetector()
        assertTrue(detector.detect(listOf(rule("on", ScreenEvent.ON, true), rule("off", ScreenEvent.OFF, false))).isEmpty())
        // ON e UNLOCKED non sono complementari: possono far parte della stessa sessione.
        assertEquals(
            1,
            detector.detect(listOf(rule("on", ScreenEvent.ON, true), rule("unlock", ScreenEvent.UNLOCKED, false))).size,
        )
    }

    @Test
    fun `device triggers can run autonomous shell and media or phone cannot`() {
        assertTrue(StaticShellSafety.allows(Trigger.Screen(ScreenEvent.ON), emptySet()))
        assertTrue(StaticShellSafety.allows(Trigger.Battery(below = 10), emptySet()))
        assertTrue(StaticShellSafety.allows(Trigger.ModeChanged(ModeKind.RINGER), emptySet()))
        assertFalse(StaticShellSafety.allows(Trigger.Media(MediaPlayState.PLAYING), emptySet()))
        assertFalse(StaticShellSafety.allows(Trigger.PhoneState(PhoneEvent.MISSED_CALL), emptySet()))

        // Il legame fra trigger approvato ed evento live si verifica anche qui.
        assertTrue(
            StaticShellSafety.allows(
                Trigger.Battery(below = 20),
                TriggerEvent.BatteryLevelChanged(21, 19),
                emptySet(),
            ),
        )
        assertFalse(
            StaticShellSafety.allows(
                Trigger.Battery(below = 20),
                TriggerEvent.BatteryLevelChanged(19, 18),
                emptySet(),
            ),
        )
        assertFalse(
            StaticShellSafety.allows(
                Trigger.Screen(ScreenEvent.ON),
                TriggerEvent.ScreenChanged(ScreenEvent.OFF),
                emptySet(),
            ),
        )
        assertFalse(
            StaticShellSafety.allows(
                Trigger.Media(MediaPlayState.PLAYING),
                TriggerEvent.MediaPlaybackChanged(MediaPlayState.PLAYING),
                emptySet(),
            ),
        )
    }

    @Test
    fun `phone parser carries the sim slot but keeps it out of the event identity`() {
        val parser = PhoneEventParser()
        val withSim = assertNotNull(parser.parse(PhoneEvent.MISSED_CALL, "333", null, 1_000L, sim = 2))
        val withoutSim = assertNotNull(parser.parse(PhoneEvent.MISSED_CALL, "333", null, 1_000L))
        assertEquals(2, (withSim.event as TriggerEvent.PhoneStateChanged).sim)
        assertNull((withoutSim.event as TriggerEvent.PhoneStateChanged).sim)
        assertEquals(withSim.id, withoutSim.id)
        // Un id di un altro tipo di chiamata non coincide.
        assertTrue(
            withSim.id != parser.parse(PhoneEvent.CALL_ENDED, "333", null, 1_000L, sim = 2)!!.id,
        )
        // Fuori da 1..2 lo slot è ignoto, mai un valore inventato.
        assertNull((parser.parse(PhoneEvent.SMS_RECEIVED, "+39", "x", 1L, sim = 3)!!.event as TriggerEvent.PhoneStateChanged).sim)
    }
}
