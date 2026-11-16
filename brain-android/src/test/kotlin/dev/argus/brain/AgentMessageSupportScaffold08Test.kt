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
import kotlin.test.assertTrue

/**
 * Vocabolario 0.8: finché un tipo è dietro un FeatureGate spento, il modello non lo deve mai vedere. Chi
 * accende un tipo aggiunge la sua riga di schema e lo toglie da qui.
 */
class AgentMessageSupportScaffold08Test {
    private val prompt = AgentMessageSupport.compileSystemText()

    /** Il discriminatore wire di ogni tipo spento, com'è scritto nelle righe di schema del prompt. */
    private val hiddenTypes = listOf(
        "nfc_tag", "next_alarm", "app_usage", "user_mode_changed",
        "stored_compare", "user_mode_is", "stored",
        "ask", "set_stored", "set_user_mode", "send_sms",
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

    /** Tipi accesi: il prompt ne ha la riga di schema. */
    private val switchedOn = mapOf(
        FeatureGate.CONDITION_WEEKDAY to "weekday",
        FeatureGate.CONDITION_WIFI_CONNECTED to "wifi_connected",
        FeatureGate.CONDITION_BLUETOOTH_CONNECTED to "bluetooth_connected",
        FeatureGate.CONDITION_HEADSET_CONNECTED to "headset_connected",
        FeatureGate.CONDITION_CALENDAR_BUSY to "calendar_busy",
        FeatureGate.TRIGGER_CALENDAR_EVENT to "calendar_event",
        FeatureGate.ACTION_NOTIFICATION_REPLY to "notification_reply",
        FeatureGate.ACTION_NOTIFICATION_ACTION to "notification_action",
    )

    @Test
    fun `only the switched-on gates are on, so this test still describes the prompt`() {
        // Quando un engineer accende un tipo, aggiorna anche [hiddenTypes] e questo elenco.
        assertEquals(switchedOn.keys, FeatureGate.entries.filter { it.enabled }.toSet())
    }

    @Test
    fun `every switched-on type has its schema line`() {
        switchedOn.values.forEach { type ->
            assertTrue("\"type\":\"$type\"" in prompt, "il prompt non descrive $type")
        }
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
