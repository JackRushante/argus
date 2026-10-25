package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Traduzione AI 0.3.5 sul provider Anthropic: codici del meta, usage del compile, troncamento, web. */
class AnthropicMessagesTransportTranslationTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun transport(model: String? = "claude-opus-4-8"): AnthropicMessagesTransport =
        AnthropicMessagesTransport(
            spec = ProviderCatalog.spec(ProviderId.ANTHROPIC),
            config = ProviderConfig(ProviderId.ANTHROPIC, server.url("/").toString().trimEnd('/'), model),
            apiKey = { API_KEY },
            client = CliBridgeTransport.defaultClient(),
            allowCleartextForTests = true,
        )

    private fun requestJson(): JsonObject = Json.parseToJsonElement(
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
    ).jsonObject

    // --- B2 ---

    @Test fun `meta error codes pass through the direct provider`(): Unit = runBlocking {
        listOf("clarification_required", "unsupported_capability", "limit_exceeded").forEach { code ->
            server.enqueue(jsonResponse(text("Serve un chiarimento.\n@@META@@ {\"draft\":null,\"error_code\":\"$code\"}")))

            val result = transport().compile("x", manifest(), DeviceState())

            assertNull(result.draft)
            assertEquals(code, result.metaError)
            assertEquals("Serve un chiarimento.", result.reply)
        }
    }

    @Test fun `draft together with an error_code is a meta parse error`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("ok\n@@META@@ {\"draft\":$DRAFT,\"error_code\":\"limit_exceeded\"}")))

        val result = transport().compile("x", manifest(), DeviceState())

        assertNull(result.draft)
        assertEquals("meta_parse_error", result.metaError)
    }

    // --- B15: usage del compile su tutti i rami ---

    @Test fun `compile returns the usage on success`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("ok\n@@META@@ {\"draft\":$DRAFT}", cacheRead = 64)))

        val result = transport().compile("x", manifest(), DeviceState())

        assertNotNull(result.draft)
        val usage = assertNotNull(result.usage)
        assertEquals(200L, usage.inputTokens)
        assertEquals(40L, usage.outputTokens)
        assertEquals(64L, usage.cachedInputTokens)
        assertEquals("claude-opus-4-8", usage.model)
        assertEquals("end_turn", usage.finishReason)
    }

    @Test fun `compile returns the usage on every no-draft branch`(): Unit = runBlocking {
        // senza sentinel
        server.enqueue(jsonResponse(text("Ciao, come posso aiutarti?")))
        var result = transport().compile("x", manifest(), DeviceState())
        assertEquals("draft_missing", result.metaError)
        assertEquals(200L, assertNotNull(result.usage).inputTokens)

        // meta malformato
        server.enqueue(jsonResponse(text("ok @@META@@ {non json}")))
        result = transport().compile("x", manifest(), DeviceState())
        assertEquals("meta_parse_error", result.metaError)
        assertEquals(200L, assertNotNull(result.usage).inputTokens)

        // contenuto vuoto
        server.enqueue(jsonResponse(text("")))
        result = transport().compile("x", manifest(), DeviceState())
        assertEquals("empty_response", result.metaError)
        assertEquals(200L, assertNotNull(result.usage).inputTokens)

        // clarification
        server.enqueue(jsonResponse(text("Quale?\n@@META@@ {\"draft\":null,\"error_code\":\"clarification_required\"}")))
        result = transport().compile("x", manifest(), DeviceState())
        assertEquals("clarification_required", result.metaError)
        assertEquals(40L, assertNotNull(result.usage).outputTokens)
    }

    // --- B6 ---

    @Test fun `max_tokens without a valid draft is output_truncated with usage`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("Ecco la regola, ma", stopReason = "max_tokens")))

        val result = transport().compile("x", manifest(), DeviceState())

        assertNull(result.draft)
        assertEquals("output_truncated", result.metaError)
        assertEquals("max_tokens", result.usage?.finishReason)
    }

    @Test fun `max_tokens with an empty content is output_truncated`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("", stopReason = "max_tokens")))

        assertEquals("output_truncated", transport().compile("x", manifest(), DeviceState()).metaError)
    }

    @Test fun `a valid draft wins over max_tokens`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("ok\n@@META@@ {\"draft\":$DRAFT}", stopReason = "max_tokens")))

        val result = transport().compile("x", manifest(), DeviceState())

        assertNull(result.metaError)
        assertNotNull(result.draft)
    }

    @Test fun `compile asks for 8192 output tokens and act for 2048`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("ok\n@@META@@ {\"draft\":$DRAFT}")))
        transport().compile("x", manifest(), DeviceState())
        assertEquals(8_192, requestJson().getValue("max_tokens").jsonPrimitive.content.toInt())

        server.enqueue(jsonResponse(toolUse("Fatto.")))
        transport().act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        assertEquals(2_048, requestJson().getValue("max_tokens").jsonPrimitive.content.toInt())
    }

    // --- S5 ---

    @Test fun `act text fallback needs end_turn and drops think blocks`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("<think>boh</think>Va bene.")))
        val ok = transport().act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        assertEquals("Va bene.", ok.text)

        server.enqueue(jsonResponse(text("Va bene, ci vediamo", stopReason = "max_tokens")))
        val cut = transport().act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        assertNull(cut.text)
        assertEquals("reply_tool_missing", cut.metaError)

        server.enqueue(jsonResponse(text("<think>sto pensando")))
        val thinking = transport().act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        assertEquals("reply_tool_missing", thinking.metaError)
    }

    @Test fun `with web search only the text after the last search result is used`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""
            {"id":"m","type":"message","role":"assistant","model":"claude-opus-4-8",
             "content":[
               {"type":"text","text":"Cerco il cambio. "},
               {"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"eur usd"}},
               {"type":"web_search_tool_result","tool_use_id":"s1","content":[]},
               {"type":"text","text":"Ora cerco anche un'altra fonte. "},
               {"type":"server_tool_use","id":"s2","name":"web_search","input":{"query":"cambio"}},
               {"type":"web_search_tool_result","tool_use_id":"s2","content":[]},
               {"type":"text","text":"Il cambio EUR/USD è 1.09."}],
             "stop_reason":"end_turn",
             "usage":{"input_tokens":300,"output_tokens":60,"server_tool_use":{"web_search_requests":2}}}
        """.trimIndent()))

        val result = transport().act(
            fireContext(), "cambio", listOf("notification"), listOf("whatsapp_reply", "web.search"),
        )

        assertEquals("Il cambio EUR/USD è 1.09.", result.text)
        // B38(d): le ricerche si contano.
        assertEquals(2, assertNotNull(result.usage).webSearches)
    }

    // --- S6 / B29 ---

    @Test fun `the web search tool is capped at 3 uses`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("Sereno.")))

        transport().act(fireContext(), "meteo", listOf("notification"), listOf("whatsapp_reply", "web.search"))

        val tools = requestJson().getValue("tools").jsonArray.map { it.jsonObject }
        val web = tools.single { it["type"]?.jsonPrimitive?.content == "web_search_20250305" }
        assertEquals(3, web.getValue("max_uses").jsonPrimitive.content.toInt())
    }

    @Test fun `notification sink promises web only when the web tool is sent`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("Promemoria.")))
        transport().act(timerContext(), "promemoria", emptyList(), emptyList())
        var system = requestJson().getValue("system").jsonPrimitive.content
        assertTrue("You have no internet access" in system)
        assertFalse("Use web search" in system)

        server.enqueue(jsonResponse(text("Prezzo.")))
        transport().act(timerContext(), "prezzo", emptyList(), listOf("web.search"))
        system = requestJson().getValue("system").jsonPrimitive.content
        assertTrue("Use web search for up-to-date data." in system)
        assertFalse("You have no internet access" in system)
    }

    // --- helpers ---

    private fun text(
        text: String,
        stopReason: String = "end_turn",
        cacheRead: Int? = null,
    ): String {
        val cache = if (cacheRead != null) ""","cache_read_input_tokens":$cacheRead""" else ""
        return """
            {"id":"msg_c","type":"message","role":"assistant","model":"claude-opus-4-8",
             "content":[{"type":"text","text":${JsonPrimitive(text)}}],
             "stop_reason":"$stopReason","usage":{"input_tokens":200,"output_tokens":40$cache}}
        """.trimIndent()
    }

    private fun toolUse(reply: String): String = """
        {"id":"msg_t","type":"message","role":"assistant","model":"claude-opus-4-8",
         "content":[{"type":"tool_use","id":"tu1","name":"whatsapp_reply","input":{"text":${JsonPrimitive(reply)}}}],
         "stop_reason":"tool_use","usage":{"input_tokens":50,"output_tokens":6}}
    """.trimIndent()

    private fun manifest(): CapabilityManifest = CapabilityManifest(
        deviceModel = "Pixel 9",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
        availableTools = listOf("set_dnd", "whatsapp_reply", "invoke_llm"),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
    )

    private fun jsonResponse(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun fireContext() = FireContext(
        event = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            conversationId = "jid:private",
            sender = "Moglie",
            title = "Moglie",
            text = "ciao",
            isGroup = false,
            notificationKey = "sbn:private",
        ),
        state = DeviceState(values = mapOf("ringer" to "normal"), foregroundApp = "com.whatsapp"),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private fun timerContext() = FireContext(
        event = TriggerEvent.TimeFired(
            automationId = AutomationId("automation-1"),
            approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        ),
        state = DeviceState(values = mapOf("ringer" to "normal"), foregroundApp = "com.whatsapp"),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private companion object {
        const val API_KEY = "sk-ant-test-secret-key-do-not-leak-0123456789"
        const val DRAFT =
            """{"name":"dnd sera","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_dnd","mode":"PRIORITY"}]}"""
    }
}
