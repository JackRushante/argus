package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HermesBrainTest {
    private lateinit var server: MockWebServer

    private val manifest = CapabilityManifest(
        deviceModel = "Pixel-TEST-9",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.INTERNET"),
        availableTools = listOf("set_dnd"),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
    )

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun brain() = HermesBrain(
        CliBridgeTransport(
            baseUrl = server.url("/").toString(),
            authProvider = BridgeAuthProvider { "test-token" },
            client = CliBridgeTransport.defaultClient(),
            allowCleartextForTests = true,
            requestIdFactory = { "req-brain-1" },
        )
    )

    @Test fun `compile returns the strict bridge draft`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"req-brain-1","reply":"ok","meta":{"draft":{"name":"dnd dopo le 23","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_dnd","mode":"PRIORITY"}]},"error_code":null}}"""
        ))

        val result = brain().compile("dopo le 23 metti DND", manifest, DeviceState())

        assertEquals("ok", result.reply)
        assertNull(result.metaError)
        val draft = assertNotNull(result.draft)
        assertEquals(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), draft.trigger)
        assertEquals(listOf(Action.SetDnd(DndMode.PRIORITY)), draft.actions)
    }

    @Test fun `typed bridge failures become stable user-safe errors`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""{"error":"unauthorized"}""").setResponseCode(401))

        val result = brain().compile("x", manifest, DeviceState())

        assertNull(result.draft)
        assertEquals("bridge_auth", result.metaError)
        assertEquals("Non riesco a contattare l'assistente adesso. Riprova tra poco.", result.reply)
    }

    @Test fun `act returns text only and maps transport failures to a stable typed error`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"act-d942ad1dca04866665b9e75219976300a81e2374066a956549ddd541830986e3","result":{"text":"Ciao"},"error_code":null}""",
        ))
        val context = FireContext(
            event = TriggerEvent.NotificationPosted(
                pkg = "com.whatsapp",
                text = "ciao",
                isGroup = false,
            ),
            state = DeviceState(),
            automationId = AutomationId("automation-1"),
            approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
            eventId = TriggerEventId("event-1"),
            executionId = ExecutionId("execution-1"),
            actionIndex = 0,
        )

        val success = brain().act(
            context,
            "rispondi",
            listOf("notification"),
            listOf("whatsapp_reply"),
        )
        assertEquals("Ciao", success.text)
        assertNull(success.metaError)

        server.enqueue(jsonResponse("""{"error":"down"}""").setResponseCode(503))
        val failure = brain().act(
            context.copy(executionId = ExecutionId("execution-2")),
            "rispondi",
            listOf("notification"),
            listOf("whatsapp_reply"),
        )
        assertNull(failure.text)
        assertEquals("bridge_http", failure.metaError)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
