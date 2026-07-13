package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.model.Action
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test puri JVM su MockWebServer — nessun device, nessun Hermes live. Coprono entrambe le modalità
 * (/compile preferita e /chat fallback), il fail-soft su draft assente/malformato e gli errori di rete.
 */
class HermesBrainTest {

    private lateinit var server: MockWebServer
    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private val manifest = CapabilityManifest(
        deviceModel = "Pixel-TEST-9",
        androidVersion = 35,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.INTERNET"),
        availableTools = listOf("set_dnd", "set_wifi"),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
    )
    private val state = DeviceState(values = mapOf("ringer" to "normal"))

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun brain(useCompile: Boolean, client: OkHttpClient = CliBridgeTransport.defaultClient()) =
        HermesBrain(CliBridgeTransport(baseUrl(), useCompileEndpoint = useCompile, client = client))

    // --- /compile (preferita) -------------------------------------------------------------------

    @Test fun `compile endpoint happy path returns Time DND draft`(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"reply":"ok","meta":{"draft":{"name":"dnd dopo le 23",""" +
                    """"trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},""" +
                    """"actions":[{"type":"set_dnd","mode":"PRIORITY"}]}},"schema_version":1}"""
            )
        )

        val r = brain(useCompile = true).compile("dopo le 23 metti DND", manifest, state)

        assertEquals("ok", r.reply)
        assertNull(r.metaError)
        val d = assertNotNull(r.draft)
        assertEquals("dnd dopo le 23", d.name)
        assertEquals(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), d.trigger)
        assertEquals(listOf(Action.SetDnd(DndMode.PRIORITY)), d.actions)
    }

    @Test fun `request body carries rendered manifest and hits compile path`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"reply":"ok","meta":{"draft":null}}"""))

        brain(useCompile = true).compile("dopo le 23 metti DND", manifest, state)

        val req = server.takeRequest()
        assertEquals("/compile", req.path)
        assertEquals("POST", req.method)
        val root = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("dopo le 23 metti DND", root["message"]?.jsonPrimitive?.content)
        val sentManifest = root["manifest"]?.jsonPrimitive?.content ?: ""
        assertTrue(sentManifest.contains("Pixel-TEST-9"), "manifest inviato deve contenere il render reale: $sentManifest")
        assertTrue(sentManifest.contains("set_dnd"), "manifest inviato deve elencare i tool disponibili")
    }

    @Test fun `compile missing draft populates metaError and preserves reply`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"reply":"Non ho capito, riprova","meta":{},"schema_version":1}"""))

        val r = brain(useCompile = true).compile("boh", manifest, state)

        assertEquals("Non ho capito, riprova", r.reply)
        assertNull(r.draft)
        assertNotNull(r.metaError)
    }

    @Test fun `compile malformed draft populates metaError without crashing`(): Unit = runBlocking {
        // trigger time senza `tz` (campo obbligatorio) → decode fallisce → metaError, reply preservato.
        server.enqueue(
            MockResponse().setBody(
                """{"reply":"x","meta":{"draft":{"name":"bad","trigger":{"type":"time","cron":"0 23 * * *"},"actions":[]}}}"""
            )
        )

        val r = brain(useCompile = true).compile("x", manifest, state)

        assertEquals("x", r.reply)
        assertNull(r.draft)
        assertNotNull(r.metaError)
    }

    @Test fun `compile non-json body populates metaError without crashing`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("not json at all"))

        val r = brain(useCompile = true).compile("x", manifest, state)

        assertNull(r.draft)
        assertNotNull(r.metaError)
    }

    // --- /chat (fallback, usa CliBridgeParser) --------------------------------------------------

    @Test fun `chat fallback parses draft via CliBridgeParser sentinel`(): Unit = runBlocking {
        val innerMeta = """@@META@@ {"draft":{"name":"dnd sera",""" +
            """"trigger":{"type":"time","at":"2026-07-15T23:00","tz":"Europe/Rome"},""" +
            """"actions":[{"type":"set_dnd","mode":"TOTAL"}]}}"""
        val chatBody = buildJsonObject { put("reply", "Fatto.\n$innerMeta") }.toString()
        server.enqueue(MockResponse().setBody(chatBody))

        val r = brain(useCompile = false).compile("dopo le 23 metti DND", manifest, state)

        assertEquals("Fatto.", r.reply)
        assertNull(r.metaError)
        val d = assertNotNull(r.draft)
        assertEquals(Trigger.Time(at = "2026-07-15T23:00", tz = "Europe/Rome"), d.trigger)
        assertEquals(listOf(Action.SetDnd(DndMode.TOTAL)), d.actions)
    }

    @Test fun `chat mode hits chat path`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"reply":"ciao"}"""))

        brain(useCompile = false).compile("ciao", manifest, state)

        assertEquals("/chat", server.takeRequest().path)
    }

    @Test fun `chat fallback with no sentinel yields reply only`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"reply":"Non ho capito, puoi ripetere?"}"""))

        val r = brain(useCompile = false).compile("...", manifest, state)

        assertEquals("Non ho capito, puoi ripetere?", r.reply)
        assertNull(r.draft)
        assertNull(r.metaError)
    }

    // --- errori di rete: BridgeException + mapping a CompileResult (mai crash) -------------------

    @Test fun `timeout surfaces BridgeException and maps to metaError`(): Unit = runBlocking {
        val fast = OkHttpClient.Builder()
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val transport = CliBridgeTransport(baseUrl(), useCompileEndpoint = true, client = fast)

        // 1) il transport espone l'errore tipizzato...
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val ex = try { transport.compile("x", "m"); null } catch (e: BridgeException) { e }
        val e = assertNotNull(ex)
        assertEquals(BridgeErrorKind.TIMEOUT, e.kind)

        // 2) ...e HermesBrain lo mappa a un CompileResult gestibile (nessun crash).
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val r = HermesBrain(transport).compile("x", manifest, state)
        assertNull(r.draft)
        assertNotNull(r.metaError)
        assertTrue(r.metaError!!.contains("timeout"), "metaError deve indicare il timeout: ${r.metaError}")
    }

    @Test fun `network error surfaces BridgeException and maps to metaError`(): Unit = runBlocking {
        val target = baseUrl()
        server.shutdown() // nessuno più in ascolto → ConnectException
        val transport = CliBridgeTransport(target, useCompileEndpoint = true, client = CliBridgeTransport.defaultClient(2))

        val ex = try { transport.compile("x", "m"); null } catch (e: BridgeException) { e }
        val e = assertNotNull(ex)
        assertEquals(BridgeErrorKind.NETWORK, e.kind)

        val r = HermesBrain(transport).compile("x", manifest, state)
        assertNull(r.draft)
        assertNotNull(r.metaError)
        assertTrue(r.metaError!!.contains("network"), "metaError deve indicare l'errore di rete: ${r.metaError}")
    }

    @Test fun `http error surfaces BridgeException of kind HTTP`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val transport = CliBridgeTransport(baseUrl(), useCompileEndpoint = true)

        val ex = try { transport.compile("x", "m"); null } catch (e: BridgeException) { e }
        val e = assertNotNull(ex)
        assertEquals(BridgeErrorKind.HTTP, e.kind)
    }
}
