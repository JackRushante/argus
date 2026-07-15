package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilityRequirementsTest {
    @Test
    fun `requirements include trigger condition and action capabilities`() {
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_TIME,
                CapabilityIds.STATE_LOCATION,
                CapabilityIds.ACTION_SET_DND,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                conditions = Condition.LocationIn(45.46, 9.19, 100.0),
                actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
            ),
        )
    }

    @Test
    fun `generative reply rule derives trigger llm and raw tool capabilities`() {
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_NOTIFICATION,
                CapabilityIds.ACTION_INVOKE_LLM,
                GenerativeContract.TOOL_WHATSAPP_REPLY,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Notification(
                    "com.whatsapp",
                    conversationId = "jid:42",
                    isGroup = false,
                ),
                actions = listOf(
                    Action.InvokeLlm(
                        goal = "rispondi",
                        contextSources = listOf("notification"),
                        allowedTools = listOf("whatsapp_reply"),
                        replyTargetSender = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `state context source adds the state read requirement exactly once`() {
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_NOTIFICATION,
                CapabilityIds.ACTION_INVOKE_LLM,
                GenerativeContract.TOOL_WHATSAPP_REPLY,
                GenerativeContract.TOOL_STATE_READ,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Notification(
                    "com.whatsapp",
                    conversationId = "jid:42",
                    isGroup = false,
                ),
                actions = listOf(
                    Action.InvokeLlm(
                        goal = "rispondi",
                        contextSources = listOf("notification", "state"),
                        allowedTools = listOf("whatsapp_reply"),
                        replyTargetSender = true,
                    ),
                    Action.InvokeLlm(
                        goal = "riassumi",
                        contextSources = listOf("notification", "state"),
                        allowedTools = listOf("whatsapp_reply"),
                        replyTargetSender = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `notification only context does not require state read`() {
        assertEquals(
            emptySet<String>(),
            CapabilityRequirements.derive(
                trigger = Trigger.Notification("com.whatsapp"),
                actions = listOf(
                    Action.InvokeLlm(
                        goal = "rispondi",
                        contextSources = listOf("notification"),
                        allowedTools = listOf("whatsapp_reply"),
                        replyTargetSender = true,
                    ),
                ),
            ).filter { it == GenerativeContract.TOOL_STATE_READ }.toSet(),
        )
    }

    @Test
    fun `phone state derives granular capabilities per event kind`() {
        // I grant OS differiscono (RECEIVE_SMS vs READ_PHONE_STATE): la capability persistita
        // deve distinguere, così la probe pubblica solo ciò che il grant reale copre.
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_SMS),
            CapabilityRequirements.derive(
                trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_CALL),
            CapabilityRequirements.derive(
                trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "333"),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_CALL),
            CapabilityRequirements.derive(
                trigger = Trigger.PhoneState(PhoneEvent.CALL_ENDED),
                actions = emptyList(),
            ),
        )
    }

    @Test
    fun `copy to clipboard derives its own action capability`() {
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_SMS, CapabilityIds.ACTION_COPY_TO_CLIPBOARD),
            CapabilityRequirements.derive(
                trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED),
                actions = listOf(Action.CopyToClipboard(extractionRegex = "(\\d{4,8})")),
            ),
        )
    }

    @Test
    fun `connectivity requirements are granular and wifi identity needs location`() {
        assertEquals(
            setOf(CapabilityIds.TRIGGER_CONNECTIVITY_POWER),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_CONNECTIVITY_BT),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED, "Auto"),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_CONNECTIVITY_WIFI,
                CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"),
                actions = emptyList(),
            ),
        )
    }

    @Test
    fun `nested conditions retain every state dependency`() {
        val conditions = Condition.And(
            listOf(
                Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "normal"),
                Condition.Or(
                    listOf(
                        Condition.AppInForeground("com.example"),
                        Condition.Not(Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "off")),
                    ),
                ),
            ),
        )

        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_CONNECTIVITY_POWER,
                CapabilityIds.state(StateKeys.RINGER),
                CapabilityIds.STATE_FOREGROUND_APP,
                CapabilityIds.state(StateKeys.WIFI),
                CapabilityIds.ACTION_SHOW_NOTIFICATION,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
                conditions = conditions,
                actions = listOf(Action.ShowNotification("Argus", "test")),
            ),
        )
    }
}
