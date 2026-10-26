package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertTrue

/** Schema del prompt: i trigger di sistema senza Shizuku (0.4.1) hanno la loro riga. */
class AgentMessageSupportSystemTriggersTest {
    private val prompt = AgentMessageSupport.compileSystemText()

    @Test
    fun `the schema describes each new trigger with its exact type and fields`() {
        listOf(
            "{\"type\":\"screen\", \"event\":\"ON\"|\"OFF\"|\"UNLOCKED\"}",
            "{\"type\":\"battery\", \"below\":integer|null, \"above\":integer|null}",
            "{\"type\":\"audio_output\", \"kind\":\"WIRED\"|\"BLUETOOTH\"|\"ANY\", \"state\":\"CONNECTED\"|\"DISCONNECTED\"}",
            "{\"type\":\"mode_changed\", \"what\":\"DND\"|\"RINGER\", \"to\":string|null}",
            "{\"type\":\"media\", \"state\":\"PLAYING\"|\"STOPPED\"}",
        ).forEach { line ->
            assertTrue(line in prompt, "riga mancante nello schema: $line")
        }
    }

    @Test
    fun `phone_state gains the missed call event and the sim filter`() {
        assertTrue("\"INCOMING_CALL\"|\"CALL_ENDED\"|\"MISSED_CALL\"|\"SMS_RECEIVED\"" in prompt)
        assertTrue("\"sim\":1|2|null" in prompt)
    }

    @Test
    fun `the trigger list rule tells the model to use the new triggers only when listed`() {
        assertTrue("\"screen\", \"battery\", \"audio_output\", \"mode_changed\" and \"media\"" in prompt)
        assertTrue("\"phone_state.sim\"" in prompt)
        assertTrue("\"phone_state.call\" also covers MISSED_CALL" in prompt)
    }

    @Test
    fun `the new trigger guidance stays short`() {
        // Ogni riga costa a ogni compile: la guida dei trigger di sistema sta in poche righe.
        val schemaLines = prompt.lines().count { line ->
            listOf("\"type\":\"screen\"", "\"type\":\"battery\"", "\"type\":\"audio_output\"",
                "\"type\":\"mode_changed\"", "\"type\":\"media\"").any { it in line }
        }
        assertTrue(schemaLines == 5, "una riga di schema per trigger, trovate $schemaLines")
    }
}
