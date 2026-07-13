package dev.argus.engine.model
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DoD P0-A: "round-trip stabile per l'intero schema". Table-driven: copre OGNI
 * sottotipo di Trigger/Condition/Action non già coperto dagli altri test di
 * serializzazione, più AutomationDraft. Per ciascuno: decode(encode(x)) == x
 * via [ArgusJson] (stesso classDiscriminator "type" della produzione).
 */
class SchemaRoundTripTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, value: T) {
        val json = ArgusJson.encodeToString(serializer, value)
        assertEquals(value, ArgusJson.decodeFromString(serializer, json), json)
    }

    @Test fun `phone_state and connectivity triggers round-trip`() {
        val triggers: List<Trigger> = listOf(
            Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "+39 320 000 0000"),
            Trigger.PhoneState(PhoneEvent.CALL_ENDED),
            Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, number = null),
            Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, match = "Casa"),
            Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
            Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED, match = "Auto"),
        )
        triggers.forEach { roundTrip(Trigger.serializer(), it) }
    }

    @Test fun `location_in condition round-trips`() {
        roundTrip(Condition.serializer(), Condition.LocationIn(lat = 45.4642, lng = 9.19, radiusM = 100.0))
    }

    @Test fun `all deterministic and generative action subtypes round-trip`() {
        val actions: List<Action> = listOf(
            Action.SetBluetooth(on = true),
            Action.SetDnd(DndMode.TOTAL),
            Action.SetDnd(DndMode.PRIORITY),
            Action.SetRinger("silent"),
            Action.LaunchApp("com.whatsapp"),
            Action.OpenUrl("https://example.org"),
            Action.ShowNotification(title = "Titolo", text = "Corpo"),
            Action.Tap(x = 120, y = 340),
            Action.InputText("ciao"),
            Action.WhatsAppReply("ok"),
            Action.RunShell("svc wifi disable"),
        )
        actions.forEach { roundTrip(Action.serializer(), it) }
    }

    @Test fun `automation draft round-trips with nested trigger, conditions and actions`() {
        val draft = AutomationDraft(
            name = "reply moglie",
            trigger = Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            actions = listOf(
                Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), replyTargetSender = true),
                Action.SetDnd(DndMode.PRIORITY),
            ),
            conditions = Condition.And(
                listOf(
                    Condition.TimeWindow("18:00", "22:00", "Europe/Rome"),
                    Condition.LocationIn(lat = 45.0, lng = 9.0, radiusM = 150.0),
                ),
            ),
            rationale = "spiega perché",
            cooldownMs = 120_000,
        )
        roundTrip(AutomationDraft.serializer(), draft)
    }
}
