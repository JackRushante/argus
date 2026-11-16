package dev.argus.brain

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.FeatureGate
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Vocabolario 0.8: finché un tipo è dietro un FeatureGate spento, il modello non lo deve mai vedere. Chi
 * accende un tipo aggiunge la sua riga di schema e lo toglie da qui.
 */
class AgentMessageSupportScaffold08Test {
    private val prompt = AgentMessageSupport.compileSystemText()

    /** Interruttori accesi: le loro righe di schema le prova il test della feature. */
    private val switchedOn = setOf(
        // Trigger fisici (AgentMessageSupportPhysical08Test).
        FeatureGate.TRIGGER_NFC_TAG, FeatureGate.TRIGGER_NEXT_ALARM, FeatureGate.TRIGGER_APP_USAGE,
        FeatureGate.TRIGGER_SENSOR_SHAKE, FeatureGate.TRIGGER_SENSOR_FLIP_FACE_DOWN,
    )

    /** Il discriminatore wire di ogni tipo spento, com'è scritto nelle righe di schema del prompt. */
    private val hiddenTypes = listOf(
        "calendar_event", "user_mode_changed",
        "weekday", "wifi_connected", "bluetooth_connected", "headset_connected", "calendar_busy",
        "stored_compare", "user_mode_is", "stored",
        "ask", "set_stored", "set_user_mode", "notification_reply", "notification_action", "send_sms",
        "call_number", "send_intent", "screen_call",
    )

    @Test
    fun `no switched-off type appears in the compile prompt`() {
        hiddenTypes.forEach { type ->
            assertFalse("\"type\":\"$type\"" in prompt, "il prompt nomina il tipo spento $type")
        }
        // Campi e valori nuovi di tipi esistenti.
        listOf("secretHeaders", "secretQuery", "\"extract\"", "\"output\"", "\"confirm\"", "\"exit\"")
            .forEach { assertFalse(it in prompt, "il prompt nomina $it") }
    }

    @Test
    fun `every gate is still off, so this test still describes the prompt`() {
        // Quando un engineer accende un tipo, aggiorna anche [hiddenTypes] e questo elenco.
        assertEquals(emptyList(), FeatureGate.entries.filter { it.enabled && it !in switchedOn })
    }

    @Test
    fun `an exit geofence never leaves the phone and comes back after the compile`() {
        val exitPlace = Trigger.Geofence(lat = 45.4642, lng = 9.19, radiusM = 150.0, transition = Transition.EXIT)
        val draft = AutomationDraft(
            name = "ufficio",
            trigger = Trigger.Manual,
            actions = listOf(Action.SetWifi(true)),
            exit = ExitSpec(exitPlace),
        )
        val redacted = LocationRedaction.redact(draft)
        assertEquals(Trigger.Geofence(lat = 0.0, lng = 0.0, radiusM = 150.0, transition = Transition.EXIT), redacted.draft.exit?.trigger)

        val restored = LocationRedaction.restore(redacted.draft, redacted.originals)
        assertEquals(exitPlace, restored.exit?.trigger)
    }
}
