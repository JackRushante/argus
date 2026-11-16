package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 0.8 [FeatureGate.HTTP_CAPTURE]: the response (or the part a JSON pointer points to) becomes text. */
class HttpCapture08Test {
    private fun captured(body: String, pointer: String? = null): String =
        assertIs<HttpCapturePolicy.Outcome.Captured>(HttpCapturePolicy.capture(body, pointer)).text

    private fun failed(body: String, pointer: String? = null): String =
        assertIs<HttpCapturePolicy.Outcome.Failed>(HttpCapturePolicy.capture(body, pointer)).code

    private val weather = """{"current":{"temp":21.5,"rain":false,"label":"Sereno","tags":["a","b"],"none":null},
        "a/b":{"m~n":"escaped"},"list":[{"id":7},{"id":8}]}"""

    @Test
    fun `without a pointer the whole body is the value, trimmed`() {
        assertEquals("ok", captured("  ok\n"))
        assertEquals("riga 1\nriga 2\tcolonna", captured("riga 1\r\nriga 2\tcolonna\r\n"))
        assertEquals("", captured("   "))
    }

    @Test
    fun `a pointer to a string number or boolean renders it as text`() {
        assertEquals("Sereno", captured(weather, "/current/label"))
        assertEquals("21.5", captured(weather, "/current/temp"))
        assertEquals("false", captured(weather, "/current/rain"))
        assertEquals("8", captured(weather, "/list/1/id"))
        assertEquals("b", captured(weather, "/current/tags/1"))
        // RFC 6901 escapes: ~1 is "/", ~0 is "~".
        assertEquals("escaped", captured(weather, "/a~1b/m~0n"))
    }

    @Test
    fun `an object an array null or a missing path fail with a closed code`() {
        assertEquals(HttpCapturePolicy.NOT_SCALAR, failed(weather, "/current"))
        assertEquals(HttpCapturePolicy.NOT_SCALAR, failed(weather, "/current/tags"))
        assertEquals(HttpCapturePolicy.NOT_SCALAR, failed(weather, "/current/none"))
        assertEquals(HttpCapturePolicy.PATH_MISSING, failed(weather, "/current/humidity"))
        assertEquals(HttpCapturePolicy.PATH_MISSING, failed(weather, "/list/2/id"))
        assertEquals(HttpCapturePolicy.PATH_MISSING, failed(weather, "/list/01/id"))
        assertEquals(HttpCapturePolicy.PATH_MISSING, failed(weather, "/list/-/id"))
        assertEquals(HttpCapturePolicy.PATH_MISSING, failed(weather, "/current/label/x"))
    }

    @Test
    fun `a pointer on a body that is not json fails`() {
        assertEquals(HttpCapturePolicy.NOT_JSON, failed("<html>ok</html>", "/a"))
        assertEquals(HttpCapturePolicy.NOT_JSON, failed("{\"a\":", "/a"))
        assertEquals(HttpCapturePolicy.NOT_JSON, failed("[".repeat(10_000), "/0"))
    }

    @Test
    fun `a value longer than 4096 characters fails instead of being cut`() {
        assertEquals(4_096, captured("a".repeat(4_096)).length)
        assertEquals(4_096, captured("  " + "a".repeat(4_096) + "\n").length)
        assertEquals(HttpCapturePolicy.TOO_LONG, failed("a".repeat(4_097)))
        assertEquals(HttpCapturePolicy.TOO_LONG, failed("{\"t\":\"${"b".repeat(4_097)}\"}", "/t"))
    }

    @Test
    fun `control characters other than newline and tab fail`() {
        assertEquals(HttpCapturePolicy.INVALID_CHARS, failed("a\u0000b"))
        assertEquals(HttpCapturePolicy.INVALID_CHARS, failed("a\u001bb"))
        assertEquals(HttpCapturePolicy.INVALID_CHARS, failed("a\rb"))
        assertEquals(HttpCapturePolicy.INVALID_CHARS, failed("{\"t\":\"a\\u0007b\"}", "/t"))
    }

    @Test
    fun `a pointer is literal and well formed`() {
        listOf("/a", "/a/0", "/a~0b", "/a~1b", "/", "/" + "x".repeat(255)).forEach {
            assertTrue(HttpCapturePolicy.isPointerValid(it), it)
        }
        listOf("", "a/b", "/a~2", "/a~", "/\${v}", "/a\nb", "/" + "x".repeat(256)).forEach {
            assertFalse(HttpCapturePolicy.isPointerValid(it), it)
        }
    }

    @Test
    fun `a captured value never prints itself`() {
        val outcome = HttpCapturePolicy.capture("token-123", null)
        assertFalse("token-123" in outcome.toString())
    }
}
