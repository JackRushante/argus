package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.runtime.DeviceState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/**
 * 0.5.1 F3b sul provider Anthropic: `output_config.format` `json_schema` (GA, senza header beta). Lo schema di
 * Anthropic e' chiuso e non ricorsivo, quindi `draft` viaggia come stringa che contiene l'oggetto. Il protocollo
 * `@@META@@` resta la base e il ripiego.
 */
class AnthropicStructuredOutputTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun transport(structuredOutput: StructuredOutputMode? = null): AnthropicMessagesTransport {
        val catalog = ProviderCatalog.spec(ProviderId.ANTHROPIC)
        val spec = structuredOutput?.let { catalog.copy(quirks = catalog.quirks.copy(structuredOutput = it)) } ?: catalog
        return AnthropicMessagesTransport(
            spec = spec,
            config = ProviderConfig(ProviderId.ANTHROPIC, server.url("/").toString().trimEnd('/'), "claude-haiku-5-5"),
            apiKey = { API_KEY },
            client = CliBridgeTransport.defaultClient(),
            allowCleartextForTests = true,
        )
    }

    private val draftJson =
        """{"name":"dnd sera","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_dnd","mode":"PRIORITY"}]}"""

    /** L'envelope di Anthropic: `draft` e' l'oggetto scritto come stringa JSON. */
    private fun envelope(reply: String, draft: String? = draftJson, errorCode: String? = null): String {
        val draftField = draft?.let { JsonPrimitive(it).toString() } ?: "null"
        val codeField = errorCode?.let { JsonPrimitive(it).toString() } ?: "null"
        return """{"reply":${JsonPrimitive(reply)},"draft":$draftField,"error_code":$codeField}"""
    }

    private fun sentinel(reply: String = "Fatto.") = "$reply\n@@META@@ {\"draft\": $draftJson}"

    private fun text(content: String, stopReason: String = "end_turn"): String = """
        {"id":"msg_c","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[{"type":"thinking","thinking":"ragiono"},{"type":"text","text":${JsonPrimitive(content)}}],
         "stop_reason":"$stopReason","usage":{"input_tokens":200,"output_tokens":40}}
    """.trimIndent()

    private fun root(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    private fun rejected(code: Int = 400) = jsonResponse(
        """{"type":"error","error":{"type":"invalid_request_error","message":"output_config.format is not supported for this model"}}""",
    ).setResponseCode(code)

    @Test fun `compile asks for a closed json schema in output_config and reads the string draft`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text(envelope("Ho creato la regola."))))

        val result = transport().compile("metti in silenzioso alle 23", manifest(), deviceState())

        assertNull(result.metaError)
        assertEquals("Ho creato la regola.", result.reply)
        assertEquals("dnd sera", assertNotNull(result.draft).name)
        assertEquals(true, result.structured)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/messages", request.path)
        assertNull(request.getHeader("anthropic-beta"), "output_config e' GA: nessun header beta")
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val body = root(request)
        val format = body.getValue("output_config").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val schema = format.getValue("schema").jsonObject
        val fields = setOf("reply", "draft", "error_code")
        assertEquals(fields, schema.getValue("properties").jsonObject.keys)
        assertEquals(fields, schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        // Anthropic vuole ogni oggetto chiuso: additionalProperties:false, mai un oggetto libero.
        assertEquals("false", schema.getValue("additionalProperties").toString())
        assertFalse("tools" in body, "nessun tool forzato: il ragionamento resta libero")
        assertFalse("tool_choice" in body)
        val system = body.getValue("system").jsonPrimitive.content
        assertTrue(AgentMessageSupport.STRUCTURED_JSON_STRING_DRAFT_RULE in system)
    }

    @Test fun `a structured clarification keeps the question and the error code`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text(envelope("A che ora?", draft = null, errorCode = "clarification_required"))))

        val result = transport().compile("silenzioso", manifest(), deviceState())

        assertNull(result.draft)
        assertEquals("clarification_required", result.metaError)
        assertEquals("A che ora?", result.reply)
        assertEquals(true, result.structured)
    }

    @Test fun `a model that does not support output_config falls back to the base protocol and remembers it`(): Unit = runBlocking {
        server.enqueue(rejected())
        server.enqueue(jsonResponse(text(sentinel("Prima."))))
        server.enqueue(jsonResponse(text(sentinel("Seconda."))))
        val t = transport()

        val first = t.compile("crea", manifest(), deviceState())
        val second = t.compile("crea ancora", manifest(), deviceState())

        assertEquals("dnd sera", assertNotNull(first.draft).name)
        assertEquals(false, first.structured)
        assertEquals("Seconda.", second.reply)
        assertEquals(3, server.requestCount, "dopo il rifiuto non c'e' piu' il giro a vuoto")
        val requests = List(3) { root(assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))) }
        assertEquals(listOf(true, false, false), requests.map { "output_config" in it })
        assertEquals(AgentMessageSupport.compileSystemText(), requests[1].getValue("system").jsonPrimitive.content)
    }

    @Test fun `a credit problem or a gateway error is not a format rejection`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse("""{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low"}}""")
                .setResponseCode(400),
        )
        val credit = assertFailsWith<TransportException> { transport().compile("crea", manifest(), deviceState()) }
        assertEquals(TransportErrorKind.BUDGET, credit.kind)
        assertEquals(1, server.requestCount)

        server.enqueue(jsonResponse("""{"type":"error","error":{"type":"overloaded_error","message":"x"}}""").setResponseCode(529))
        val overloaded = assertFailsWith<TransportException> { transport().compile("crea", manifest(), deviceState()) }
        assertEquals(529, overloaded.statusCode)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a refusal that is not about the format is not remembered`(): Unit = runBlocking {
        repeat(2) { server.enqueue(rejected(404)) }
        val t = transport()

        val error = assertFailsWith<TransportException> { t.compile("crea", manifest(), deviceState()) }

        assertEquals(404, error.statusCode)
        assertEquals(2, server.requestCount)
        server.enqueue(jsonResponse(text(envelope("Fatto."))))
        t.compile("crea", manifest(), deviceState())
        repeat(2) { server.takeRequest(2, TimeUnit.SECONDS) }
        assertTrue("output_config" in root(assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))))
    }

    @Test fun `a model that ignores the format and writes the sentinel line is still read`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text(sentinel("Ho creato la regola."))))

        val result = transport().compile("crea", manifest(), deviceState())

        assertEquals("dnd sera", assertNotNull(result.draft).name)
        assertEquals(false, result.structured)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a cut or malformed envelope is a typed format error and max_tokens is a truncation`(): Unit = runBlocking {
        val t = transport()

        server.enqueue(jsonResponse(text("""{"reply":"x","draft":"niente da vedere","error_code":null}""")))
        val notAnObject = t.compile("crea", manifest(), deviceState())
        assertNull(notAnObject.draft)
        assertEquals("meta_parse_error", notAnObject.metaError)
        assertEquals(200L, assertNotNull(notAnObject.usage).inputTokens)

        server.enqueue(jsonResponse(text("""{"reply":"Fatto.","draft":"{\"name\":\"dnd","error_code":null""")))
        val cut = t.compile("crea", manifest(), deviceState())
        assertNull(cut.draft)
        assertEquals("meta_json_missing", cut.metaError)

        server.enqueue(jsonResponse(text("""{"reply":"Fatto.","draft":"{\"name\":\"dnd""", stopReason = "max_tokens")))
        assertEquals("output_truncated", t.compile("crea", manifest(), deviceState()).metaError)
    }

    @Test fun `with the structured output off the request is the base protocol`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text(sentinel())))

        val result = transport(structuredOutput = StructuredOutputMode.NONE).compile("crea", manifest(), deviceState())

        assertNotNull(result.draft)
        assertEquals(false, result.structured)
        val body = root(assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)))
        assertFalse("output_config" in body)
        assertEquals(AgentMessageSupport.compileSystemText(), body.getValue("system").jsonPrimitive.content)
    }

    @Test fun `act and health never carry an output_config`(): Unit = runBlocking {
        server.enqueue(jsonResponse(text("Promemoria.")))
        server.enqueue(jsonResponse(text("pong")))
        val t = transport()

        t.act(timerContext(), "genera un promemoria", emptyList(), emptyList())
        t.health()

        repeat(2) {
            assertFalse("output_config" in assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
        }
    }

    private fun timerContext() = dev.argus.engine.runtime.FireContext(
        event = dev.argus.engine.runtime.TriggerEvent.TimeFired(
            automationId = dev.argus.engine.model.AutomationId("automation-1"),
            approvalFingerprint = dev.argus.engine.model.ApprovalFingerprint("0".repeat(64)),
        ),
        state = DeviceState(values = mapOf("ringer" to "normal"), foregroundApp = "com.whatsapp"),
        automationId = dev.argus.engine.model.AutomationId("automation-1"),
        approvalFingerprint = dev.argus.engine.model.ApprovalFingerprint("0".repeat(64)),
        eventId = dev.argus.engine.runtime.TriggerEventId("event-1"),
        executionId = dev.argus.engine.runtime.ExecutionId("execution-1"),
        actionIndex = 0,
    )

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

    private fun deviceState(): DeviceState = DeviceState(
        values = mapOf("ringer" to "normal"),
        foregroundApp = "com.whatsapp",
    )

    private fun jsonResponse(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private companion object {
        const val API_KEY = "sk-ant-test-secret-key-do-not-leak-0123456789"
    }
}
