package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Il prompt del compile insegna le azioni 0.4.1 con le regole di sicurezza che il validatore impone. */
class AgentMessageSupportActions041Test {
    private val prompt = AgentMessageSupport.compileSystemText()

    /** La riga dello schema che inizia con `- {"type":"<tipo>"` e le sue righe di commento. */
    private fun block(type: String): String {
        val start = prompt.indexOf("- {\"type\":\"$type\"")
        assertTrue(start >= 0, "manca la riga di schema per $type")
        val end = listOf(prompt.indexOf("\n- {\"type\"", start + 1), prompt.indexOf("\n\n", start))
            .filter { it >= 0 }.minOrNull() ?: prompt.length
        // I commenti dello schema vanno a capo con `//`: si tolgono i marcatori e si normalizzano gli spazi.
        return prompt.substring(start, end).replace(Regex("\\s+//\\s+"), " ").replace(Regex("\\s+"), " ")
    }

    @Test
    fun `the schema lists the five new actions with the exact registered fields`() {
        assertTrue("\"type\":\"speak\", \"text\":string" in block("speak"))
        assertTrue(
            "\"command\":\"PLAY\"|\"PAUSE\"|\"PLAY_PAUSE\"|\"NEXT\"|\"PREVIOUS\"" in block("media_control"),
        )
        assertTrue("\"level\":integer 0-100 | \"auto\":boolean" in block("set_brightness"))
        assertTrue("\"type\":\"set_rotation\", \"auto\":boolean" in block("set_rotation"))
        val http = block("http_request")
        assertTrue(
            "\"method\":\"GET\"|\"POST\"|\"PUT\"|\"DELETE\", \"url\":string, " +
                "\"headers\":{string:string}|null, \"body\":string|null, \"contentType\":string|null" in http,
            http,
        )
    }

    @Test
    fun `http_request is only for a url the user gave and keeps url and headers literal`() {
        val http = block("http_request")
        assertTrue("ONLY with a URL the user gave you" in http, http)
        assertTrue("never invent or guess one" in http, http)
        assertTrue("url and headers are LITERAL" in http, http)
        assertTrue("https only (http only for localhost)" in http, http)
        assertTrue("at most 10 headers" in http, http)
        assertTrue("body max 16 KB" in http, http)
        // I dati esterni solo nel body, dentro una stringa JSON; nel prompt il dollaro è reso `$`.
        assertTrue("go ONLY in body" in http, http)
        assertTrue("\"body\":\"{\\\"text\\\":\\\"\${sms}\\\"}\"" in http, http)
    }

    @Test
    fun `speak allows an interpolation and brightness needs the special access`() {
        assertTrue("\${var} interpolation allowed" in block("speak"))
        val brightness = block("set_brightness")
        assertTrue("EXACTLY ONE" in brightness, brightness)
        assertTrue("Modify system settings" in brightness, brightness)
        assertTrue("available_tools only when that access is granted" in brightness, brightness)
        assertTrue("same access and availability as set_brightness" in block("set_rotation"))
    }

    @Test
    fun `typed brightness and media keys are preferred over the shell and write_setting`() {
        assertTrue("brightness is set_brightness" in prompt.replace(Regex("\\s+//\\s*|\\s+"), " "))
        assertTrue("media keys are media_control" in prompt.replace(Regex("\\s+//\\s*|\\s+"), " "))
        // La documentazione di write_setting per la luminosità resta, per chi ha Shizuku e non il permesso.
        assertTrue(
            "screen_brightness (0-255, SYSTEM; set screen_brightness_mode=0 first to disable auto-brightness)" in prompt,
        )
    }

    @Test
    fun `each new type costs a handful of lines and the prompt has no leftover dollar escape`() {
        val added = listOf("speak", "media_control", "set_brightness", "set_rotation", "http_request")
            .sumOf { block(it).length }
        assertTrue(added < 2_800, "le righe nuove pesano a ogni compile: $added caratteri")
        assertFalse("\${'\$'}" in prompt, "escape Kotlin rimasto nel testo del prompt")
        assertEquals(1, Regex("- \\{\"type\":\"http_request\"").findAll(prompt).count())
    }
}
