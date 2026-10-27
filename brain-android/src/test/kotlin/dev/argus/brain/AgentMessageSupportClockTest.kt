package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prompt di compile 0.5.0: la variabile `clock` e la regola sui valori che cambiano. */
class AgentMessageSupportClockTest {
    private val system = AgentMessageSupport.compileSystemText()

    @Test
    fun `the schema announces the clock variable with its exact shape`() {
        assertTrue("- {\"type\":\"clock\", \"name\":string, \"tz\":string|null, \"pattern\":string|null}" in system)
        val block = system.substringAfter("- {\"type\":\"clock\"").substringBefore("\n\n")
            .substringBefore("\n\"captureAs\"")
        // Sobrio: la riga di schema e al massimo tre righe di guida.
        assertTrue(block.lines().size <= 4, block)
        listOf("AT FIRE TIME", "IANA", "null = the phone's zone", "DateTimeFormatter", "max 40 chars", "\"HH:mm\"")
            .forEach { part -> assertTrue(part in block, "$part in $block") }
        assertTrue("\${name}" in block, block)
    }

    @Test
    fun `one general rule says that changing values are read at fire time and never written as text`() {
        val rules = AgentMessageSupport.COMPILE_RULES
        val rule = rules.substringAfter("\n19. ")
        assertTrue(rules.contains("\n19. "))
        // Al massimo 3 righe, ed e' l'ultima regola.
        assertTrue(rule.lines().size <= 3, rule)
        val flat = rule.replace(Regex("\\s+"), " ")
        listOf(
            "Rule texts are frozen when you write them",
            "current time or date, battery level, weather",
            "never written as fixed text but read at fire time",
            "a clock variable for time and date",
            "a state variable for phone values",
            "invoke_llm for web data",
            "otherwise say it cannot be done",
        ).forEach { part -> assertTrue(part in flat, "$part in $flat") }
    }

    @Test
    fun `the local time given to the model stays in the user message and not in the system prompt`() {
        assertEquals(false, "local_time:" in system)
    }
}
