package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8: le regole chiuse di `confirm`, `ask` e della risposta vincolata del modello. */
class UserPromptPolicyTest {
    private val choice = OutputSpec(OutputKind.CHOICE, choices = listOf("urgent", "normal", "low-1"))
    private val number = OutputSpec(OutputKind.NUMBER, min = -2, max = 10)
    private val boolean = OutputSpec(OutputKind.BOOLEAN)

    @Test
    fun `a choice answer must be exactly one approved label after trimming`() {
        assertEquals("urgent", LlmOutputPolicy.normalize(choice, "urgent"))
        assertEquals("low-1", LlmOutputPolicy.normalize(choice, "  low-1\n"))
        listOf("Urgent", "urgent.", "\"urgent\"", "urgent normal", "", "it is urgent").forEach {
            assertNull(LlmOutputPolicy.normalize(choice, it), it)
        }
    }

    @Test
    fun `a number answer is a plain integer within the approved bounds`() {
        assertEquals("10", LlmOutputPolicy.normalize(number, "10"))
        assertEquals("-2", LlmOutputPolicy.normalize(number, " -2 "))
        assertEquals("0", LlmOutputPolicy.normalize(number, "0"))
        listOf("11", "-3", "+5", "05", "5.0", "five", "5 stars", "", "-0", "99999999999999999999").forEach {
            assertNull(LlmOutputPolicy.normalize(number, it), it)
        }
    }

    @Test
    fun `a boolean answer is yes or no, captured as true or false`() {
        assertEquals("true", LlmOutputPolicy.normalize(boolean, "yes"))
        assertEquals("false", LlmOutputPolicy.normalize(boolean, "no\n"))
        assertEquals("true", LlmOutputPolicy.normalize(boolean, "true"))
        listOf("Yes", "y", "sì", "yes.", "nope", "maybe", "1").forEach {
            assertNull(LlmOutputPolicy.normalize(boolean, it), it)
        }
    }

    @Test
    fun `an invalid shape never accepts anything`() {
        assertNull(LlmOutputPolicy.normalize(OutputSpec(OutputKind.NUMBER, min = 1), "1"))
        assertNull(LlmOutputPolicy.normalize(OutputSpec(OutputKind.CHOICE, choices = listOf("a")), "a"))
    }

    @Test
    fun `the instruction names only the approved answers`() {
        assertEquals(
            "Answer with exactly one of these labels and nothing else: urgent, normal, low-1.",
            LlmOutputPolicy.instruction(choice),
        )
        assertEquals("Answer with exactly one integer between -2 and 10 and nothing else.", LlmOutputPolicy.instruction(number))
        assertEquals("Answer with exactly yes or no and nothing else.", LlmOutputPolicy.instruction(boolean))
        assertEquals(VarType.TEXT, LlmOutputPolicy.varType(choice))
        assertEquals(VarType.NUMBER, LlmOutputPolicy.varType(number))
        assertEquals(VarType.BOOLEAN, LlmOutputPolicy.varType(boolean))
    }

    @Test
    fun `ask and confirm bounds`() {
        assertTrue(AskPolicy.validChoices(listOf("Sì", "No")))
        assertFalse(AskPolicy.validChoices(listOf("Sì", "Sì")))
        assertFalse(AskPolicy.validChoice("a​b"))
        assertTrue(AskPolicy.validResolvedPrompt("x".repeat(120)))
        assertFalse(AskPolicy.validResolvedPrompt("x".repeat(121)))
        assertFalse(AskPolicy.validResolvedPrompt("a\nb"))
        assertTrue(ConfirmPolicy.validTimeout(30_000))
        assertFalse(ConfirmPolicy.validTimeout(3_600_001))
        assertTrue(ConfirmPolicy.supportsTrigger(Trigger.Manual))
        assertFalse(ConfirmPolicy.supportsTrigger(Trigger.Immediate))
    }
}
