package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Trigger di sistema senza Shizuku (0.4.1): wire, round-trip e impronte delle regole esistenti. */
class SystemTriggersModelTest {
    private fun encode(trigger: Trigger) = ArgusJson.encodeToString(Trigger.serializer(), trigger)
    private fun decode(json: String) = ArgusJson.decodeFromString(Trigger.serializer(), json)

    @Test
    fun `every new trigger round-trips with the exact wire names of the registry`() {
        val cases = mapOf<Trigger, String>(
            Trigger.Screen(ScreenEvent.UNLOCKED) to """{"type":"screen","event":"UNLOCKED"}""",
            Trigger.Battery(below = 20) to """{"type":"battery","below":20,"above":null}""",
            Trigger.Battery(above = 80) to """{"type":"battery","below":null,"above":80}""",
            Trigger.AudioOutput(AudioOutputKind.BLUETOOTH, ConnState.DISCONNECTED) to
                """{"type":"audio_output","kind":"BLUETOOTH","state":"DISCONNECTED"}""",
            Trigger.ModeChanged(ModeKind.DND, "priority") to
                """{"type":"mode_changed","what":"DND","to":"priority"}""",
            Trigger.ModeChanged(ModeKind.RINGER) to """{"type":"mode_changed","what":"RINGER","to":null}""",
            Trigger.Media(MediaPlayState.STOPPED) to """{"type":"media","state":"STOPPED"}""",
            Trigger.PhoneState(PhoneEvent.MISSED_CALL, sim = 2) to
                """{"type":"phone_state","event":"MISSED_CALL","number":null,"textMatch":null,"sim":2}""",
        )
        cases.forEach { (trigger, wire) ->
            assertEquals(wire, encode(trigger))
            assertEquals(trigger, decode(wire))
        }
    }

    @Test
    fun `phone_state without sim keeps its bytes and the pinned approval fingerprint`() {
        val trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, textMatch = "codice")
        val wire = encode(trigger)
        assertFalse("sim" in wire, wire)
        assertEquals(
            """{"type":"phone_state","event":"SMS_RECEIVED","number":null,"textMatch":"codice"}""",
            wire,
        )
        // Stesso fixture e stesso hash PINNATO di V1FingerprintCompatibilityTest ("phone-state").
        val automation = Automation(
            id = AutomationId("v1-phone"),
            name = "v1-phone",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = decode(wire),
            actions = listOf(Action.ShowNotification("fixture", "fixture")),
            enabled = false,
            priority = 3,
            cooldownMs = 12_345,
        )
        assertEquals(
            "63ea2dc5dbaf1fadea1de24512ed890a6436135e480f2e57bd49ba05ca907179",
            ApprovalFingerprints.of(automation).value,
        )
    }

    @Test
    fun `a stored phone_state without the sim key decodes with no SIM filter`() {
        val old = """{"type":"phone_state","event":"INCOMING_CALL","number":"333","textMatch":null}"""
        val decoded = decode(old) as Trigger.PhoneState
        assertEquals(null, decoded.sim)
        assertEquals(old, encode(decoded))
    }

    @Test
    fun `sim and battery policies state their closed bounds`() {
        assertTrue(SimSlotPolicy.validSlot(1) && SimSlotPolicy.validSlot(2))
        assertFalse(SimSlotPolicy.validSlot(0) || SimSlotPolicy.validSlot(3))
        assertTrue(BatteryTriggerPolicy.validThreshold(1) && BatteryTriggerPolicy.validThreshold(99))
        assertFalse(BatteryTriggerPolicy.validThreshold(0) || BatteryTriggerPolicy.validThreshold(100))
    }

    @Test
    fun `mode values are the ones of the state keys`() {
        assertEquals(StateKeys.ALL.getValue(StateKeys.DND).split('|').toSet(), ModeKind.DND.allowedValues)
        assertEquals(StateKeys.ALL.getValue(StateKeys.RINGER).split('|').toSet(), ModeKind.RINGER.allowedValues)
    }
}
