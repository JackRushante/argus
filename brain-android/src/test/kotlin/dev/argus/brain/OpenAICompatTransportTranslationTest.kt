package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/**
 * Traduzione AI 0.3.5 sui provider diretti OpenAI-compatibili: codici del meta (B2), troncamento
 * (B6), ripiego del reply tool e `<think>` (S5), tetti sui percorsi web (S6), usage (B38d).
 */
class OpenAICompatTransportTranslationTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun transport(providerId: ProviderId, model: String? = null): OpenAICompatTransport =
        OpenAICompatTransport(
            providerId = providerId,
            spec = ProviderCatalog.spec(providerId),
            config = ProviderConfig(providerId, server.url("/").toString().trimEnd('/'), model),
            apiKey = { API_KEY },
            client = CliBridgeTransport.defaultClient(),
            allowCleartextForTests = true,
        )

    private fun requestJson(): JsonObject = Json.parseToJsonElement(
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
    ).jsonObject

    // --- B2: error_code del meta ---

    @Test fun `unsupported_capability and limit_exceeded reach the caller as typed codes`(): Unit = runBlocking {
        listOf("unsupported_capability", "limit_exceeded").forEach { code ->
            server.enqueue(jsonResponse(chat("Non posso farlo.\n@@META@@ {\"draft\":null,\"error_code\":\"$code\"}")))
            val result = transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

            assertNull(result.draft)
            assertEquals(code, result.metaError)
            assertEquals("Non posso farlo.", result.reply)
            assertEquals(PLAUSIBLE_PROMPT_TOKENS, assertNotNull(result.usage).inputTokens)
        }
    }

    @Test fun `an invalid error_code is a meta parse error`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("no\n@@META@@ {\"draft\":null,\"error_code\":\"Not Valid!\"}")))

        val result = transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

        assertEquals("meta_parse_error", result.metaError)
    }

    @Test fun `draft together with an error_code is a meta parse error`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("ok\n@@META@@ {\"draft\":$DRAFT,\"error_code\":\"clarification_required\"}")))

        val result = transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

        assertNull(result.draft)
        assertEquals("meta_parse_error", result.metaError)
    }

    @Test fun `a tolerant reply with reasoning and a trailing comma still yields the draft`(): Unit = runBlocking {
        val body = "<think>scrivo @@META@@ {\"draft\":null,\"error_code\":\"fake\"}</think>Fatto.\n" +
            "@@META@@ {\"draft\":$DRAFT,}"
        server.enqueue(jsonResponse(chat(body)))

        val result = transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

        assertNull(result.metaError)
        assertEquals("dnd sera", assertNotNull(result.draft).name)
    }

    // --- B6: troncamento ---

    @Test fun `length finish without a valid draft is output_truncated and keeps the usage`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("Ecco la regola, ma", finishReason = "length")))

        val result = transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

        assertNull(result.draft)
        assertEquals("output_truncated", result.metaError)
        assertEquals("length", result.usage?.finishReason)
    }

    @Test fun `length finish with an empty content is still output_truncated`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("", finishReason = "length")))

        val result = transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

        assertEquals("output_truncated", result.metaError)
    }

    @Test fun `a valid draft wins even if the finish reason is length`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("ok\n@@META@@ {\"draft\":$DRAFT}", finishReason = "length")))

        val result = transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

        assertNull(result.metaError)
        assertNotNull(result.draft)
    }

    @Test fun `a prompt the server reports far smaller than ours is context_truncated`(): Unit = runBlocking {
        // Il prompt locale pesa migliaia di token: 100 riportati dal server = istruzioni tagliate.
        server.enqueue(jsonResponse(chat("Boh, non so.", promptTokens = TRUNCATED_PROMPT_TOKENS)))

        val result = transport(ProviderId.CUSTOM_OPENAI_COMPAT, "llama").compile("x", manifest(), DeviceState())

        assertNull(result.draft)
        assertEquals("context_truncated", result.metaError)
    }

    @Test fun `a plausible prompt token count does not flag context truncation`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("Quale contatto?\n@@META@@ {\"draft\":null,\"error_code\":\"clarification_required\"}", promptTokens = PLAUSIBLE_PROMPT_TOKENS)))

        val result = transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

        assertEquals("clarification_required", result.metaError)
    }

    @Test fun `compile asks for 8192 output tokens and act for 2048`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("ok\n@@META@@ {\"draft\":$DRAFT}")))
        transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())
        assertEquals(8_192, requestJson().getValue("max_completion_tokens").jsonPrimitive.content.toInt())

        server.enqueue(jsonResponse(toolReply("Fatto.")))
        transport(ProviderId.OPENAI, "gpt-5.5")
            .act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        assertEquals(2_048, requestJson().getValue("max_completion_tokens").jsonPrimitive.content.toInt())

        server.enqueue(jsonResponse(chat("ok\n@@META@@ {\"draft\":$DRAFT}")))
        transport(ProviderId.GEMINI, "gemini-2.5-flash").compile("x", manifest(), DeviceState())
        assertEquals(8_192, requestJson().getValue("max_tokens").jsonPrimitive.content.toInt())
    }

    @Test fun `a 32768 character message is accepted and 32769 is refused before the network`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("ok\n@@META@@ {\"draft\":$DRAFT}")))
        val t = transport(ProviderId.OPENAI, "gpt-5.5")

        t.compile("a".repeat(32_768), manifest(), DeviceState())
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        val error = kotlin.runCatching { t.compile("a".repeat(32_769), manifest(), DeviceState()) }.exceptionOrNull()
        assertEquals(TransportErrorKind.CONFIGURATION, (error as TransportException).kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `compile never sends the real conversation id`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("ok\n@@META@@ {\"draft\":$DRAFT}")))

        transport(ProviderId.OPENAI, "gpt-5.5").compile("x", manifest(), DeviceState())

        val body = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        assertFalse("jid:private" in body)
        assertTrue("contact_1" in body)
    }

    // --- S5: reply tool, ripiego e <think> ---

    @Test fun `reply tool text is cleaned of think blocks`(): Unit = runBlocking {
        server.enqueue(jsonResponse(toolReply("<think>ragiono</think>Ciao!")))

        val result = transport(ProviderId.OPENAI, "gpt-5.5")
            .act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Ciao!", result.text)
    }

    @Test fun `content fallback needs a normal stop and strips reasoning`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("<think>x</think>Va bene, a dopo.", finishReason = "stop")))

        val result = transport(ProviderId.GEMINI, "gemini-2.5-flash")
            .act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Va bene, a dopo.", result.text)
    }

    @Test fun `content fallback with a length finish fails with reply_tool_missing`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("Va bene, ci vediamo domani alle", finishReason = "length")))

        val result = transport(ProviderId.OPENAI, "gpt-5.5")
            .act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertNull(result.text)
        assertEquals("reply_tool_missing", result.metaError)
        assertNotNull(result.usage)
    }

    @Test fun `content fallback with an unfinished think block fails with reply_tool_missing`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("<think>devo rispondere che", finishReason = "stop")))

        val result = transport(ProviderId.OPENAI, "gpt-5.5")
            .act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("reply_tool_missing", result.metaError)
    }

    @Test fun `plain generation hides reasoning from the notification text`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("<think>boh</think>Promemoria: acqua.", finishReason = "stop")))

        val result = transport(ProviderId.OPENROUTER, "openai/gpt-5.5")
            .act(timerContext(), "promemoria", emptyList(), emptyList())

        assertEquals("Promemoria: acqua.", result.text)
    }

    @Test fun `act system prompt carries the untrusted data rules`(): Unit = runBlocking {
        server.enqueue(jsonResponse(toolReply("Ok")))

        transport(ProviderId.OPENAI, "gpt-5.5")
            .act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        val system = requestJson().getValue("messages").jsonArray.first().jsonObject
            .getValue("content").jsonPrimitive.content
        assertTrue("UNTRUSTED DATA" in system)
        assertTrue("{{ARGUS_RUNTIME_DATA_n}}" in system)
    }

    // --- B29: promessa del web solo se il web c'è ---

    @Test fun `notification sink without web tells the model it is offline`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("Promemoria.", finishReason = "stop")))

        transport(ProviderId.OPENROUTER, "openai/gpt-5.5").act(timerContext(), "prezzo bitcoin", emptyList(), emptyList())

        val system = requestJson().getValue("messages").jsonArray.first().jsonObject
            .getValue("content").jsonPrimitive.content
        assertTrue("You have no internet access: never present live or current data as if you had looked it up." in system)
        assertFalse("Use web search" in system)
    }

    @Test fun `notification sink with web keeps the web search promise`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("Prezzo.", finishReason = "stop")))

        transport(ProviderId.OPENROUTER, "openai/gpt-5.5")
            .act(timerContext(), "prezzo bitcoin", emptyList(), listOf("web.search"))

        val system = requestJson().getValue("messages").jsonArray.first().jsonObject
            .getValue("content").jsonPrimitive.content
        assertTrue("Use web search for up-to-date data." in system)
        assertFalse("You have no internet access" in system)
    }

    @Test fun `notification sink with a web request on a provider without web degrades to the offline wording`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chat("Prezzo.", finishReason = "stop")))

        // CUSTOM non ha un meccanismo web: il web richiesto viene ignorato, quindi niente promessa.
        transport(ProviderId.CUSTOM_OPENAI_COMPAT, "llama")
            .act(timerContext(), "prezzo bitcoin", emptyList(), listOf("web.search"))

        val system = requestJson().getValue("messages").jsonArray.first().jsonObject
            .getValue("content").jsonPrimitive.content
        assertTrue("You have no internet access" in system)
    }

    // --- S6: tetti sui percorsi web ---

    @Test fun `the openai responses request is capped and not stored`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""
            {"model":"gpt-5.5",
             "output":[
               {"type":"web_search_call","status":"completed"},
               {"type":"web_search_call","status":"completed"},
               {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Cambio 1.09."}]}],
             "usage":{"input_tokens":220,"output_tokens":18}}
        """.trimIndent()))

        val result = transport(ProviderId.OPENAI, "gpt-5.5")
            .act(fireContext(), "cambio", listOf("notification"), listOf("whatsapp_reply", "web.search"))

        val root = requestJson()
        assertEquals(2_048, root.getValue("max_output_tokens").jsonPrimitive.content.toInt())
        assertEquals(3, root.getValue("max_tool_calls").jsonPrimitive.content.toInt())
        assertEquals("false", root.getValue("store").jsonPrimitive.content)
        // B38(d): le ricerche si contano, non si addebitano.
        assertEquals(2, assertNotNull(result.usage).webSearches)
    }

    @Test fun `the gemini native request caps output and thinking and counts thought tokens as output`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Oggi sereno."}]}}],
             "usageMetadata":{"promptTokenCount":45,"candidatesTokenCount":9,"thoughtsTokenCount":300},
             "modelVersion":"gemini-2.5-flash"}
        """.trimIndent()))

        val result = transport(ProviderId.GEMINI, "gemini-2.5-flash")
            .act(fireContext(), "meteo", listOf("notification"), listOf("whatsapp_reply", "web.search"))

        val config = requestJson().getValue("generationConfig").jsonObject
        assertEquals(2_048, config.getValue("maxOutputTokens").jsonPrimitive.content.toInt())
        assertEquals(
            1_024,
            config.getValue("thinkingConfig").jsonObject.getValue("thinkingBudget").jsonPrimitive.content.toInt(),
        )
        val usage = assertNotNull(result.usage)
        assertEquals(309L, usage.outputTokens)
        assertEquals(300L, usage.reasoningTokens)
    }

    // --- helpers ---

    private fun chat(
        content: String,
        finishReason: String = "stop",
        promptTokens: Long = PLAUSIBLE_PROMPT_TOKENS,
    ): String = """
        {"id":"c1","model":"gpt-5.5",
         "choices":[{"index":0,"finish_reason":${JsonPrimitive(finishReason)},"message":{"role":"assistant","content":${JsonPrimitive(content)}}}],
         "usage":{"prompt_tokens":$promptTokens,"completion_tokens":40}}
    """.trimIndent()

    private fun toolReply(text: String): String {
        val arguments = buildJsonObject { put("text", text) }.toString()
        return """
            {"id":"c2","model":"gpt-5.5",
             "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
               "tool_calls":[{"id":"call_1","type":"function","function":{"name":"whatsapp_reply","arguments":${JsonPrimitive(arguments)}}}]}}],
             "usage":{"prompt_tokens":120,"completion_tokens":8}}
        """.trimIndent()
    }

    private fun manifest(): CapabilityManifest = CapabilityManifest(
        deviceModel = "Pixel 9",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
        availableTools = listOf("set_dnd", "whatsapp_reply", "invoke_llm"),
        unavailableTools = emptyMap(),
        whitelistedContacts = listOf(dev.argus.engine.brain.WhitelistedContact("Moglie", "jid:private")),
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
        const val API_KEY = "sk-test-secret-key-do-not-leak-0123456789"

        /** Molto sopra il 60% di qualunque prompt di compile: non scatta mai `context_truncated`. */
        const val PLAUSIBLE_PROMPT_TOKENS = 12_000L

        /** Molto sotto il 60% di qualunque prompt di compile (le sole regole pesano migliaia di token). */
        const val TRUNCATED_PROMPT_TOKENS = 100L
        const val DRAFT =
            """{"name":"dnd sera","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_dnd","mode":"PRIORITY"}]}"""
    }
}
