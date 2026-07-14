package dev.argus.engine.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
class AutomationSerializationTest {
    @Test fun `automation with generative action round-trips`() {
        val a = Automation(
            id = AutomationId("a1"), name = "reply moglie",
            createdBy = CreatedBy.LLM, status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Notification(pkg = "com.whatsapp", conversationId = "id:42", isGroup = false),
            conditions = Condition.TimeWindow("18:00", "22:00", "Europe/Rome"),
            actions = listOf(Action.InvokeLlm(
                goal = "rispondi nel tono X", contextSources = listOf("notification"),
                allowedTools = listOf("whatsapp_reply"), replyTargetSender = true,
            )),
        )
        val json = ArgusJson.encodeToString(Automation.serializer(), a)
        assertEquals(a, ArgusJson.decodeFromString(Automation.serializer(), json))
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_NOTIFICATION,
                CapabilityIds.ACTION_INVOKE_LLM,
                "whatsapp_reply",
            ),
            a.requiredCapabilities,
        )
    }
    @Test fun `legacy json without capability snapshot derives it fail closed`() {
        val automation = Automation(
            id = AutomationId("legacy"), name = "DND",
            createdBy = CreatedBy.USER, status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val encoded = ArgusJson.encodeToJsonElement(Automation.serializer(), automation).jsonObject
        val legacy = JsonObject(encoded - "requiredCapabilities")
        val decoded = ArgusJson.decodeFromJsonElement(Automation.serializer(), legacy)
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME, CapabilityIds.ACTION_SET_DND),
            decoded.requiredCapabilities,
        )
    }
    @Test fun `tier classification`() {
        assertTrue(Action.SetWifi(true).tier == ActionTier.DETERMINISTIC)
        assertTrue(Action.ShowNotification("t", "x").tier == ActionTier.DETERMINISTIC)
        assertTrue(Action.InvokeLlm("g", listOf(), listOf(), true).tier == ActionTier.GENERATIVE)
    }
    @Test fun `state keys registry is closed and documented`() {
        assertTrue(StateKeys.RINGER in StateKeys.ALL)
        assertTrue(StateKeys.ALL.getValue(StateKeys.RINGER).contains("silent"))
    }
}
