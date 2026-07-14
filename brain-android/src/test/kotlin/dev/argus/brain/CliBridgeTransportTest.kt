package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.GeoPoint
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.security.MessageDigest
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

class CliBridgeTransportTest {
    private lateinit var server: MockWebServer

    private val manifest = CapabilityManifest(
        deviceModel = "OnePlus CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.INTERNET"),
        availableTools = listOf("set_dnd", "set_wifi"),
        unavailableTools = mapOf("vision.analyze" to "provider assente"),
        whitelistedContacts = listOf(WhitelistedContact("Moglie", "jid:42")),
    )

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun transport(
        client: OkHttpClient = CliBridgeTransport.defaultClient(),
        token: String? = TOKEN,
        requestId: String = REQUEST_ID,
    ) = CliBridgeTransport(
        baseUrl = server.url("/").toString(),
        authProvider = BridgeAuthProvider { token },
        client = client,
        allowCleartextForTests = true,
        requestIdFactory = { requestId },
    )

    @Test fun `request is authenticated versioned structured and redacts device state`(): Unit = runBlocking {
        server.enqueue(validNoDraftResponse())
        val state = DeviceState(
            values = mapOf("ringer" to "normal", "battery" to "80\ninject", "private" to "secret"),
            foregroundApp = "com.example.current",
            location = GeoPoint(37.123, 15.456),
        )

        transport().compile("dopo le 23 metti DND", manifest, state)

        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/compile", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals(REQUEST_ID, request.getHeader("Idempotency-Key"))
        assertEquals("application/json", request.getHeader("Accept"))
        val raw = request.body.readUtf8()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(1, root.getValue("schema_version").jsonPrimitive.content.toInt())
        assertEquals(REQUEST_ID, root.getValue("request_id").jsonPrimitive.content)
        val sentManifest = root.getValue("manifest").jsonObject
        assertEquals(36, sentManifest.getValue("android_api").jsonPrimitive.content.toInt())
        val sentState = root.getValue("state").jsonObject
        assertEquals("normal", sentState.getValue("values").jsonObject.getValue("ringer").jsonPrimitive.content)
        assertFalse("battery" in sentState.getValue("values").jsonObject)
        assertFalse("private" in sentState.getValue("values").jsonObject)
        assertEquals("com.example.current", sentState.getValue("foreground_app").jsonPrimitive.content)
        assertTrue(sentState.getValue("location_available").jsonPrimitive.boolean)
        assertFalse(raw.contains("37.123"))
        assertFalse(raw.contains("15.456"))
    }

    @Test fun `health uses the same authenticated strict boundary`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""{"schema_version":1,"status":"ok","model":"gpt-5.5"}"""))

        val health = transport().health()

        assertEquals(1, health.schemaVersion)
        assertEquals("gpt-5.5", health.model)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/health", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
    }

    @Test fun `act is deterministic minimal and never sends local reply handles or target ids`(): Unit = runBlocking {
        val expectedRequestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Va bene, a dopo."},"error_code":null}""",
        ))

        val result = transport().act(
            context = fireContext(),
            goal = "rispondi in modo cordiale",
            contextSources = listOf("notification", "state"),
            allowedTools = listOf("whatsapp_reply"),
        )

        assertEquals("Va bene, a dopo.", result.text)
        assertNull(result.metaError)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/act", request.path)
        assertEquals(expectedRequestId, request.getHeader("Idempotency-Key"))
        val raw = request.body.readUtf8()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(expectedRequestId, root.getValue("request_id").jsonPrimitive.content)
        assertFalse(raw.contains("jid:private"))
        assertFalse(raw.contains("sbn:private"))
        assertFalse(raw.contains("45.123"))
        assertFalse(raw.contains("11.456"))
        assertFalse(raw.contains("private_state"))
        val context = root.getValue("context").jsonObject
        val notification = context.getValue("notification").jsonObject
        assertEquals("com.whatsapp", notification.getValue("package").jsonPrimitive.content)
        assertEquals("ciao", notification.getValue("text").jsonPrimitive.content)
        val state = assertNotNull(context["state"]).jsonObject
        assertEquals("normal", state.getValue("values").jsonObject.getValue("ringer").jsonPrimitive.content)
    }

    @Test fun `act omits state unless explicitly approved and rejects unsafe lanes before network`(): Unit = runBlocking {
        val expectedRequestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Ciao"},"error_code":null}""",
        ))

        transport().act(
            context = fireContext(),
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
        )
        val body = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertTrue(body.getValue("context").jsonObject.getValue("state").jsonPrimitive.content == "null")

        val invalid = listOf(
            Triple(listOf("notification", "screen"), listOf("whatsapp_reply"), fireContext()),
            Triple(listOf("notification"), listOf("shell.run"), fireContext()),
            Triple(listOf("notification"), listOf("whatsapp_reply"), fireContext(isGroup = true)),
        )
        invalid.forEach { (sources, tools, context) ->
            val error = assertFailsWith<BridgeException> {
                transport().act(context, "rispondi", sources, tools)
            }
            assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        }
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `act response cannot choose a target or smuggle unknown fields`(): Unit = runBlocking {
        val requestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$requestId","result":{"text":"Ciao","target":"altro"},"error_code":null}""",
        ))

        val error = assertFailsWith<BridgeException> {
            transport().act(
                fireContext(),
                "rispondi",
                listOf("notification"),
                listOf("whatsapp_reply"),
            )
        }

        assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
    }

    @Test fun `valid strict response decodes a draft`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$REQUEST_ID","reply":"ok","meta":{"draft":{"name":"wifi off","trigger":{"type":"time","at":"2026-07-15T23:00","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":false}]},"error_code":null}}"""
        ))

        val result = transport().compile("x", manifest, DeviceState())

        assertEquals("ok", result.reply)
        assertNotNull(result.draft)
        assertNull(result.metaError)
    }

    @Test fun `explicit no-draft response preserves reply and error code`(): Unit = runBlocking {
        server.enqueue(validNoDraftResponse("Serve un orario."))

        val result = transport().compile("boh", manifest, DeviceState())

        assertEquals("Serve un orario.", result.reply)
        assertNull(result.draft)
        assertEquals("clarification_required", result.metaError)
    }

    @Test fun `missing incompatible or mismatched protocol fields fail closed`(): Unit = runBlocking {
        val invalid = listOf(
            """{"request_id":"$REQUEST_ID","reply":"x","meta":{"draft":null,"error_code":"x"}}""",
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"x","meta":{"draft":null,"error_code":"x"}}""",
            """{"schema_version":1,"request_id":"other","reply":"x","meta":{"draft":null,"error_code":"x"}}""",
            """{"schema_version":1,"request_id":"$REQUEST_ID","reply":{},"meta":{"draft":null,"error_code":"x"}}""",
            """{"schema_version":1,"request_id":"$REQUEST_ID","reply":"x","meta":{"draft":null,"error_code":"x"},"surprise":true}""",
        )

        invalid.forEach { body ->
            server.enqueue(jsonResponse(body))
            val error = assertFailsWith<BridgeException> {
                transport().compile("x", manifest, DeviceState())
            }
            assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
        }
    }

    @Test fun `malformed draft is fail-soft after a valid envelope`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$REQUEST_ID","reply":"x","meta":{"draft":{"name":"bad","trigger":{"type":"time","cron":"0 23 * * *"},"actions":[]},"error_code":null}}"""
        ))

        val result = transport().compile("x", manifest, DeviceState())

        assertNull(result.draft)
        assertEquals("draft_invalid", result.metaError)
    }

    @Test fun `non-json content type is rejected before parsing`(): Unit = runBlocking {
        server.enqueue(MockResponse().addHeader("Content-Type", "text/html").setBody("{}"))

        val error = assertFailsWith<BridgeException> {
            transport().compile("x", manifest, DeviceState())
        }

        assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
    }

    @Test fun `oversized body is rejected without unbounded read`(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setChunkedBody("x".repeat(CliBridgeTransport.MAX_RESPONSE_BYTES + 1), 8192)
        )

        val error = assertFailsWith<BridgeException> {
            transport().compile("x", manifest, DeviceState())
        }

        assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
    }

    @Test fun `missing token fails before any network call`(): Unit = runBlocking {
        val error = assertFailsWith<BridgeException> {
            transport(token = null).compile("x", manifest, DeviceState())
        }

        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `401 and generic HTTP errors are typed without response leakage`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""{"secret":"must-not-leak"}""").setResponseCode(401))
        var error = assertFailsWith<BridgeException> {
            transport().compile("x", manifest, DeviceState())
        }
        assertEquals(BridgeErrorKind.AUTH, error.kind)
        assertFalse(error.message.orEmpty().contains("must-not-leak"))

        server.enqueue(jsonResponse("""{"error":"down"}""").setResponseCode(503))
        error = assertFailsWith {
            transport().compile("x", manifest, DeviceState())
        }
        assertEquals(BridgeErrorKind.HTTP, error.kind)
        assertEquals(503, error.statusCode)
    }

    @Test fun `timeout is typed`(): Unit = runBlocking {
        val fast = OkHttpClient.Builder()
            .callTimeout(250, TimeUnit.MILLISECONDS)
            .readTimeout(250, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val error = assertFailsWith<BridgeException> {
            transport(client = fast).compile("x", manifest, DeviceState())
        }

        assertEquals(BridgeErrorKind.TIMEOUT, error.kind)
    }

    @Test fun `coroutine cancellation cancels the in-flight call and propagates`(): Unit = runBlocking {
        val client = CliBridgeTransport.defaultClient()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async {
            transport(client = client).compile("x", manifest, DeviceState())
        }
        withTimeout(2_000) {
            while (client.dispatcher.runningCallsCount() == 0) yield()
        }

        pending.cancelAndJoin()

        assertTrue(pending.isCancelled)
        assertFailsWith<CancellationException> { pending.await() }
        withTimeout(2_000) {
            while (client.dispatcher.runningCallsCount() != 0) yield()
        }
    }

    @Test fun `cleartext base URLs are unavailable outside explicit tests`() {
        assertFailsWith<IllegalArgumentException> {
            CliBridgeTransport(
                baseUrl = "http://100.80.142.65:8090",
                authProvider = BridgeAuthProvider { TOKEN },
            )
        }
    }

    private fun validNoDraftResponse(reply: String = "ok") = jsonResponse(
        """{"schema_version":1,"request_id":"$REQUEST_ID","reply":"$reply","meta":{"draft":null,"error_code":"clarification_required"}}"""
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
            location = GeoPoint(45.123, 11.456),
        ),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private fun actRequestId(executionId: String, actionIndex: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$executionId\u0000$actionIndex".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "act-$digest"
    }

    private companion object {
        const val TOKEN = "test-token"
        const val REQUEST_ID = "req-test-1"
    }
}
