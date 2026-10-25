package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderErrorMessageTest {
    private val key = "sk-test-secret-key-do-not-leak-0123456789"

    @Test fun `reads error message from an OpenAI style body`() {
        val body = """{"error":{"message":"The model `gpt-9` does not exist","type":"invalid_request_error"}}"""
        assertEquals("The model `gpt-9` does not exist", ProviderErrorMessage.extract(body, listOf(key)))
    }

    @Test fun `falls back to a top level message`() {
        assertEquals("quota esaurita", ProviderErrorMessage.extract("""{"message":"quota esaurita"}""", emptyList()))
    }

    @Test fun `reads a plain string error as sent by local servers`() {
        assertEquals(
            "model 'x' not found",
            ProviderErrorMessage.extract("""{"error":"model 'x' not found"}""", emptyList()),
        )
    }

    @Test fun `reads the first element of a gemini style array body`() {
        val body = """[{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}]"""
        assertEquals("API key not valid.", ProviderErrorMessage.extract(body, emptyList()))
    }

    @Test fun `bodies without a usable message give null`() {
        assertNull(ProviderErrorMessage.extract(null, emptyList()))
        assertNull(ProviderErrorMessage.extract("", emptyList()))
        assertNull(ProviderErrorMessage.extract("<html>Bad gateway</html>", emptyList()))
        assertNull(ProviderErrorMessage.extract("""{"error":{"code":400}}""", emptyList()))
        assertNull(ProviderErrorMessage.extract("""{"error":{"message":null}}""", emptyList()))
        assertNull(ProviderErrorMessage.extract("""{"error":{"message":"   "}}""", emptyList()))
    }

    @Test fun `masks the configured key and any sk or bearer token`() {
        val body = """{"error":{"message":"Incorrect API key provided: $key. Also sk-proj-ABCdef123_456 and Bearer abc.def-123 were seen"}}"""
        val message = ProviderErrorMessage.extract(body, listOf(key))!!
        assertFalse(key in message)
        assertFalse("sk-" in message, message)
        assertFalse("abc.def-123" in message, message)
        assertTrue("***" in message)
    }

    @Test fun `masks a key that is not shaped like sk`() {
        val custom = "local-token-12345"
        val message = ProviderErrorMessage.extract("""{"error":{"message":"bad token local-token-12345"}}""", listOf(custom))!!
        assertEquals("bad token ***", message)
    }

    @Test fun `masks a google key`() {
        val message = ProviderErrorMessage.extract(
            """{"error":{"message":"key AIzaSyA1234567890abcdefghijklmnop expired"}}""",
            emptyList(),
        )!!
        assertFalse("AIza" in message, message)
    }

    @Test fun `strips control characters and collapses whitespace`() {
        val message = ProviderErrorMessage.extract(
            """{"error":{"message":"line one\nline\ttwo\u0000\u001b[31m end"}}""",
            emptyList(),
        )!!
        assertTrue(message.none { it.isISOControl() }, message)
        assertEquals("line one line two [31m end", message)
    }

    @Test fun `caps the message at 160 characters`() {
        val long = "x".repeat(400)
        val message = ProviderErrorMessage.extract("""{"error":{"message":"$long"}}""", emptyList())!!
        assertEquals(ProviderErrorMessage.MAX_CHARS, message.length)
        assertTrue(message.endsWith("…"))
    }

    @Test fun `a key straddling the cap is masked before cutting`() {
        val prefix = "y".repeat(150)
        val message = ProviderErrorMessage.extract(
            """{"error":{"message":"$prefix $key tail"}}""",
            listOf(key),
        )!!
        assertTrue(message.length <= ProviderErrorMessage.MAX_CHARS)
        assertFalse("sk-" in message, message)
        assertFalse("test-secret" in message, message)
    }

    @Test fun `never cuts a surrogate pair in half`() {
        val emoji = "😀"
        // L'emoji occupa gli indici 158-159: il taglio a 159 caratteri cadrebbe tra le due metà.
        val text = "a".repeat(158) + emoji + "zzz"
        val message = ProviderErrorMessage.sanitize(text, emptyList())!!
        assertEquals("a".repeat(158) + "…", message)
    }
}
