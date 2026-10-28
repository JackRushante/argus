package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Il prompt del compile insegna le otto azioni privilegiate 0.5.1 con i nomi e i campi registrati. */
class AgentMessageSupportPrivileged051Test {
    private val prompt = AgentMessageSupport.compileSystemText()

    /** La riga dello schema che inizia con `- {"type":"<tipo>"` e i suoi commenti. */
    private fun block(type: String): String {
        val start = prompt.indexOf("- {\"type\":\"$type\"")
        assertTrue(start >= 0, "manca la riga di schema per $type")
        val end = listOf(prompt.indexOf("\n- {\"type\"", start + 1), prompt.indexOf("\n\n", start))
            .filter { it >= 0 }.minOrNull() ?: prompt.length
        return prompt.substring(start, end).replace(Regex("\\s+//\\s+"), " ").replace(Regex("\\s+"), " ")
    }

    @Test
    fun `the schema lists the eight actions with the exact registered fields`() {
        assertTrue("\"type\":\"app_control\", \"pkg\":string, \"op\":\"FORCE_STOP\"|\"FREEZE\"|\"UNFREEZE\"" in block("app_control"))
        assertTrue("\"type\":\"set_airplane\", \"on\":boolean" in block("set_airplane"))
        assertTrue("\"type\":\"set_location\", \"on\":boolean" in block("set_location"))
        assertTrue("\"type\":\"set_nfc\", \"on\":boolean" in block("set_nfc"))
        assertTrue("\"type\":\"set_battery_saver\", \"on\":boolean" in block("set_battery_saver"))
        assertTrue(block("lock_screen").startsWith("- {\"type\":\"lock_screen\"}"))
        assertTrue(block("screenshot").startsWith("- {\"type\":\"screenshot\"}"))
        assertTrue(
            "\"type\":\"app_permission\", \"pkg\":string, \"permission\":string, \"grant\":boolean" in
                block("app_permission"),
        )
    }

    @Test
    fun `package and permission are literal and the app comes from the local candidates`() {
        val control = block("app_control")
        assertTrue("installed_app_candidates" in control, control)
        assertTrue("never \${var}" in control, control)
        assertTrue("Argus" in control, control)
        val permission = block("app_permission")
        assertTrue("pkg and permission are literal, never \${var}" in permission, permission)
        assertTrue("android.permission.RECORD_AUDIO" in permission, permission)
    }

    @Test
    fun `the prompt prefers them to run_shell and says they depend on a privileged backend`() {
        val flat = block("app_permission")
        assertTrue("ALWAYS prefer them to run_shell" in flat, flat)
        assertTrue("need Shizuku or root" in flat, flat)
        assertTrue("available_tools only when available" in flat, flat)
    }

    @Test
    fun `the eight lines cost a handful of characters and leave no kotlin escape behind`() {
        val added = listOf(
            "app_control", "set_airplane", "set_location", "set_nfc", "set_battery_saver", "lock_screen",
            "screenshot", "app_permission",
        ).sumOf { block(it).length }
        assertTrue(added < 1_900, "le righe nuove pesano a ogni compile: $added caratteri")
        assertFalse("\${'\$'}" in prompt, "escape Kotlin rimasto nel testo del prompt")
        assertEquals(1, Regex("- \\{\"type\":\"app_control\"").findAll(prompt).count())
    }
}
