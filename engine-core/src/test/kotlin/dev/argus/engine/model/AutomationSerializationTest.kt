package dev.argus.engine.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
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
    @Test fun `immediate automation with alarm and volume actions round-trips`() {
        val a = Automation(
            id = AutomationId("imm1"), name = "sveglia tra poco",
            createdBy = CreatedBy.USER, status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Immediate,
            actions = listOf(
                Action.SetVolume(VolumeStream.ALARM, level = 80),
                Action.SetAlarm(hour = 7, minute = 30, label = "Palestra"),
            ),
        )
        val json = ArgusJson.encodeToString(Automation.serializer(), a)
        assertTrue(json.contains("\"type\":\"immediate\""), json)
        assertEquals(a, ArgusJson.decodeFromString(Automation.serializer(), json))
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_IMMEDIATE,
                CapabilityIds.ACTION_SET_VOLUME,
                CapabilityIds.ACTION_SET_ALARM,
            ),
            a.requiredCapabilities,
        )
    }
    @Test fun `tier classification`() {
        assertTrue(Action.SetWifi(true).tier == ActionTier.DETERMINISTIC)
        assertTrue(Action.ShowNotification("t", "x").tier == ActionTier.DETERMINISTIC)
        assertTrue(Action.InvokeLlm("g", listOf(), listOf(), true).tier == ActionTier.GENERATIVE)
    }
    @Test fun `invoke llm local notification sink round-trips and serializes deliver uppercase`() {
        val action = Action.InvokeLlm(
            goal = "genera il cambio euro dollaro",
            contextSources = emptyList(),
            allowedTools = listOf("web.search"),
            replyTargetSender = false,
            deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
            notificationTitle = "Cambio EUR/USD",
        )
        val json = ArgusJson.encodeToString(Action.serializer(), action)
        // Enum senza @SerialName → UPPERCASE; ArgusJson è case-sensitive.
        assertTrue(json.contains("\"deliver\":\"LOCAL_NOTIFICATION\""), json)
        assertTrue(json.contains("\"notificationTitle\":\"Cambio EUR/USD\""), json)
        assertEquals(action, ArgusJson.decodeFromString(Action.serializer(), json))
    }
    @Test fun `invoke llm reply is the default and stays wire-compatible without deliver field`() {
        val reply = Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true)
        assertEquals(GenerativeDeliverMode.WHATSAPP_REPLY, reply.deliver)
        val json = ArgusJson.encodeToString(Action.serializer(), reply)
        // @EncodeDefault(NEVER): il ramo reply NON emette i campi nuovi → bytes v1 stabili.
        assertTrue(!json.contains("deliver"), json)
        assertTrue(!json.contains("notificationTitle"), json)
        assertEquals(reply, ArgusJson.decodeFromString(Action.serializer(), json))
        // Un vecchio JSON senza `deliver` deserializza al default WHATSAPP_REPLY (retro-compat).
        val legacy = "{\"type\":\"invoke_llm\",\"goal\":\"rispondi\"," +
            "\"contextSources\":[\"notification\"],\"allowedTools\":[\"whatsapp_reply\"]," +
            "\"replyTargetSender\":true,\"timeoutMs\":60000}"
        val decoded = ArgusJson.decodeFromString(Action.serializer(), legacy) as Action.InvokeLlm
        assertEquals(reply, decoded)
        assertEquals(GenerativeDeliverMode.WHATSAPP_REPLY, decoded.deliver)
    }
    @Test fun `invoke llm v2 round trips with required wire fields and exact reader capability`() {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val action = Action.InvokeLlmV2(
            goal = "rispondi",
            stateContext = listOf(
                ApprovedStateContext(
                    query,
                    StateValueType.NUMBER,
                    StateQueryPolicy.VERSION,
                    IntegrityLabel.CLEAN,
                    ConfidentialityLabel.SECRET,
                ),
            ),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 60_000,
        )
        val automation = Automation(
            AutomationId("v2"),
            "reply v2",
            CreatedBy.USER,
            AutomationStatus.PENDING_APPROVAL,
            Trigger.Notification("com.whatsapp", "id:42", isGroup = false),
            listOf(action),
        )
        val encoded = ArgusJson.encodeToString(Automation.serializer(), automation)

        assertEquals(automation, ArgusJson.decodeFromString(Automation.serializer(), encoded))
        assertTrue(action.tier == ActionTier.GENERATIVE)
        assertTrue(CapabilityIds.STATE_READER_DUMPSYS_FIELD in automation.requiredCapabilities)
        assertTrue("state.read" !in automation.requiredCapabilities)
        assertFailsWith<SerializationException> {
            ArgusJson.decodeFromString(
                Automation.serializer(),
                encoded.replace(",\"timeoutMs\":60000", ""),
            )
        }
    }
    @Test fun `state keys registry is closed and documented`() {
        assertTrue(StateKeys.RINGER in StateKeys.ALL)
        assertTrue(StateKeys.ALL.getValue(StateKeys.RINGER).contains("silent"))
    }

    @Test fun `night mode accepts lowercase compiler wire and preserves uppercase storage`() {
        val lower = """{"type":"set_dark_mode","mode":"on"}"""
        val decoded = ArgusJson.decodeFromString(Action.serializer(), lower)
        assertEquals(Action.SetDarkMode(NightMode.ON), decoded)

        val persisted = ArgusJson.encodeToString(Action.serializer(), decoded)
        assertTrue(persisted.contains("\"mode\":\"ON\""), persisted)
        assertEquals(decoded, ArgusJson.decodeFromString(Action.serializer(), persisted))
    }

    @Test fun `invoke llm capture only serializes an explicit internal sink`() {
        val capture = Action.InvokeLlm(
            goal = "summarize",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            deliver = GenerativeDeliverMode.CAPTURE_ONLY,
            captureAs = "summary",
        )

        val json = ArgusJson.encodeToString(Action.serializer(), capture)
        assertTrue(json.contains("\"deliver\":\"CAPTURE_ONLY\""), json)
        assertTrue(json.contains("\"captureAs\":\"summary\""), json)
        assertEquals(capture, ArgusJson.decodeFromString(Action.serializer(), json))
    }
}
