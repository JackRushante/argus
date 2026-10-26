package dev.argus.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B48 (0.4.0): «Svuota conversazione» e «rimuovi contatto» agivano subito. Ora chiedono conferma, e
 * l'annullamento non esegue niente.
 */
class ConfirmationStateTest {
    @Test
    fun `nothing is asked until someone asks`() {
        val state = ConfirmationState<String>()

        assertFalse(state.isOpen)
        assertNull(state.pending)
    }

    @Test
    fun `asking opens the confirmation without doing anything`() {
        val done = mutableListOf<String>()

        val state = ConfirmationState<String>().ask("contatto")

        assertTrue(state.isOpen)
        assertEquals("contatto", state.pending)
        assertTrue(done.isEmpty())
    }

    @Test
    fun `cancelling closes the confirmation and never runs the action`() {
        val done = mutableListOf<String>()
        val asked = ConfirmationState<String>().ask("contatto")

        val state = asked.cancel()

        assertFalse(state.isOpen)
        assertTrue(done.isEmpty(), "annullare non cancella")
        // Dopo l'annullamento una conferma tardiva non trova piu' niente da eseguire.
        state.confirm { done += it }
        assertTrue(done.isEmpty())
    }

    @Test
    fun `confirming runs the action once with the asked item and closes`() {
        val done = mutableListOf<String>()
        val asked = ConfirmationState<String>().ask("contatto")

        val state = asked.confirm { done += it }

        assertEquals(listOf("contatto"), done)
        assertFalse(state.isOpen)
        // Un secondo tocco sul pulsante non esegue di nuovo.
        state.confirm { done += it }
        assertEquals(listOf("contatto"), done)
    }

    @Test
    fun `asking again replaces the pending item`() {
        val done = mutableListOf<String>()

        ConfirmationState<String>().ask("primo").ask("secondo").confirm { done += it }

        assertEquals(listOf("secondo"), done)
    }

    @Test
    fun `a confirmation without payload works too`() {
        var cleared = 0

        val asked = ConfirmationState<Unit>().ask(Unit)
        assertTrue(asked.isOpen)
        asked.cancel().confirm { cleared++ }
        assertEquals(0, cleared)

        asked.confirm { cleared++ }
        assertEquals(1, cleared)
    }
}
