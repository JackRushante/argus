package dev.argus.e2e

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class E2eWireTest {
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `an empty or missing argument is an empty request`() {
        assertEquals(JsonObject(emptyMap()), E2eWire.decodeRequest(null))
        assertEquals(JsonObject(emptyMap()), E2eWire.decodeRequest("   "))
    }

    @Test
    fun `the argument travels as base64 and quotes survive`() {
        val name = "it's \"quoted\" `x` \$HOME"
        val json = JsonObject(mapOf("name" to JsonPrimitive(name))).toString()
        val decoded = E2eWire.decodeRequest(b64(json))
        assertEquals(name, (decoded["name"] as JsonPrimitive).content)
    }

    @Test
    fun `base64 only uses characters that need no shell quoting inside single quotes`() {
        val encoded = b64("echo 'a b' | tr -d \"\\\" ; \$(x) `y` & > /dev/null")
        assertTrue(Regex("^[A-Za-z0-9+/=]+$").matches(encoded))
    }

    @Test
    fun `raw json is accepted for hand-made calls`() {
        val decoded = E2eWire.decodeRequest("{\"id\":\"abc\"}")
        assertEquals("abc", (decoded["id"] as JsonPrimitive).content)
    }

    @Test
    fun `a non object payload is refused`() {
        assertFailsWith<IllegalArgumentException> { E2eWire.decodeRequest(b64("[1,2]")) }
    }

    @Test
    fun `results round trip through the single result key`() {
        val result = JsonObject(mapOf("ok" to JsonPrimitive(true), "id" to JsonPrimitive("x")))
        assertEquals(result, E2eWire.decodeResult(E2eWire.encodeResult(result)))
        assertEquals("r", E2eWire.RESULT_KEY)
    }
}
