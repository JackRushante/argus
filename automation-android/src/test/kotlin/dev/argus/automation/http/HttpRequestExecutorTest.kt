package dev.argus.automation.http

import dev.argus.engine.model.Action
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/**
 * Esecutore HTTP contro un server finto sul loopback (l'unico `http://` ammesso): metodi, esiti, redirect
 * solo verso lo stesso host, limiti, timeout e regole di forma ripetute prima di aprire la connessione.
 */
class HttpRequestExecutorTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private val base: String get() = "http://127.0.0.1:${server.port}"

    private fun request(
        path: String = "/hook",
        method: HttpMethod = HttpMethod.POST,
        headers: Map<String, String>? = null,
        body: String? = null,
        contentType: String? = null,
    ) = Action.HttpRequest(method, base + path, headers, body, contentType)

    @Test
    fun `a post sends method path headers content type and body and succeeds on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val result = HttpRequestExecutor().execute(
            request(
                headers = mapOf("Authorization" to "Bearer abcdef", "X-Trace" to "t1"),
                body = "{\"text\":\"ciao\"}",
                contentType = "application/json",
            ),
        )

        assertEquals(ActionResult.Success, result)
        val received = server.takeRequest()
        assertEquals("POST", received.method)
        assertEquals("/hook", received.path)
        assertEquals("Bearer abcdef", received.getHeader("Authorization"))
        assertEquals("t1", received.getHeader("X-Trace"))
        assertEquals("application/json", received.getHeader("Content-Type"))
        assertEquals("{\"text\":\"ciao\"}", received.body.readUtf8())
    }

    @Test
    fun `a get has no body and 204 is a success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = HttpRequestExecutor().execute(request(method = HttpMethod.GET, path = "/ping?x=1"))

        assertEquals(ActionResult.Success, result)
        val received = server.takeRequest()
        assertEquals("GET", received.method)
        assertEquals("/ping?x=1", received.path)
        assertEquals(0L, received.bodySize)
    }

    @Test
    fun `put and delete use their own method and a delete may carry a body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(ActionResult.Success, HttpRequestExecutor().execute(request(method = HttpMethod.PUT, body = "a")))
        assertEquals(ActionResult.Success, HttpRequestExecutor().execute(request(method = HttpMethod.DELETE)))
        assertEquals(
            ActionResult.Success,
            HttpRequestExecutor().execute(request(method = HttpMethod.DELETE, body = "b")),
        )

        val put = server.takeRequest()
        val bareDelete = server.takeRequest()
        val bodyDelete = server.takeRequest()
        assertEquals("PUT" to "a", put.method to put.body.readUtf8())
        assertEquals("DELETE" to 0L, bareDelete.method to bareDelete.bodySize)
        assertEquals("DELETE" to "b", bodyDelete.method to bodyDelete.body.readUtf8())
    }

    @Test
    fun `a post without a body sends an empty one`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(ActionResult.Success, HttpRequestExecutor().execute(request()))

        val received = server.takeRequest()
        assertEquals("POST", received.method)
        assertEquals(0L, received.bodySize)
    }

    @Test
    fun `the content type comes from the header when the field is absent and is sent once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        HttpRequestExecutor().execute(
            request(body = "{}", headers = mapOf("content-type" to "application/json")),
        )

        val received = server.takeRequest()
        assertEquals(listOf("application/json"), received.headers.values("Content-Type"))
    }

    @Test
    fun `a body without any content type goes out as plain utf8 text`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        HttpRequestExecutor().execute(request(body = "ciao"))

        assertEquals("text/plain; charset=utf-8", server.takeRequest().getHeader("Content-Type"))
    }

    // --- Esiti ---------------------------------------------------------------------------------

    @Test
    fun `only 2xx is a success and the journal gets the status code and nothing else`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("secret body text"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("stack trace"))
        server.enqueue(MockResponse().setResponseCode(300).setBody("choices"))

        val results = List(3) { HttpRequestExecutor().execute(request(method = HttpMethod.GET)) }

        assertEquals(
            listOf(
                ActionResult.Failure("http_status_404"),
                ActionResult.Failure("http_status_500"),
                ActionResult.Failure("http_status_300"),
            ),
            results,
        )
        results.forEach { assertFalse("secret body text" in it.toString() || "stack trace" in it.toString()) }
    }

    @Test
    fun `a large response is read in part and does not fail the call`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(300_000)))

        assertEquals(ActionResult.Success, HttpRequestExecutor().execute(request(method = HttpMethod.GET)))
    }

    @Test
    fun `an unreachable server is a network error`() = runTest {
        val url = request(method = HttpMethod.GET)
        server.shutdown()

        assertEquals(ActionResult.Failure("http_network_error"), HttpRequestExecutor().execute(url))
    }

    @Test
    fun `a server that never answers ends with a timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = HttpRequestExecutor(timeoutMs = 400).execute(request(method = HttpMethod.GET))

        assertEquals(ActionResult.Failure("http_timeout"), result)
    }

    @Test
    fun `cancelling the coroutine cancels the call`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()

        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            HttpRequestExecutor().execute(request(method = HttpMethod.GET))
        }
        withContext(Dispatchers.Default) { delay(200) }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue((System.nanoTime() - started) < 10_000_000_000L, "la chiamata non è stata interrotta")
    }

    // --- Redirect ------------------------------------------------------------------------------

    @Test
    fun `a redirect to the same host is followed and a post becomes a get without body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next"))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = HttpRequestExecutor().execute(request(body = "dati", headers = mapOf("X-Trace" to "t")))

        assertEquals(ActionResult.Success, result)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("POST" to "/hook", first.method to first.path)
        assertEquals("GET" to "/next", second.method to second.path)
        assertEquals(0L, second.bodySize)
        assertEquals("t", second.getHeader("X-Trace"))
    }

    @Test
    fun `307 and 308 repeat the same method and body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/again"))
        server.enqueue(MockResponse().setResponseCode(308).addHeader("Location", "/third"))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = HttpRequestExecutor().execute(request(method = HttpMethod.PUT, body = "dati"))

        assertEquals(ActionResult.Success, result)
        repeat(3) {
            val received = server.takeRequest()
            assertEquals("PUT", received.method)
            assertEquals("dati", received.body.readUtf8())
        }
    }

    @Test
    fun `a redirect to a different host is blocked before any connection is made`() = runTest {
        // «localhost» e «127.0.0.1» sono host diversi: nemmeno l'alias del loopback è ammesso.
        server.enqueue(
            MockResponse().setResponseCode(302).addHeader("Location", "http://localhost:${server.port}/leak"),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val result = HttpRequestExecutor().execute(request(headers = mapOf("Authorization" to "Bearer abcdef")))

        assertEquals(ActionResult.Failure("http_redirect_blocked"), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a redirect to a non web scheme is blocked`() = runTest {
        listOf("file:///sdcard/secret", "javascript:alert(1)", "ftp://127.0.0.1/x").forEach { target ->
            server.enqueue(MockResponse().setResponseCode(301).addHeader("Location", target))
            assertEquals(
                ActionResult.Failure("http_redirect_blocked"),
                HttpRequestExecutor().execute(request(method = HttpMethod.GET)),
                target,
            )
        }
    }

    @Test
    fun `a redirect without a location is reported with its status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302))

        assertEquals(
            ActionResult.Failure("http_status_302"),
            HttpRequestExecutor().execute(request(method = HttpMethod.GET)),
        )
    }

    @Test
    fun `an endless redirect chain stops after five hops`() = runTest {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/loop")) }

        val result = HttpRequestExecutor().execute(request(method = HttpMethod.GET))

        assertEquals(ActionResult.Failure("http_too_many_redirects"), result)
        assertEquals(6, server.requestCount)
    }

    // --- Regole di forma, prima di ogni connessione ----------------------------------------------

    @Test
    fun `clear text http is refused for any host but the loopback`() = runTest {
        listOf(
            "http://example.org/hook",
            "http://192.168.1.10/hook",
            "http://localhost.evil.example/hook",
            "ftp://example.org/hook",
        ).forEach { url ->
            assertEquals(
                ActionResult.Failure("http_request_invalid"),
                HttpRequestExecutor().execute(Action.HttpRequest(HttpMethod.GET, url)),
                url,
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an url with userinfo or a placeholder is refused`() = runTest {
        listOf(
            "https://user:pass@example.org/hook",
            "https://example.org/\${sms}",
            "https://example.org/a b",
        ).forEach { url ->
            assertEquals(
                ActionResult.Failure("http_request_invalid"),
                HttpRequestExecutor().execute(Action.HttpRequest(HttpMethod.GET, url)),
                url,
            )
        }
    }

    @Test
    fun `forbidden or malformed headers and an oversized body are refused`() = runTest {
        val exec = HttpRequestExecutor()
        listOf(
            request(headers = mapOf("Host" to "evil.example")),
            request(headers = mapOf("Content-Length" to "5")),
            request(headers = mapOf("Transfer-Encoding" to "chunked")),
            request(headers = mapOf("Connection" to "close")),
            request(headers = mapOf("X-A" to "a\r\nX-B: b")),
            request(headers = mapOf("X-A" to "\${secret}")),
            request(headers = (1..11).associate { "X-$it" to "v" }),
            request(body = "x".repeat(16 * 1024 + 1)),
            request(method = HttpMethod.GET, body = "x"),
        ).forEach { action ->
            assertEquals(ActionResult.Failure("http_request_invalid"), exec.execute(action), action.toString())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a body of exactly 16 KB is accepted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(ActionResult.Success, HttpRequestExecutor().execute(request(body = "x".repeat(16 * 1024))))
        assertEquals(16L * 1024, server.takeRequest().bodySize)
    }
}
