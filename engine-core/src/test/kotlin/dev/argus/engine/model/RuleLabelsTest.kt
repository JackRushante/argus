package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Etichette delle regole (0.7.2): da 1 a 24 caratteri dopo il trim, confrontate senza maiuscole, al più 5 per
 * regola e 20 distinte in tutto. Sono organizzazione: nessuna di queste regole tocca la regola eseguibile.
 */
class RuleLabelsTest {
    @Test
    fun `a label is trimmed and its inner spaces are collapsed`() {
        assertEquals("Casa", RuleLabels.clean("  Casa  "))
        assertEquals("Casa al mare", RuleLabels.clean("Casa \t al  mare"))
    }

    @Test
    fun `an empty or blank label is refused`() {
        assertNull(RuleLabels.clean(""))
        assertNull(RuleLabels.clean("   "))
        assertNull(RuleLabels.clean(" \t"))
    }

    @Test
    fun `a label has at most 24 characters, an emoji counting as one`() {
        assertEquals("a".repeat(24), RuleLabels.clean("a".repeat(24)))
        assertNull(RuleLabels.clean("a".repeat(25)))
        val emoji = "🏠" // casa, una coppia surrogata
        assertEquals("Casa $emoji", RuleLabels.clean("Casa $emoji"))
        assertEquals(emoji.repeat(24), RuleLabels.clean(emoji.repeat(24)))
        assertNull(RuleLabels.clean(emoji.repeat(25)))
    }

    @Test
    fun `line breaks become spaces, control and invisible direction characters are refused`() {
        assertEquals("Casa Lavoro", RuleLabels.clean("Casa\nLavoro"))
        assertEquals("Casa", RuleLabels.clean("Casa "))
        assertNull(RuleLabels.clean("Ca\u0000sa"))
        assertNull(RuleLabels.clean("Ca\u0007sa"))
        assertNull(RuleLabels.clean("‮asaC"))
        assertNull(RuleLabels.clean("Ca​sa"))
        assertNull(RuleLabels.clean("Casa\uD800"))
    }

    @Test
    fun `labels are compared without case`() {
        assertEquals(RuleLabels.key("Casa"), RuleLabels.key("casa"))
        assertEquals(RuleLabels.key("CASA "), RuleLabels.key(" casa"))
        assertEquals(RuleLabels.key("Casa  al mare"), RuleLabels.key("casa al mare"))
        assertEquals("casa", RuleLabels.key("Casa"))
    }

    @Test
    fun `the labels of a rule drop invalid ones and case duplicates, keeping the first spelling`() {
        assertEquals(
            listOf("Casa", "Lavoro"),
            RuleLabels.sanitize(listOf(" Casa", "", "casa", "Lavoro", "x".repeat(30), "LAVORO")),
        )
    }

    @Test
    fun `a rule keeps at most five labels`() {
        val many = (1..8).map { "L$it" }
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5"), RuleLabels.sanitize(many))
        assertEquals(5, RuleLabels.MAX_PER_RULE)
    }

    @Test
    fun `labels already in use are always admitted and new ones until twenty distinct`() {
        val inUse = (1..19).map { RuleLabels.key("L$it") }.toSet()
        assertEquals(
            listOf("l1", "Nuova"),
            RuleLabels.admit(listOf("l1", "Nuova", "Altra"), inUse),
        )
        val full = inUse + "nuova"
        assertEquals(listOf("L2"), RuleLabels.admit(listOf("Terza", "L2"), full))
        assertEquals(20, RuleLabels.MAX_DISTINCT)
    }

    @Test
    fun `the same new label twice is admitted once`() {
        assertEquals(listOf("Casa"), RuleLabels.admit(listOf("Casa", "casa"), emptySet()))
    }
}
