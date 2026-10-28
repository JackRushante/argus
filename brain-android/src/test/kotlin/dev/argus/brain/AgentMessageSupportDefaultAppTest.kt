package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertTrue

/** Regola 18 del compile (0.7.0): l'app predefinita per tipo e la domanda quando più app vanno bene. */
class AgentMessageSupportDefaultAppTest {
    private val rule: String = AgentMessageSupport.COMPILE_RULES
        .substringAfter("\n18. ").substringBefore("\n19. ")
        .replace(Regex("\\s+"), " ")

    @Test
    fun `a default candidate is used when the request names only the kind`() {
        assertTrue("A candidate with \"default\":true is the user's default app" in rule, rule)
        listOf("calculator", "calendar", "contacts", "email", "gallery", "maps", "messages", "music", "browser", "camera")
            .forEach { kind -> assertTrue(kind in rule, "$kind in $rule") }
        assertTrue("when the request names only that kind, use it" in rule, rule)
    }

    @Test
    fun `with several fitting candidates and no default the model asks which one, naming them`() {
        assertTrue("If several candidates fit the request and none is the default, ask which one" in rule, rule)
        assertTrue("short clarification naming them" in rule, rule)
        assertTrue("return draft null" in rule, rule)
    }

    @Test
    fun `the old behaviour for a single clear match stays`() {
        assertTrue("If exactly one candidate clearly matches the requested app, use its package" in rule, rule)
        assertTrue("Never invent a package outside those candidates" in rule, rule)
    }
}
