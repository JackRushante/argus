package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Schema del prompt: il trigger `app_event` (0.5.1, F15) ha la sua riga, una riga di guida e la regola 10. */
class AgentMessageSupportAppEventTest {
    private val prompt = AgentMessageSupport.compileSystemText()

    @Test
    fun `the schema has the app event trigger with its exact type and fields`() {
        assertTrue(
            "{\"type\":\"app_event\", \"pkg\":string, \"event\":\"OPENED\"|\"CLOSED\"}" in prompt,
            "riga mancante nello schema",
        )
        // Una sola riga di schema per il tipo.
        assertEquals(1, prompt.lines().count { "\"type\":\"app_event\"" in it })
    }

    @Test
    fun `one guide line says to use the package and to split opening and closing`() {
        val lines = prompt.lines()
        val index = lines.indexOfFirst { "\"type\":\"app_event\"" in it }
        val guide = lines[index + 1]
        assertTrue("package" in guide && "installed_app_candidates" in guide, guide)
        assertTrue("two rules" in guide, guide)
        // La guida è una riga sola: la successiva è già il trigger seguente.
        assertTrue(lines[index + 2].startsWith("- {\"type\":"), lines[index + 2])
    }

    @Test
    fun `the trigger list rule names it as usable only when listed`() {
        assertTrue("\"app_event\" (an app opened or closed) is usable only if listed" in prompt)
        // Le frasi dei trigger precedenti restano com'erano.
        assertTrue("\"screen\", \"battery\", \"audio_output\", \"mode_changed\" and \"media\" are usable only if listed" in prompt)
    }
}
