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
    /** Ogni tipo 0.8 è ormai acceso: chi aggiunge un tipo spento lo elenca qui. */
    private val hiddenTypes = emptyList<String>()

    @Test
    fun `no switched-off type appears in the compile prompt`() {
        hiddenTypes.forEach { type ->
            assertFalse("\"type\":\"$type\"" in prompt, "il prompt nomina il tipo spento $type")
        }
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
        FeatureGate.VAR_STORED to "stored",
        FeatureGate.CONDITION_STORED_COMPARE to "stored_compare",
        FeatureGate.CONDITION_USER_MODE_IS to "user_mode_is",
        FeatureGate.TRIGGER_USER_MODE_CHANGED to "user_mode_changed",
        FeatureGate.ACTION_SET_STORED to "set_stored",
        FeatureGate.ACTION_SET_USER_MODE to "set_user_mode",
        FeatureGate.ACTION_ASK to "ask",
        FeatureGate.ACTION_SEND_SMS to "send_sms",
        FeatureGate.ACTION_CALL_NUMBER to "call_number",
        FeatureGate.ACTION_SEND_INTENT to "send_intent",
        FeatureGate.ACTION_SCREEN_CALL to "screen_call",
        // Trigger fisici (AgentMessageSupportPhysical08Test).
        FeatureGate.TRIGGER_NFC_TAG to "nfc_tag",
        FeatureGate.TRIGGER_NEXT_ALARM to "next_alarm",
        FeatureGate.TRIGGER_APP_USAGE to "app_usage",
    )

    /** Interruttori accesi che non sono un tipo: il prompt ne descrive il campo. */
    private val switchedOnFields = mapOf(
        FeatureGate.CONFIRM to "\"confirm\": {\"timeoutMs\":integer}",
        FeatureGate.EXIT to "\"exit\": {\"trigger\":Trigger, \"restore\":boolean, \"actions\":[Action, ...]}",
        FeatureGate.LLM_OUTPUT to "\"output\":{\"kind\":\"CHOICE\"|\"NUMBER\"|\"BOOLEAN\"",
        FeatureGate.HTTP_CAPTURE to "\"extract\":string = a JSON pointer",
        FeatureGate.HTTP_SECRETS to "\"secretHeaders\":[SecretRef,...] and \"secretQuery\":[SecretRef,...]",
        FeatureGate.TRIGGER_SENSOR_SHAKE to "\"shake\"",
        FeatureGate.TRIGGER_SENSOR_FLIP_FACE_DOWN to "\"flip_face_down\"",
    )

    @Test
    fun `only the switched-on gates are on, so this test still describes the prompt`() {
        // Quando un engineer accende un tipo, aggiorna anche [hiddenTypes] e questo elenco.
        assertEquals(switchedOn.keys + switchedOnFields.keys, FeatureGate.entries.filter { it.enabled }.toSet())
    }

    @Test
    fun `every switched-on type has its schema line`() {
        switchedOn.values.forEach { type ->
            assertTrue("\"type\":\"$type\"" in prompt, "il prompt non descrive $type")
        }
        switchedOnFields.values.forEach { field ->
            assertTrue(field in prompt, "il prompt non descrive $field")
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
