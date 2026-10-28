package dev.argus.ui.components

import dev.argus.engine.safety.Severity
import dev.argus.ui.model.DraftCardStatus
import dev.argus.ui.model.UiWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Logica pura delle schede della chat (redesign A, 0.7.0): la card bozza (invariante 1: un solo pulsante, gli
 * ERROR per intero, gli avvisi contati) e la barra dei tempi dell'attesa.
 */
class ChatCardsLogicTest {
    private fun issue(severity: Severity, code: String) = UiWarning(severity, code, "testo $code")

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(kotlin.math.abs(expected - actual) <= TOLERANCE, "atteso $expected, trovato $actual")

    private companion object {
        const val TOLERANCE = 1e-5f
    }

    // --- Card bozza ---------------------------------------------------------------------------------

    @Test
    fun `the review button is the only way forward and only a draft still waiting has it`() {
        assertTrue(showsReviewButton(DraftCardStatus.PROPOSED))
        assertFalse(showsReviewButton(DraftCardStatus.APPROVED))
        assertFalse(showsReviewButton(DraftCardStatus.REJECTED))
        assertFalse(showsReviewButton(DraftCardStatus.SUPERSEDED))
    }

    @Test
    fun `every ERROR is shown in full and in arrival order, warnings never are`() {
        val issues = listOf(
            issue(Severity.WARNING, "w1"), issue(Severity.ERROR, "e1"),
            issue(Severity.WARNING, "w2"), issue(Severity.ERROR, "e2"),
        )

        assertEquals(listOf("e1", "e2"), blockingIssues(issues).map { it.code })
        assertEquals(emptyList(), blockingIssues(listOf(issue(Severity.WARNING, "w1"))))
    }

    @Test
    fun `warnings are counted and errors are not counted as warnings`() {
        assertEquals(0, warningCountOf(emptyList()))
        assertEquals(1, warningCountOf(listOf(issue(Severity.WARNING, "w1"))))
        assertEquals(
            2,
            warningCountOf(listOf(issue(Severity.WARNING, "w1"), issue(Severity.ERROR, "e1"), issue(Severity.WARNING, "w2"))),
        )
    }

    @Test
    fun `the trigger line loses the When prefix in both languages and nothing else`() {
        assertEquals("notifica WhatsApp da \"Moglie\" (chat 1:1)", stripWhenPrefix("Quando: notifica WhatsApp da \"Moglie\" (chat 1:1)"))
        assertEquals("a WhatsApp notification from Moglie", stripWhenPrefix("When: a WhatsApp notification from Moglie"))
        assertEquals("ogni giorno alle 23:00", stripWhenPrefix("ogni giorno alle 23:00"))
        // Solo all'inizio: un «Quando: » dentro il testo resta.
        assertEquals("tra le 8 e le 9 Quando: sì", stripWhenPrefix("tra le 8 e le 9 Quando: sì"))
    }

    // --- Barra dei tempi ----------------------------------------------------------------------------

    @Test
    fun `the wait bar of the tavola is 12 seconds of 65 with the typical band at 10 to 30`() {
        val bar = waitBarOf(elapsedSec = 12, timeoutSec = 65)

        assertClose(12f / 65f, bar.progress)
        assertClose(10f / 65f, bar.bandStart)
        assertClose(20f / 65f, bar.bandWidth)
        assertEquals(65, bar.axisSec)
    }

    @Test
    fun `an unknown timeout uses the standard 65 second axis`() {
        assertEquals(65, waitBarOf(elapsedSec = 0, timeoutSec = null).axisSec)
    }

    @Test
    fun `a local server timeout stretches the axis and the band shrinks with it`() {
        val bar = waitBarOf(elapsedSec = 90, timeoutSec = 180)

        assertEquals(180, bar.axisSec)
        assertClose(0.5f, bar.progress)
        assertClose(10f / 180f, bar.bandStart)
    }

    @Test
    fun `the fill never leaves the bar, whatever the elapsed time`() {
        assertEquals(1f, waitBarOf(elapsedSec = 200, timeoutSec = 65).progress)
        assertEquals(0f, waitBarOf(elapsedSec = -5, timeoutSec = 65).progress)
        assertEquals(1f, waitBarOf(elapsedSec = 65, timeoutSec = 65).progress)
    }

    @Test
    fun `a nonsense timeout cannot put the typical band outside the bar`() {
        listOf(0, 1, 15, -3).forEach { timeout ->
            val bar = waitBarOf(elapsedSec = 5, timeoutSec = timeout)
            assertTrue(bar.bandStart in 0f..1f, "bandStart con timeout $timeout")
            assertTrue(bar.bandStart + bar.bandWidth <= 1f + TOLERANCE, "fine fascia con timeout $timeout")
            assertTrue(bar.axisSec >= TYPICAL_WAIT_TO_SEC, "asse con timeout $timeout")
        }
    }
}
