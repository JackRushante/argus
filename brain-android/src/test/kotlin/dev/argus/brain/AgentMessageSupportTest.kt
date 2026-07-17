package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertTrue

class AgentMessageSupportTest {
    @Test
    fun `compile prompt teaches the immediate one-shot trigger`() {
        val prompt = AgentMessageSupport.compileSystemText()

        // Regola 10: "immediate" deve comparire tra i trigger armabili elencati.
        assertTrue("\"immediate\"" in prompt, "la lista dei trigger deve includere immediate")
        // Regola dedicata one-shot: l'orario sveglia/timer va nell'azione, non nel trigger.
        assertTrue("set_alarm/set_timer" in prompt, "la regola one-shot deve rimandare l'ora all'azione")
        // Schema draft: il trigger immediate deve essere descritto.
        assertTrue(
            "{\"type\":\"immediate\"}" in prompt,
            "lo schema draft deve descrivere il trigger immediate",
        )
    }
}
