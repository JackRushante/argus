package dev.argus.automation.vm

import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Motivi del Log delle azioni 0.4.1: ognuno ha un testo in inglese e in italiano. */
class LogReasonLabelsActions041Test {
    private val codes = listOf(
        "deferred_to_notification",
        "activity_start_blocked",
        "tts_unavailable",
        "tts_failed",
        "tts_timeout",
        "write_settings_unavailable",
        "settings_write_failed",
        "http_request_invalid",
        "http_timeout",
        "http_network_error",
        "http_tls_error",
        "http_redirect_blocked",
        "http_too_many_redirects",
        "json_quote_context",
    )

    @Test
    fun `each new reason has a readable text in both languages`() {
        codes.forEach { code ->
            val en = assertNotNull(LogReasonLabels.label(code, RenderLanguage.EN), code)
            val it = assertNotNull(LogReasonLabels.label(code, RenderLanguage.IT), code)
            assertTrue(en.isNotBlank() && it.isNotBlank() && en != it, code)
            assertTrue(code !in en && code !in it, code)
        }
    }

    @Test
    fun `the deferred outcome reads differently from a plain block`() {
        assertTrue(
            LogReasonLabels.text("deferred_to_notification", RenderLanguage.EN) !=
                LogReasonLabels.text("activity_start_blocked", RenderLanguage.EN),
        )
    }

    @Test
    fun `an http status is shown with its number and nothing from the response`() {
        assertEquals(
            "the server answered with status 404",
            LogReasonLabels.label("http_status_404", RenderLanguage.EN),
        )
        assertEquals(
            "il server ha risposto con stato 503",
            LogReasonLabels.label("http_status_503", RenderLanguage.IT),
        )
        assertNull(LogReasonLabels.label("http_status_", RenderLanguage.EN))
        assertNull(LogReasonLabels.label("http_status_9999", RenderLanguage.EN))
        assertNull(LogReasonLabels.label("http_status_abc", RenderLanguage.EN))
    }
}
