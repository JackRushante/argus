package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun `compile prompt teaches the relative afterMs delay for tra N`() {
        val prompt = AgentMessageSupport.compileSystemText()

        // Regola 15: "tra N" ora è un ritardo relativo afterMs (millisecondi), non più "at".
        assertTrue("afterMs" in prompt, "la regola temporale deve insegnare afterMs")
        assertTrue(
            "afterMs=120000" in prompt,
            "l'esempio 'tra 2 minuti' deve mappare su afterMs in millisecondi",
        )
        // Lo schema draft del trigger time deve dichiarare il campo afterMs.
        assertTrue(
            "\"afterMs\":integer|null" in prompt,
            "lo schema draft time deve descrivere afterMs",
        )
    }

    // --- #59 Ondata 4a: sink NOTIFICA (nessuna notifica, testo dal solo goal) ---

    @Test
    fun `generative context sources accept the sink subset and reject notification`() {
        // Reply (useReplyTool=true): comportamento invariato, "notification" obbligatoria.
        AgentMessageSupport.requireGenerativeContextSources(listOf("notification"), useReplyTool = true)
        AgentMessageSupport.requireGenerativeContextSources(listOf("notification", "state"), useReplyTool = true)

        // Sink (useReplyTool=false): [] e [state] validi.
        AgentMessageSupport.requireGenerativeContextSources(emptyList(), useReplyTool = false)
        AgentMessageSupport.requireGenerativeContextSources(listOf("state"), useReplyTool = false)

        // Sink rifiuta "notification", sorgenti sconosciute e duplicati.
        listOf(
            listOf("notification"),
            listOf("notification", "state"),
            listOf("screen"),
            listOf("state", "state"),
        ).forEach { sources ->
            assertFailsWith<TransportException> {
                AgentMessageSupport.requireGenerativeContextSources(sources, useReplyTool = false)
            }
        }
    }

    @Test
    fun `notification prompts carry the goal without any whatsapp framing`() {
        val system = AgentMessageSupport.actSystemTextNotification("promemoria acqua")
        assertTrue("promemoria acqua" in system, "il goal deve entrare nel system del sink")
        assertTrue("NOTIFICA" in system)
        assertFalse("WhatsApp" in system, "il sink non parla di messaggi WhatsApp")
        assertFalse("messaggio" in system.lowercase(), "il sink non parla di un messaggio ricevuto")

        // User message: neutro senza stato, con le sole state lines quando presenti.
        assertEquals(
            "Genera ora il contenuto richiesto.",
            AgentMessageSupport.actUserTextNotification(emptyList()),
        )
        val withState = AgentMessageSupport.actUserTextNotification(listOf("ringer=normal", "battery=80"))
        assertTrue("ringer=normal" in withState)
        assertTrue("battery=80" in withState)
        assertFalse("Messaggio ricevuto" in withState)
    }
}
