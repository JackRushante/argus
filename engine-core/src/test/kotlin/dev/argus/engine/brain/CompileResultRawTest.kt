package dev.argus.engine.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompileResultRawTest {
    @Test fun `raw defaults to null so existing results are unchanged`() {
        val result = CompileResult("r", null, null)
        assertNull(result.raw)
        assertEquals(CompileResult("r", null, null), result)
    }

    @Test fun `capRaw keeps short text and drops blank text`() {
        assertEquals("risposta", CompileResult.capRaw("risposta"))
        assertNull(CompileResult.capRaw(null))
        assertNull(CompileResult.capRaw(""))
        assertNull(CompileResult.capRaw("   \n"))
    }

    @Test fun `capRaw truncates to 16000 characters`() {
        val capped = CompileResult.capRaw("x".repeat(40_000))!!
        assertEquals(16_000, capped.length)
        assertEquals(16_000, CompileResult.MAX_RAW_CHARS)
        assertEquals("y".repeat(16_000), CompileResult.capRaw("y".repeat(16_000)))
    }

    @Test fun `capRaw never splits a surrogate pair`() {
        // L'emoji occupa gli indici 15 999-16 000: tagliare a 16 000 la spezzerebbe.
        val text = "a".repeat(15_999) + "😀" + "tail"
        val capped = CompileResult.capRaw(text)!!
        assertEquals("a".repeat(15_999), capped)
    }

    @Test fun `the provider message defaults to null and never reaches toString`() {
        assertNull(CompileResult("r", null, null).providerMessage)
        val result = CompileResult("", null, "bridge_http_400", providerMessage = "x is not a valid model ID")
        assertEquals("x is not a valid model ID", result.providerMessage)
        val text = result.toString()
        assertFalse("valid model" in text, text)
        assertTrue("providerMessage=<" in text, text)
    }

    @Test fun `toString never prints the raw text`() {
        val result = CompileResult("ok", null, null, raw = "dato personale: Mario Rossi 333 1234567")
        val text = result.toString()
        assertFalse("Mario" in text, text)
        assertFalse("333" in text, text)
        assertTrue("raw=<" in text, text)
    }
}
