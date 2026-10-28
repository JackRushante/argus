package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Il prompt del compile insegna `ui_steps` con i limiti che il validatore impone, in poche righe. */
class AgentMessageSupportUiStepsTest {
    private val prompt = AgentMessageSupport.compileSystemText()

    private fun block(): String {
        val start = prompt.indexOf("- {\"type\":\"ui_steps\"")
        assertTrue(start >= 0, "manca la riga di schema per ui_steps")
        val end = listOf(prompt.indexOf("\n- {\"type\"", start + 1), prompt.indexOf("\n\n", start))
            .filter { it >= 0 }.minOrNull() ?: prompt.length
        return prompt.substring(start, end).replace(Regex("\\s+//\\s+"), " ").replace(Regex("\\s+"), " ")
    }

    @Test
    fun `the schema lists the action and every step with the exact registered fields`() {
        val text = block()
        assertTrue("\"type\":\"ui_steps\", \"pkg\":string|null, \"steps\":[Step,...]" in text, text)
        assertTrue("\"op\":\"wait_for\",\"text\":string|null,\"id\":string|null,\"timeoutMs\":integer 100-15000" in text, text)
        assertTrue(
            "\"op\":\"click\",\"text\":string|null,\"id\":string|null,\"desc\":string|null,\"index\":integer|null" in text,
            text,
        )
        assertTrue("\"op\":\"type\",\"text\":string" in text, text)
        assertTrue("\"op\":\"scroll\",\"dir\":\"UP\"|\"DOWN\"" in text, text)
        assertTrue("\"op\":\"back\"" in text && "\"op\":\"home\"" in text, text)
        assertTrue("\"op\":\"check\",\"text\":string,\"present\":boolean" in text, text)
    }

    @Test
    fun `it states the limits, the last resort rule and the forbidden targets`() {
        val text = block()
        assertTrue("1-20 steps" in text, text)
        assertTrue("60 s" in text, text)
        assertTrue("LAST RESORT" in text && "no typed action or intent" in text, text)
        assertTrue("exact \"id\" or visible \"text\"" in text, text)
        assertTrue("Never on Argus, Settings, the installer or permission dialogs" in text, text)
        assertTrue("Same triggers as run_shell" in text, text)
        assertTrue("available_tools only when that service is on" in text, text)
    }

    @Test
    fun `it explains that a wait with no target waits for the app and points to the wait action`() {
        val text = block()
        assertTrue("wait_for with text and id both null waits for the app in pkg to be open" in text, text)
        assertTrue("needs pkg" in text, text)
        assertTrue("outside macros there is the wait action" in text, text)
    }

    @Test
    fun `a tool that is off is explained to the user with how to turn it on, never as a missing feature`() {
        val rules = AgentMessageSupport.COMPILE_RULES.replace(Regex("\\s+"), " ")
        val rule = rules.substring(rules.indexOf("A tool in manifest.unavailable_tools"), rules.indexOf(" 2. Use only keys"))
        assertTrue("the value says why" in rule, rule)
        assertTrue("never say the function does not exist or is not available in this version" in rule, rule)
        assertTrue("how to turn it on" in rule, rule)
        assertTrue("ui_steps" in rule && "Accessibility service" in rule, rule)
        assertTrue("unsupported_capability" in rule, rule)
    }

    @Test
    fun `an interpolation is allowed only in the typed text`() {
        assertTrue("\${var} allowed only here" in block(), block())
    }

    @Test
    fun `the new type costs a handful of lines and leaves no kotlin escape in the prompt`() {
        assertTrue(block().length < 1_500, "le righe nuove pesano a ogni compile: ${block().length} caratteri")
        assertFalse("\${'\$'}" in prompt)
        assertEquals(1, Regex("- \\{\"type\":\"ui_steps\"").findAll(prompt).count())
    }
}
