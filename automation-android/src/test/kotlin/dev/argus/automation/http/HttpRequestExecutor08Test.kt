package dev.argus.automation.http

import dev.argus.engine.model.Action
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.SecretRef
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.secrets.SecretValueSource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.8: capture of the response, values from the secrets vault and plain http on the local network, against a
 * fake server on the loopback.
 */
class HttpRequestExecutor08Test {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        other = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { other.shutdown() }
    }

    private val base: String get() = "http://127.0.0.1:${server.port}"
    private val token = "sk-live-0123456789abcdef"
    private val reads = mutableListOf<String>()
    private val vault = SecretValueSource { name ->
        reads += name
        when (name) {
            "ha_token" -> token
            "accented" -> "città"
            "q_key" -> "a b&c=d"
            else -> null
        }
    }
    private val bearer = SecretRef("Authorization", "ha_token", "Bearer ")

    private fun request(
        path: String = "/api",
        captureAs: String? = null,
        extract: String? = null,
        secretHeaders: List<SecretRef>? = null,
        secretQuery: List<SecretRef>? = null,
        url: String = base + path,
    ) = Action.HttpRequest(
        HttpMethod.GET, url,
        captureAs = captureAs, extract = extract, secretHeaders = secretHeaders, secretQuery = secretQuery,
    )

    // --- Capture -----------------------------------------------------------------------------------

    @Test
    fun `a 2xx response is captured whole or through a json pointer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("  21.5 gradi\n"))
        val whole = HttpRequestExecutor().run(request(captureAs = "temp"))
        assertEquals(ActionResult.Success, whole.result)
        assertEquals("21.5 gradi", whole.captured)

        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"state":"21.5","attributes":{"unit":"°C"}}"""),
        )
        val pointed = HttpRequestExecutor().run(request(captureAs = "temp", extract = "/attributes/unit"))
        assertEquals(ActionResult.Success, pointed.result)
        assertEquals("°C", pointed.captured)
        assertFalse("°C" in pointed.toString())
    }

    @Test
    fun `without captureAs nothing is kept`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val outcome = HttpRequestExecutor().run(request())
        assertEquals(ActionResult.Success, outcome.result)
        assertNull(outcome.captured)
    }

    @Test
    fun `a failed capture is a closed code and a non 2xx never captures`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"a":{"b":1}}"""))
        assertEquals(
            ActionResult.Failure("http_capture_not_scalar"),
            HttpRequestExecutor().run(request(captureAs = "v", extract = "/a")).result,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
        assertEquals(
            ActionResult.Failure("http_capture_not_json"),
            HttpRequestExecutor().run(request(captureAs = "v", extract = "/a")).result,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(4_097)))
        assertEquals(ActionResult.Failure("http_capture_too_long"), HttpRequestExecutor().run(request(captureAs = "v")).result)
        server.enqueue(MockResponse().setResponseCode(404).setBody("not here"))
        val missing = HttpRequestExecutor().run(request(captureAs = "v"))
        assertEquals(ActionResult.Failure("http_status_404"), missing.result)
        assertNull(missing.captured)
    }

    @Test
    fun `a response over the size limit fails the capture instead of being cut`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(64 * 1024 + 1) { 'a'.code.toByte() })))
        assertEquals(
            ActionResult.Failure("http_capture_response_too_large"),
            HttpRequestExecutor().run(request(captureAs = "v")).result,
        )
    }

    // --- Secrets -----------------------------------------------------------------------------------

    @Test
    fun `a secret header and query parameter are read at fire time and sent to the approved host`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = HttpRequestExecutor(secrets = vault).execute(
            request(path = "/api?x=1#frag", secretHeaders = listOf(bearer), secretQuery = listOf(SecretRef("key", "q_key"))),
        )
        assertEquals(ActionResult.Success, result)
        val received = server.takeRequest()
        assertEquals("Bearer $token", received.getHeader("Authorization"))
        assertEquals("/api?x=1&key=a%20b%26c%3Dd", received.path)
        assertEquals(listOf("ha_token", "q_key"), reads)
    }

    @Test
    fun `a missing secret fails closed before any connection`() = runTest {
        val result = HttpRequestExecutor(secrets = vault).execute(request(secretHeaders = listOf(bearer.copy(secret = "gone"))))
        assertEquals(ActionResult.Failure("http_secret_missing"), result)
        val noVault = HttpRequestExecutor().execute(request(secretHeaders = listOf(bearer)))
        assertEquals(ActionResult.Failure("http_secret_missing"), noVault)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a secret that cannot go in a header fails without echoing it`() = runTest {
        val result = HttpRequestExecutor(secrets = vault).execute(request(secretHeaders = listOf(bearer.copy(secret = "accented"))))
        assertEquals(ActionResult.Failure("http_secret_invalid"), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a secret is re-sent only on a same origin redirect`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next"))
        server.enqueue(MockResponse().setResponseCode(200))
        val ok = HttpRequestExecutor(secrets = vault).execute(
            request(secretHeaders = listOf(bearer), secretQuery = listOf(SecretRef("key", "q_key"))),
        )
        assertEquals(ActionResult.Success, ok)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue("key=" in first.path.orEmpty())
        assertEquals("/next", second.path)
        assertEquals("Bearer $token", second.getHeader("Authorization"))
    }

    @Test
    fun `a redirect to another host or port never carries the secret`() = runTest {
        // Same host, different port: another origin.
        server.enqueue(
            MockResponse().setResponseCode(307).addHeader("Location", "http://127.0.0.1:${other.port}/leak"),
        )
        other.enqueue(MockResponse().setResponseCode(200))
        val samePortOnly = HttpRequestExecutor(secrets = vault).execute(request(secretHeaders = listOf(bearer)))
        assertEquals(ActionResult.Failure("http_redirect_blocked"), samePortOnly)
        assertEquals(0, other.requestCount)

        // Another host.
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost:${server.port}/leak"))
        val otherHost = HttpRequestExecutor(secrets = vault).execute(request(secretHeaders = listOf(bearer)))
        assertEquals(ActionResult.Failure("http_redirect_blocked"), otherHost)
        assertEquals(2, server.requestCount)
        repeat(2) { assertFalse(server.takeRequest().path.orEmpty().contains("leak")) }
    }

    @Test
    fun `with a secret the approved url and the sent url must name the same origin`() = runTest {
        assertTrue(HttpRequestExecutor.parsersAgree("$base/api"))
        assertTrue(HttpRequestExecutor.parsersAgree("https://Example.ORG/x?y=1"))
        assertTrue(HttpRequestExecutor.parsersAgree("https://example.org:443/x"))
        // OkHttp rewrites this loopback address as ::1: the text that was approved is not what it would
        // connect to, so the vault is not read and nothing leaves.
        val rewritten = "http://[0:0:0:0:0:0:0:1]:${server.port}/api"
        assertFalse(HttpRequestExecutor.parsersAgree(rewritten))
        assertEquals(
            ActionResult.Failure("http_request_invalid"),
            HttpRequestExecutor(secrets = vault).execute(request(url = rewritten, secretHeaders = listOf(bearer))),
        )
        assertTrue(reads.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `nothing the executor returns or prints carries the secret value`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val failed = HttpRequestExecutor(secrets = vault).run(
            request(captureAs = "v", secretHeaders = listOf(bearer), secretQuery = listOf(SecretRef("key", "q_key"))),
        )
        assertEquals(ActionResult.Failure("http_status_500"), failed.result)
        listOf(token, "a b&c=d", "a%20b").forEach { assertFalse(it in failed.toString(), it) }
        listOf(
            "http://127.0.0.1:1/closed",
            "http://192.168.1.20/lan",
        ).forEach { url ->
            val outcome = HttpRequestExecutor(secrets = vault, timeoutMs = 2_000).run(request(url = url, secretHeaders = listOf(bearer)))
            assertFalse(token in outcome.toString(), url)
            assertFalse(token in outcome.result.toString(), url)
        }
    }

    // --- Plain http on the local network ----------------------------------------------------------

    @Test
    fun `a secret over plain http to the local network is refused before connecting`() = runTest {
        listOf("http://192.168.1.20:8123/api", "http://homeassistant.local/api").forEach { url ->
            val headerResult = HttpRequestExecutor(secrets = vault).execute(request(url = url, secretHeaders = listOf(bearer)))
            assertEquals(ActionResult.Failure("http_cleartext_secret"), headerResult, url)
            val queryResult = HttpRequestExecutor(secrets = vault).execute(request(url = url, secretQuery = listOf(SecretRef("k", "q_key"))))
            assertEquals(ActionResult.Failure("http_cleartext_secret"), queryResult, url)
        }
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `the client guard lets http through to this phone and the user's network only`() {
        val executor = HttpRequestExecutor()
        listOf("127.0.0.1", "localhost", "192.168.1.20", "10.0.0.5", "homeassistant.local", "fd00::1").forEach {
            assertTrue(executor.permitsCleartext(it), it)
        }
        listOf("example.org", "8.8.8.8", "192.168.1.20.nip.io").forEach {
            assertFalse(executor.permitsCleartext(it), it)
        }
    }
}
