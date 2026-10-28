package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.ScrollDirection
import dev.argus.engine.model.UiStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Resa di `ui_steps` in approvazione e nel dettaglio: la macro per intero, un passo per riga, dai
 * tipi (invariante 4), con l'avviso che agisce sullo schermo come l'utente. Bilingue.
 */
class ActionRowsUiStepsTest {
    private val macro = Action.UiSteps(
        pkg = "com.example.shop",
        steps = listOf(
            UiStep.WaitFor(text = "Cerca", timeoutMs = 7_500),
            UiStep.Click(id = "search_box"),
            UiStep.Type("scarpe \${tag}"),
            UiStep.Scroll(ScrollDirection.DOWN),
            UiStep.Click(text = "Aggiungi", desc = "carrello", index = 1),
            UiStep.Check("Nel carrello", present = true),
            UiStep.Back,
            UiStep.Home,
        ),
    )

    private fun lines(l: RenderLanguage) = ActionRowsUiSteps.uiSteps(macro, l).detail.orEmpty().lines()

    @Test
    fun `every step has its own numbered line, in order, and the package is shown whole`() {
        val en = lines(RenderLanguage.EN)
        assertEquals("Opens com.example.shop first", en.first())
        assertEquals(
            listOf(
                "1. Wait up to 7.5 s for text \"Cerca\"",
                "2. Tap the element with id \"search_box\"",
                "3. Type \"scarpe \${tag}\" in the focused field",
                "4. Scroll down",
                "5. Tap the element with text \"Aggiungi\" and description \"carrello\" (match number 2)",
                "6. Check that \"Nel carrello\" is on the screen, otherwise stop",
                "7. Press Back",
                "8. Press Home",
            ),
            en.drop(1).dropLast(1),
        )
    }

    @Test
    fun `the italian render says the same things`() {
        val it = lines(RenderLanguage.IT)
        assertEquals("Prima apre com.example.shop", it.first())
        assertEquals("1. Aspetta fino a 7.5 s: testo \"Cerca\"", it[1])
        assertEquals("4. Scorri in giù", it[4])
        assertEquals("6. Controlla che \"Nel carrello\" sia sullo schermo, altrimenti fermati", it[6])
        assertEquals("8. Premi Home", it[8])
    }

    @Test
    fun `the last line warns that the macro acts on the screen like the user`() {
        assertTrue("Acts on the screen like you would" in lines(RenderLanguage.EN).last())
        assertTrue("Agisce sullo schermo come faresti tu" in lines(RenderLanguage.IT).last())
    }

    @Test
    fun `a macro on the current screen says so`() {
        val row = ActionRowsUiSteps.uiSteps(Action.UiSteps(null, listOf(UiStep.Back)), RenderLanguage.EN)
        assertEquals("Works on the screen as it is", row.detail.orEmpty().lines().first())
    }

    @Test
    fun `a not-present check reads as the opposite`() {
        val text = ActionRowsUiSteps.stepText(UiStep.Check("Errore", present = false), RenderLanguage.EN)
        assertTrue("is NOT on the screen" in text, text)
    }

    @Test
    fun `the row is a plain action row, never a shell and never generative`() {
        val row = ActionRowsUiSteps.uiSteps(macro, RenderLanguage.EN)
        assertFalse(row.isShell)
        assertFalse(row.isGenerative)
        assertEquals(null, row.shellCommand)
        assertEquals("tap", row.iconKey)
        assertEquals("Steps on the screen", row.label)
    }

    @Test
    fun `all twenty steps are shown, none is dropped`() {
        val twenty = Action.UiSteps(null, List(20) { UiStep.Click(text = "Voce $it") })
        val shown = ActionRowsUiSteps.uiSteps(twenty, RenderLanguage.EN).detail.orEmpty().lines()
        assertEquals(20, shown.count { Regex("^\\d+\\. Tap").containsMatchIn(it) })
        assertTrue(shown.any { it.startsWith("20. ") })
    }
}
