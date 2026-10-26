package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpRequestPolicyTest {
    private fun request(
        url: String = "https://example.org/hook",
        method: HttpMethod = HttpMethod.POST,
        headers: Map<String, String>? = null,
        body: String? = null,
        contentType: String? = null,
    ) = Action.HttpRequest(method, url, headers, body, contentType)

    // --- URL ----------------------------------------------------------------------------------

    @Test
    fun `https is required and http is allowed only for an exact loopback host`() {
        assertTrue(HttpRequestPolicy.isUrlAllowed("https://example.org/path?q=1"))
        assertTrue(HttpRequestPolicy.isUrlAllowed("https://example.org:8443/x"))
        assertTrue(HttpRequestPolicy.isUrlAllowed("HTTPS://Example.ORG/"))
        assertTrue(HttpRequestPolicy.isUrlAllowed("http://localhost:8123/api"))
        assertTrue(HttpRequestPolicy.isUrlAllowed("http://127.0.0.1/api"))
        assertTrue(HttpRequestPolicy.isUrlAllowed("http://[::1]:3000/api"))

        assertFalse(HttpRequestPolicy.isUrlAllowed("http://example.org/hook"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("http://192.168.1.10/hook"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("http://localhost.evil.example/hook"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("http://127.0.0.1.nip.io/hook"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("ftp://example.org/file"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("file:///sdcard/x"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("content://contacts"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("//example.org/x"))
        assertFalse(HttpRequestPolicy.isUrlAllowed(""))
    }

    @Test
    fun `an url with userinfo whitespace control characters or a bad port is rejected`() {
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://user:pass@example.org/"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://user@example.org/"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://example.org/a b"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://example.org/a\nb"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://example.org:0/"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://example.org:70000/"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://example.org/" + "a".repeat(2_100)))
    }

    @Test
    fun `an url with a placeholder is not an url the policy accepts`() {
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://example.org/\${id}"))
        assertFalse(HttpRequestPolicy.isUrlAllowed("https://\${host}/x"))
    }

    @Test
    fun `host comparison for redirects ignores case and ipv6 brackets`() {
        assertTrue(HttpRequestPolicy.sameHost("https://Example.org/a", "https://example.ORG/b"))
        assertTrue(HttpRequestPolicy.sameHost("http://[::1]:1/a", "http://[::1]:2/b"))
        assertFalse(HttpRequestPolicy.sameHost("https://example.org/a", "https://evil.example/a"))
        assertFalse(HttpRequestPolicy.sameHost("https://example.org/a", "https://api.example.org/a"))
        assertFalse(HttpRequestPolicy.sameHost("https://example.org/a", "not a url"))
    }

    // --- Intestazioni e content type ----------------------------------------------------------

    @Test
    fun `header names are rfc tokens and the four transport headers are forbidden`() {
        assertTrue(HttpRequestPolicy.isHeaderNameValid("Authorization"))
        assertTrue(HttpRequestPolicy.isHeaderNameValid("X-Api-Key"))
        assertTrue(HttpRequestPolicy.isHeaderNameValid("x_custom.1"))
        assertFalse(HttpRequestPolicy.isHeaderNameValid(""))
        assertFalse(HttpRequestPolicy.isHeaderNameValid("Bad Name"))
        assertFalse(HttpRequestPolicy.isHeaderNameValid("Bad:Name"))
        assertFalse(HttpRequestPolicy.isHeaderNameValid("X\r\nInjected"))
        assertFalse(HttpRequestPolicy.isHeaderNameValid("n".repeat(65)))

        listOf("Host", "host", "Content-Length", "TRANSFER-ENCODING", "Connection").forEach {
            assertTrue(HttpRequestPolicy.isHeaderNameForbidden(it), it)
        }
        assertFalse(HttpRequestPolicy.isHeaderNameForbidden("Accept"))
    }

    @Test
    fun `header values cannot be blank or carry line breaks`() {
        assertTrue(HttpRequestPolicy.isHeaderValueValid("Bearer abc.def"))
        assertTrue(HttpRequestPolicy.isHeaderValueValid("a\tb"))
        assertFalse(HttpRequestPolicy.isHeaderValueValid(""))
        assertFalse(HttpRequestPolicy.isHeaderValueValid("   "))
        assertFalse(HttpRequestPolicy.isHeaderValueValid("ok\r\nX-Injected: 1"))
        assertFalse(HttpRequestPolicy.isHeaderValueValid("ok\nbad"))
        assertFalse(HttpRequestPolicy.isHeaderValueValid("v".repeat(2_100)))
    }

    @Test
    fun `content type must look like type slash subtype with optional parameters`() {
        assertTrue(HttpRequestPolicy.isContentTypeValid("application/json"))
        assertTrue(HttpRequestPolicy.isContentTypeValid("application/json; charset=utf-8"))
        assertTrue(HttpRequestPolicy.isContentTypeValid("application/vnd.api+json"))
        assertFalse(HttpRequestPolicy.isContentTypeValid("json"))
        assertFalse(HttpRequestPolicy.isContentTypeValid("a/b/c"))
        assertFalse(HttpRequestPolicy.isContentTypeValid("application/json\r\nX: 1"))
        assertFalse(HttpRequestPolicy.isContentTypeValid(""))
    }

    @Test
    fun `the effective content type comes from the field or from the header and the encoding follows it`() {
        val json = request(contentType = "application/json")
        assertEquals("application/json", HttpRequestPolicy.effectiveContentType(json))
        assertEquals(InterpolationPolicy.ValueEncoding.JSON_STRING, HttpRequestPolicy.bodyEncoding(json))

        val viaHeader = request(headers = mapOf("content-type" to "Application/JSON; charset=utf-8"))
        assertEquals(InterpolationPolicy.ValueEncoding.JSON_STRING, HttpRequestPolicy.bodyEncoding(viaHeader))

        val vendor = request(contentType = "application/vnd.api+json")
        assertEquals(InterpolationPolicy.ValueEncoding.JSON_STRING, HttpRequestPolicy.bodyEncoding(vendor))

        val form = request(contentType = "application/x-www-form-urlencoded")
        assertEquals(InterpolationPolicy.ValueEncoding.FORM, HttpRequestPolicy.bodyEncoding(form))

        assertEquals(InterpolationPolicy.ValueEncoding.RAW, HttpRequestPolicy.bodyEncoding(request()))
        assertEquals(
            InterpolationPolicy.ValueEncoding.RAW,
            HttpRequestPolicy.bodyEncoding(request(contentType = "text/plain")),
        )

        assertTrue(
            HttpRequestPolicy.contentTypeConflicts(
                request(contentType = "application/json", headers = mapOf("Content-Type" to "text/plain")),
            ),
        )
        assertFalse(
            HttpRequestPolicy.contentTypeConflicts(
                request(contentType = "application/json", headers = mapOf("Content-Type" to "Application/JSON")),
            ),
        )
    }

    // --- Body: JSON e form --------------------------------------------------------------------

    @Test
    fun `json string content escapes quotes backslashes and control characters`() {
        assertEquals("plain", HttpRequestPolicy.jsonStringContent("plain"))
        assertEquals("a\\\"b", HttpRequestPolicy.jsonStringContent("a\"b"))
        assertEquals("a\\\\b", HttpRequestPolicy.jsonStringContent("a\\b"))
        assertEquals("l1\\nl2\\r\\t", HttpRequestPolicy.jsonStringContent("l1\nl2\r\t"))
        assertEquals("\\u0001\\u001f", HttpRequestPolicy.jsonStringContent("\u0001\u001f"))
        assertEquals("caffè ☕", HttpRequestPolicy.jsonStringContent("caffè ☕"))
    }

    @Test
    fun `an escaped value cannot break out of a json string`() {
        val attack = "x\", \"admin\": true, \"y\": \""
        val body = "{\"text\":\"${HttpRequestPolicy.jsonStringContent(attack)}\"}"
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body)
            as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("text"), parsed.keys)
    }

    @Test
    fun `form encoding percent encodes separators`() {
        assertEquals("a%26b%3Dc+d", HttpRequestPolicy.formEncode("a&b=c d"))
    }

    @Test
    fun `placeholders are located inside or outside json strings`() {
        fun contexts(template: String): List<Boolean> {
            val ranges = Regex("""\$\{[a-z_]+\}""").findAll(template).map { it.range }.toList()
            return HttpRequestPolicy.jsonStringContexts(template, ranges)
        }
        assertEquals(listOf(true), contexts("{\"a\":\"\${x}\"}"))
        assertEquals(listOf(false), contexts("{\"a\":\${x}}"))
        assertEquals(listOf(true, false), contexts("{\"a\":\"pre \${x} post\",\"n\":\${y}}"))
        // Una virgoletta escapata non chiude la stringa.
        assertEquals(listOf(true), contexts("{\"a\":\"he said \\\"\${x}\\\" ok\"}"))
        assertEquals(listOf(false), contexts("\${x}"))
    }

    @Test
    fun `body size is measured in utf8 bytes`() {
        assertEquals(4, HttpRequestPolicy.bodyBytes("abcd"))
        assertEquals(5, HttpRequestPolicy.bodyBytes("è€"))
    }

    // --- Mascheratura -------------------------------------------------------------------------

    @Test
    fun `sensitive headers are detected from the name only`() {
        listOf("Authorization", "Proxy-Authorization", "X-Auth-Token", "X-Api-Key", "apikey", "Client-Secret")
            .forEach { assertTrue(HttpRequestPolicy.isSensitiveHeader(it), it) }
        listOf("Accept", "Content-Type", "User-Agent", "X-Trace").forEach {
            assertFalse(HttpRequestPolicy.isSensitiveHeader(it), it)
        }
    }

    @Test
    fun `a sensitive value shows the scheme and the last four characters and a short secret nothing`() {
        assertEquals(
            "Bearer ••••wxyz",
            HttpRequestPolicy.displayValue("Authorization", "Bearer abcdefghijklmnopwxyz"),
        )
        assertEquals("••••6789", HttpRequestPolicy.displayValue("X-Api-Key", "k-123456789"))
        // Un segreto corto non rivela nemmeno la coda.
        assertEquals("••••", HttpRequestPolicy.displayValue("X-Api-Key", "short"))
        assertEquals("Basic ••••", HttpRequestPolicy.displayValue("Authorization", "Basic abc"))
        assertEquals("application/json", HttpRequestPolicy.displayValue("Content-Type", "application/json"))
        // Il segreto intero non compare mai.
        val shown = HttpRequestPolicy.displayValue("Authorization", "Bearer abcdefghijklmnopwxyz")
        assertFalse("abcdefghijklmnop" in shown)
    }

    // --- Forma dopo la risoluzione ------------------------------------------------------------

    @Test
    fun `the resolved shape check repeats the validator rules`() {
        assertTrue(HttpRequestPolicy.isResolvedShapeValid(request(body = "{}", contentType = "application/json")))
        assertTrue(HttpRequestPolicy.isResolvedShapeValid(request(method = HttpMethod.GET)))

        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(url = "http://example.org/")))
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(url = "https://example.org/\${x}")))
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(method = HttpMethod.GET, body = "x")))
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(body = "x".repeat(16 * 1024 + 1))))
        assertTrue(HttpRequestPolicy.isResolvedShapeValid(request(body = "x".repeat(16 * 1024))))
        assertFalse(
            HttpRequestPolicy.isResolvedShapeValid(request(headers = mapOf("Host" to "evil.example"))),
        )
        assertFalse(
            HttpRequestPolicy.isResolvedShapeValid(request(headers = mapOf("X-A" to "\${secret}"))),
        )
        assertFalse(
            HttpRequestPolicy.isResolvedShapeValid(
                request(headers = (1..11).associate { "X-H$it" to "v" }),
            ),
        )
        assertTrue(
            HttpRequestPolicy.isResolvedShapeValid(
                request(headers = (1..10).associate { "X-H$it" to "v" }),
            ),
        )
        assertFalse(
            HttpRequestPolicy.isResolvedShapeValid(
                request(headers = mapOf("Accept" to "a", "accept" to "b")),
            ),
        )
    }
}
