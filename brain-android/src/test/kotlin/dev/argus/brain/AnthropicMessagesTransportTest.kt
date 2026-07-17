package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicMessagesTransportTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun transport(
        model: String? = null,
        client: OkHttpClient = CliBridgeTransport.defaultClient(),
        apiKey: String? = API_KEY,
    ): AnthropicMessagesTransport {
        val spec = ProviderCatalog.spec(ProviderId.ANTHROPIC)
        return AnthropicMessagesTransport(
            spec = spec,
            config = ProviderConfig(ProviderId.ANTHROPIC, server.url("/").toString().trimEnd('/'), model),
            apiKey = { apiKey },
            client = client,
            allowCleartextForTests = true,
        )
    }

    @Test fun `tool_use reply parses text and usage and sets anthropic headers`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-4-8",
             "content":[{"type":"tool_use","id":"toolu_1","name":"whatsapp_reply","input":{"text":"Va bene, a dopo."}}],
             "stop_reason":"tool_use",
             "usage":{"input_tokens":120,"output_tokens":8,"cache_read_input_tokens":64}}
            """.trimIndent(),
        ))
        val t = transport(model = "claude-opus-4-8")

        val result = t.act(fireContext(), "rispondi cordiale", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Va bene, a dopo.", result.text)
        assertNull(result.metaError)
        val usage = assertNotNull(result.usage)
        assertEquals(120L, usage.inputTokens)
        assertEquals(8L, usage.outputTokens)
        assertEquals(64L, usage.cachedInputTokens)
        assertEquals("claude-opus-4-8", usage.model)

        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/messages", request.path)
        assertEquals(API_KEY, request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        // La chiave NON deve mai finire in un header Authorization Bearer.
        assertNull(request.getHeader("Authorization"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("claude-opus-4-8", root.getValue("model").jsonPrimitive.content)
        // max_tokens obbligatorio; system separato dai messages.
        assertTrue("max_tokens" in root)
        assertTrue("system" in root)
        val messages = root.getValue("messages").jsonArray
        assertEquals("user", messages.single().jsonObject.getValue("role").jsonPrimitive.content)
        // tools con input_schema (non "parameters").
        val tool = root.getValue("tools").jsonArray.single().jsonObject
        assertEquals("whatsapp_reply", tool.getValue("name").jsonPrimitive.content)
        assertTrue("input_schema" in tool)
        val toolChoice = root.getValue("tool_choice").jsonObject
        assertEquals("tool", toolChoice.getValue("type").jsonPrimitive.content)
        assertEquals("whatsapp_reply", toolChoice.getValue("name").jsonPrimitive.content)
    }

    // --- #52 F3: web search server-side (Anthropic Messages) ---

    @Test fun `web search adds the server tool and sets tool_choice auto`(): Unit = runBlocking {
        // Con web richiesto: il server tool web_search_20250305 affianca il reply tool e il reply NON
        // e' forzato (auto), cosi' Anthropic puo' cercare e poi rispondere. Il testo finale (blocco
        // text dopo i risultati web) passa via textContent().
        server.enqueue(jsonResponse(
            """
            {"id":"msg_w","type":"message","role":"assistant","model":"claude-opus-4-8",
             "content":[{"type":"text","text":"A Roma oggi sereno, 28 gradi."}],
             "stop_reason":"end_turn",
             "usage":{"input_tokens":100,"output_tokens":20}}
            """.trimIndent(),
        ))
        val t = transport(model = "claude-opus-4-8")

        val result = t.act(
            fireContext(), "che tempo fa a Roma", listOf("notification"),
            listOf("whatsapp_reply", "web.search"),
        )

        assertEquals("A Roma oggi sereno, 28 gradi.", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        val tools = root.getValue("tools").jsonArray
        assertEquals(2, tools.size)
        val names = tools.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet()
        assertTrue("whatsapp_reply" in names)
        assertTrue("web_search" in names)
        val webTool = tools.first { it.jsonObject.getValue("name").jsonPrimitive.content == "web_search" }.jsonObject
        assertEquals("web_search_20250305", webTool.getValue("type").jsonPrimitive.content)
        val toolChoice = root.getValue("tool_choice").jsonObject
        assertEquals("auto", toolChoice.getValue("type").jsonPrimitive.content)
    }

    @Test fun `without web the reply tool is forced and no web tool is present`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"id":"msg_nw","type":"message","role":"assistant","model":"claude-opus-4-8",
             "content":[{"type":"tool_use","id":"toolu_1","name":"whatsapp_reply","input":{"text":"Ok!"}}],
             "stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":2}}
            """.trimIndent(),
        ))
        val t = transport(model = "claude-opus-4-8")

        t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        val tools = root.getValue("tools").jsonArray
        assertEquals(1, tools.size)
        assertEquals("whatsapp_reply", tools.single().jsonObject.getValue("name").jsonPrimitive.content)
        val toolChoice = root.getValue("tool_choice").jsonObject
        assertEquals("tool", toolChoice.getValue("type").jsonPrimitive.content)
        assertEquals("whatsapp_reply", toolChoice.getValue("name").jsonPrimitive.content)
    }

    @Test fun `plain text content reply is accepted`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-4-5",
             "content":[{"type":"text","text":"Ciao, arrivo!"}],
             "stop_reason":"end_turn",
             "usage":{"input_tokens":30,"output_tokens":4}}
            """.trimIndent(),
        ))
        val t = transport(model = "claude-sonnet-4-5")

        val result = t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Ciao, arrivo!", result.text)
        val usage = assertNotNull(result.usage)
        assertEquals(30L, usage.inputTokens)
        assertEquals(4L, usage.outputTokens)
        assertNull(usage.cachedInputTokens)
    }

    @Test fun `default model is resolved from catalog`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"id":"msg_3","type":"message","role":"assistant","model":"claude-opus-4-8",
             "content":[{"type":"tool_use","id":"toolu_1","name":"whatsapp_reply","input":{"text":"Ok!"}}],
             "stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":2}}
            """.trimIndent(),
        ))
        val t = transport(model = null)

        val result = t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Ok!", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals("claude-opus-4-8", root.getValue("model").jsonPrimitive.content)
    }

    @Test fun `actV2 sends the classified state value in the prompt`(): Unit = runBlocking {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val action = Action.InvokeLlmV2(
            goal = "rispondi tenendo conto del voltaggio",
            stateContext = listOf(
                ApprovedStateContext(
                    query = query,
                    valueType = StateValueType.NUMBER,
                    policyVersion = StateQueryPolicy.VERSION,
                    integrity = IntegrityLabel.CLEAN,
                    confidentiality = ConfidentialityLabel.SECRET,
                ),
            ),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 60_000,
        )
        server.enqueue(jsonResponse(
            """
            {"id":"msg_4","type":"message","role":"assistant","model":"claude-opus-4-8",
             "content":[{"type":"tool_use","id":"toolu_1","name":"whatsapp_reply","input":{"text":"Ricarico ora."}}],
             "stop_reason":"tool_use","usage":{"input_tokens":40,"output_tokens":5}}
            """.trimIndent(),
        ))
        val t = transport(model = "claude-opus-4-8")

        val result = t.actV2(
            fireContext().copy(state = fireContext().state.copy(queryValues = mapOf(query.canonicalId to "4200"))),
            action,
        )

        assertEquals("Ricarico ora.", result.text)
        val raw = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue(raw.contains("4200"), "il valore di stato classificato deve entrare nel prompt")
    }

    @Test fun `401 maps to AUTH without leaking the key`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
                .setResponseCode(401),
        )
        val t = transport(model = "claude-opus-4-8")

        val error = assertFailsWith<TransportException> {
            t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        }

        assertEquals(TransportErrorKind.AUTH, error.kind)
        assertFalse(error.message.orEmpty().contains(API_KEY))
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(API_KEY, request.getHeader("x-api-key"))
    }

    @Test fun `insufficient credit on 400 maps to BUDGET without leaking body`(): Unit = runBlocking {
        val creditBody = """{"type":"error","error":{"type":"invalid_request_error",""" +
            """"message":"Your credit balance is too low to access the Claude API."}}"""
        server.enqueue(jsonResponse(creditBody).setResponseCode(400))
        val t = transport(model = "claude-opus-4-8")

        val error = assertFailsWith<TransportException> {
            t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        }

        assertEquals(TransportErrorKind.BUDGET, error.kind)
        assertFalse(error.message.orEmpty().contains("credit balance"), "il body remoto non deve trapelare")
        assertFalse(error.message.orEmpty().contains(API_KEY))
    }

    @Test fun `payment required and rate limit and server and generic errors are typed`(): Unit = runBlocking {
        val cases = mapOf(
            402 to TransportErrorKind.BUDGET,
            429 to TransportErrorKind.RATE_LIMIT,
            400 to TransportErrorKind.HTTP, // 400 non-credit resta HTTP
            404 to TransportErrorKind.HTTP,
            500 to TransportErrorKind.HTTP,
        )
        cases.forEach { (code, kind) ->
            server.enqueue(
                jsonResponse("""{"type":"error","error":{"type":"api_error","message":"x"}}""").setResponseCode(code),
            )
            val t = transport(model = "claude-opus-4-8")
            val error = assertFailsWith<TransportException> {
                t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
            }
            assertEquals(kind, error.kind, "code $code")
            assertFalse(error.message.orEmpty().contains(API_KEY))
        }
    }

    @Test fun `missing api key fails before any network call`(): Unit = runBlocking {
        val t = transport(model = "claude-opus-4-8", apiKey = null)

        val error = assertFailsWith<TransportException> {
            t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        }

        assertEquals(TransportErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    // --- compile: prompt Hermes + sentinel @@META@@ (nessun tool) ---

    @Test fun `compile parses the sentinel draft and posts to v1 messages without tools`(): Unit = runBlocking {
        val content = "Ho creato l'automazione per il silenzioso serale.\n" +
            "@@META@@ {\"draft\": {\"name\":\"dnd sera\"," +
            "\"trigger\":{\"type\":\"time\",\"cron\":\"0 23 * * *\",\"tz\":\"Europe/Rome\"}," +
            "\"actions\":[{\"type\":\"set_dnd\",\"mode\":\"PRIORITY\"}]}}"
        server.enqueue(jsonResponse(textContent(content)))
        val t = transport(model = "claude-opus-4-8")

        val result = t.compile("metti in silenzioso alle 23", manifest(), deviceState())

        val draft = assertNotNull(result.draft)
        assertEquals("dnd sera", draft.name)
        assertNull(result.metaError)
        assertTrue(result.reply.startsWith("Ho creato"))

        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/messages", request.path)
        assertEquals(API_KEY, request.getHeader("x-api-key"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertTrue("max_tokens" in root)
        assertFalse("tools" in root, "la strada primaria del compile è la riga sentinel nel testo")
        val system = root.getValue("system").jsonPrimitive.content
        assertTrue(system.contains("REGOLE VINCOLANTI"))
        assertTrue(system.contains("@@META@@"))
        assertTrue(system.contains("AutomationDraft"))
        val user = root.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(user.contains("metti in silenzioso alle 23"))
    }

    @Test fun `compile without sentinel yields draft_missing metaError`(): Unit = runBlocking {
        server.enqueue(jsonResponse(textContent("Ciao! Come posso aiutarti oggi?")))
        val t = transport(model = "claude-opus-4-8")

        val result = t.compile("ciao", manifest(), deviceState())

        assertNull(result.draft)
        assertEquals("draft_missing", result.metaError)
        assertTrue(result.reply.contains("Ciao"))
    }

    @Test fun `compile with empty content yields empty_response metaError`(): Unit = runBlocking {
        server.enqueue(jsonResponse(textContent("")))
        val t = transport(model = "claude-opus-4-8")

        val result = t.compile("crea qualcosa", manifest(), deviceState())

        assertNull(result.draft)
        assertEquals("empty_response", result.metaError)
    }

    private fun textContent(text: String): String =
        """
        {"id":"msg_c","type":"message","role":"assistant","model":"claude-opus-4-8",
         "content":[{"type":"text","text":${JsonPrimitive(text)}}],
         "stop_reason":"end_turn","usage":{"input_tokens":200,"output_tokens":40}}
        """.trimIndent()

    private fun manifest(): CapabilityManifest = CapabilityManifest(
        deviceModel = "Pixel 9",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
        availableTools = listOf("set_dnd", "whatsapp_reply", "invoke_llm"),
        unavailableTools = emptyMap(),
        whitelistedContacts = listOf(WhitelistedContact("Moglie", "jid:private")),
    )

    private fun deviceState(): DeviceState = DeviceState(
        values = mapOf("ringer" to "normal"),
        foregroundApp = "com.whatsapp",
    )

    private fun jsonResponse(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun fireContext(isGroup: Boolean = false) = FireContext(
        event = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            conversationId = "jid:private",
            sender = "Moglie",
            title = "Moglie",
            text = "ciao",
            isGroup = isGroup,
            notificationKey = "sbn:private",
        ),
        state = DeviceState(
            values = mapOf("ringer" to "normal", "private_state" to "secret"),
            foregroundApp = "com.whatsapp",
        ),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private companion object {
        const val API_KEY = "sk-ant-test-secret-key-do-not-leak-0123456789"
    }
}
