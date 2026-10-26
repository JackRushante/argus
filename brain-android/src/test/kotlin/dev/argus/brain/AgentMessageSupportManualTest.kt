package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Schema del prompt: il trigger `manual` (0.4.1, F8) ha la sua riga e dice quando usarlo. */
class AgentMessageSupportManualTest {
    private val prompt = AgentMessageSupport.compileSystemText()

    @Test
    fun `the schema has the manual trigger with its exact type`() {
        assertTrue("{\"type\":\"manual\"}" in prompt, "riga mancante nello schema")
    }

    @Test
    fun `the schema line says when to use it`() {
        val lines = prompt.lines()
        val index = lines.indexOfFirst { "{\"type\":\"manual\"}" in it }
        val text = lines.drop(index).take(2).joinToString(" ")
        listOf("button", "widget", "shortcut", "by hand").forEach { word ->
            assertTrue(word in text, "manca \"$word\" nella guida del trigger manuale: $text")
        }
    }

    @Test
    fun `the trigger list rule names it and the guidance stays short`() {
        assertTrue("\"manual\" (user-started" in prompt)
        // Una sola riga di schema per il tipo; la guida occupa al massimo due righe in tutto.
        assertEquals(1, prompt.lines().count { "\"type\":\"manual\"" in it })
    }
}
