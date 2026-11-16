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

    /** Il discriminatore wire di ogni tipo spento, com'è scritto nelle righe di schema del prompt. */
    private val hiddenTypes = listOf(
        "calendar_event", "nfc_tag", "next_alarm", "app_usage",
        "weekday", "wifi_connected", "bluetooth_connected", "headset_connected", "calendar_busy",
        "ask", "notification_reply", "notification_action", "send_sms",
        "call_number", "send_intent", "screen_call",
    )

    @Test
    fun `no switched-off type appears in the compile prompt`() {
        hiddenTypes.forEach { type ->
            assertFalse("\"type\":\"$type\"" in prompt, "il prompt nomina il tipo spento $type")
        }
        // Campi e valori nuovi di tipi esistenti.
        listOf("\"shake\"", "\"flip_face_down\"", "secretHeaders", "secretQuery", "\"extract\"", "\"output\"", "\"confirm\"", "\"exit\"")
            .forEach { assertFalse(it in prompt, "il prompt nomina $it") }
    }

    @Test
    fun `only the switched-on gates are on, so this test still describes the prompt`() {
        // Quando un engineer accende un tipo, aggiorna anche [hiddenTypes] e questo elenco.
        val switchedOn = setOf(
            // 0.8: valori conservati e modalità.
            FeatureGate.VAR_STORED,
            FeatureGate.CONDITION_STORED_COMPARE,
            FeatureGate.CONDITION_USER_MODE_IS,
            FeatureGate.TRIGGER_USER_MODE_CHANGED,
            FeatureGate.ACTION_SET_STORED,
            FeatureGate.ACTION_SET_USER_MODE,
        )
        assertEquals(switchedOn, FeatureGate.entries.filter { it.enabled }.toSet())
    }

    @Test
    fun `stored values and modes are described to the model`() {
        listOf("set_stored", "set_user_mode", "stored_compare", "user_mode_is", "user_mode_changed", "stored")
            .forEach { type -> kotlin.test.assertTrue("\"type\":\"$type\"" in prompt, "il prompt non descrive $type") }
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
