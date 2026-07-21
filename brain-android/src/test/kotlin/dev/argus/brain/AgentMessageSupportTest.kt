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

    // --- P4-D: lo schema draft insegna la grammatica Tasker-class (schema v2) ---

    @Test
    fun `compile prompt teaches the P4 vars grammar and bounds`() {
        val prompt = AgentMessageSupport.compileSystemText()

        // Variabili tipizzate opzionali + regex nome + tetto 16.
        assertTrue("\"vars\"" in prompt, "lo schema deve descrivere il campo vars")
        assertTrue(
            "^[a-z][a-z0-9_]{0,31}\$" in prompt,
            "lo schema deve dichiarare la regex dei nomi di variabile",
        )
        assertTrue("16 variables" in prompt, "il tetto 16 variabili deve comparire")
        // Le tre sorgenti di binding.
        assertTrue("\"type\":\"literal\"" in prompt, "binding literal (CLEAN)")
        assertTrue("\"type\":\"state\"" in prompt, "binding state (CLEAN)")
        assertTrue("\"type\":\"trigger_payload\"" in prompt, "binding trigger_payload (TAINTED)")
    }

    @Test
    fun `compile prompt teaches captureAs only on the three producers`() {
        val prompt = AgentMessageSupport.compileSystemText()

        assertTrue("captureAs" in prompt, "captureAs deve essere descritto")
        assertTrue(
            "run_shell, invoke_llm and invoke_llm_v2" in prompt,
            "captureAs è ammesso solo sui tre producer",
        )
    }

    @Test
    fun `compile prompt teaches the control-flow actions and their bounds`() {
        val prompt = AgentMessageSupport.compileSystemText()

        assertTrue("\"type\":\"if\"" in prompt, "azione if")
        assertTrue("\"type\":\"while\"" in prompt, "azione while")
        assertTrue("\"type\":\"wait\"" in prompt, "azione wait")
        // Bound numerici P4 §2.5.
        assertTrue("maxIterations\":integer 1..1000" in prompt, "while maxIterations 1..1000")
        assertTrue("durationMs\":integer 1..3600000" in prompt, "wait <= 1 ora")
        assertTrue("64 action nodes" in prompt, "tetto 64 nodi azione")
        assertTrue("depth 4" in prompt || "depth <= 4" in prompt, "profondità annidamento 4")
        assertTrue("6 hours" in prompt, "budget tempo worst-case 6 ore")
    }

    @Test
    fun `compile prompt teaches the flow conditions and the aggressive posture with kept shell-gating`() {
        val prompt = AgentMessageSupport.compileSystemText()

        assertTrue("var_compare" in prompt, "condizione var_compare")
        assertTrue("boolean_literal" in prompt, "condizione boolean_literal")
        assertTrue("expectedVar" in prompt, "confronto var-vs-var")
        // Posture AGGRESSIVO: il prompt permette di usare liberamente i valori runtime nei campi,
        // inclusi comandi/URL/target — non li confina più ai soli data sink.
        assertTrue(
            "freely" in prompt.lowercase(),
            "il prompt deve permettere l'uso libero dei valori runtime",
        )
        // Dente che teniamo: run_shell resta innescabile solo da contatti whitelistati.
        assertTrue("run_shell" in prompt, "il prompt deve nominare run_shell")
        assertTrue(
            "whitelist" in prompt.lowercase(),
            "il prompt deve mantenere lo shell-gating sui contatti whitelistati",
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
        assertTrue("NOTIFICATION" in system)
        assertFalse("WhatsApp" in system, "il sink non parla di messaggi WhatsApp")
        assertFalse("messaggio" in system.lowercase(), "il sink non parla di un messaggio ricevuto")

        // User message: neutro senza stato, con le sole state lines quando presenti.
        assertEquals(
            "Generate the requested content now.",
            AgentMessageSupport.actUserTextNotification(emptyList()),
        )
        val withState = AgentMessageSupport.actUserTextNotification(listOf("ringer=normal", "battery=80"))
        assertTrue("ringer=normal" in withState)
        assertTrue("battery=80" in withState)
        assertFalse("Message received" in withState)
    }
}
