package dev.argus.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemHealthLayoutTest {
    private fun rows(vararg items: String) =
        SystemHealthLayout.rows(items.toList()) { it.startsWith("W") }

    @Test
    fun `wide items stand alone and the others pair up`() {
        assertEquals(
            listOf(listOf("Wa"), listOf("Wb"), listOf("a", "b"), listOf("c", "d")),
            rows("Wa", "Wb", "a", "b", "c", "d"),
        )
    }

    @Test
    fun `a lone narrow item before a wide one stays alone`() {
        assertEquals(listOf(listOf("a"), listOf("Wb"), listOf("c")), rows("a", "Wb", "c"))
    }

    @Test
    fun `no items make no rows and order is kept`() {
        assertEquals(emptyList(), rows())
        assertEquals(listOf("x", "y", "z"), rows("x", "y", "z").flatten())
    }

    @Test
    fun `a problem or a long text takes the full width`() {
        assertTrue(SystemHealthLayout.isWide(needsFix = true, subtitle = "ok"))
        assertTrue(SystemHealthLayout.isWide(needsFix = false, subtitle = "x".repeat(SystemHealthLayout.WIDE_SUBTITLE_CHARS + 1)))
        assertFalse(SystemHealthLayout.isWide(needsFix = false, subtitle = "consentite"))
        assertFalse(SystemHealthLayout.isWide(needsFix = false, subtitle = "x".repeat(SystemHealthLayout.WIDE_SUBTITLE_CHARS)))
    }
}
