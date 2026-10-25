package dev.argus.automation.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawResponseReportTest {
    @Test
    fun `the report has the header and the raw text in a fenced block`() {
        val report = rawResponseReport(
            appVersion = "0.3.6",
            installSource = "APK",
            provider = "openrouter",
            model = "deepseek/deepseek-v4.1-flash",
            errorCode = "meta_parse_error",
            raw = "testo del modello\n",
        )

        assertEquals(
            "App: Argus 0.3.6 (APK)\n" +
                "Provider: openrouter\n" +
                "Model: deepseek/deepseek-v4.1-flash\n" +
                "Error code: meta_parse_error\n" +
                "Raw response:\n" +
                "```\n" +
                "testo del modello\n" +
                "```",
            report,
        )
    }

    @Test
    fun `unknown version and model show placeholders and no error code means no line`() {
        val report = rawResponseReport(null, "F-Droid", "hermes", null, null, "x")

        assertTrue(report.startsWith("App: Argus ? (F-Droid)\nProvider: hermes\nModel: -\nRaw response:\n"), report)
        assertFalse("Error code" in report)
    }

    @Test
    fun `the fence is longer than any backtick run inside the raw text`() {
        val raw = "Ecco:\n```json\n{\"a\":1}\n```\ne anche ````quattro````"

        val report = rawResponseReport("0.3.6", "APK", "openai", "gpt-5.5", null, raw)

        val fence = "`````"
        assertTrue(report.contains("Raw response:\n$fence\n"), report)
        assertTrue(report.endsWith("\n$fence"), report)
        assertEquals(2, Regex(fence).findAll(report).count(), "il recinto compare solo in apertura e chiusura")
    }

    @Test
    fun `internal error codes are the simple class name in snake case`() {
        assertEquals("illegal_state_exception", internalErrorCode(IllegalStateException("messaggio segreto")))
        assertEquals("illegal_argument_exception", internalErrorCode(IllegalArgumentException()))
        assertEquals("null_pointer_exception", internalErrorCode(NullPointerException()))
        assertEquals("io_exception", internalErrorCode(java.io.IOException("x")))
        assertEquals("number_format_exception", internalErrorCode(NumberFormatException("Mario Rossi")))
    }

    @Test
    fun `an anonymous exception class still gets a safe code`() {
        val anonymous = object : RuntimeException("dato personale") {}

        val code = internalErrorCode(anonymous)

        assertEquals("unknown_error", code)
        assertFalse("dato" in code)
    }
}
