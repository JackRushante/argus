package dev.argus.automation.vm

import dev.argus.automation.screen.ScreenCodes
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Motivi del Log delle macro sullo schermo (0.5.1): ognuno ha un testo in inglese e in italiano. */
class LogReasonLabelsUiStepsTest {
    private val codes = listOf(
        ScreenCodes.SERVICE_UNAVAILABLE,
        ScreenCodes.BUSY,
        ScreenCodes.FORBIDDEN_SCREEN,
        ScreenCodes.OPEN_FAILED,
        ScreenCodes.ELEMENT_NOT_FOUND,
        ScreenCodes.CHECK_FAILED,
        ScreenCodes.TIMEOUT,
        ScreenCodes.MACRO_TIMEOUT,
        ScreenCodes.STOPPED,
        ScreenCodes.CLICK_FAILED,
        ScreenCodes.INPUT_FAILED,
        ScreenCodes.SCROLL_FAILED,
        ScreenCodes.ACTION_FAILED,
        ScreenCodes.NOTIFICATION_UNAVAILABLE,
        "ui_steps_external_trigger",
    )

    @Test
    fun `each reason the macro can stop with has a readable text in both languages`() {
        codes.forEach { code ->
            val en = assertNotNull(LogReasonLabels.label(code, RenderLanguage.EN), code)
            val it = assertNotNull(LogReasonLabels.label(code, RenderLanguage.IT), code)
            assertTrue(en.isNotBlank() && it.isNotBlank() && en != it, code)
            assertTrue(code !in en && code !in it, code)
        }
    }

    @Test
    fun `the codes are closed snake case words that the journal accepts as they are`() {
        val journalCode = Regex("^[a-z][a-z0-9_]{0,63}$")
        codes.forEach { assertTrue(journalCode.matches(it), it) }
    }
}
