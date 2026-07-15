package dev.argus.engine.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeExtractionRegexTest {
    @Test
    fun `capture group extracts only the OTP`() {
        val pattern = SafeExtractionRegex.DEFAULT_OTP_PATTERN

        assertEquals(
            SafeExtractionRegex.Result.Match("345798"),
            SafeExtractionRegex.extract(pattern, "Il tuo codice e 345798. Non condividerlo"),
        )
        assertEquals(
            SafeExtractionRegex.Result.NoMatch,
            SafeExtractionRegex.extract(pattern, "telefono +345798"),
        )
    }

    @Test
    fun `backtracking-shaped input stays supported while unsafe syntax is rejected`() {
        assertTrue(SafeExtractionRegex.isValid("(a+)+$"))
        assertEquals(
            SafeExtractionRegex.Result.NoMatch,
            SafeExtractionRegex.extract("(a+)+$", "a".repeat(100_000) + "!"),
        )
        assertTrue(SafeExtractionRegex.isValid(SafeExtractionRegex.LEGACY_OTP_PATTERN))
        assertEquals(
            SafeExtractionRegex.Result.Match("345798"),
            SafeExtractionRegex.extract(
                SafeExtractionRegex.LEGACY_OTP_PATTERN,
                "codice 345798",
            ),
        )
        assertFalse(SafeExtractionRegex.isValid("(?<!foo)\\d+"))
        assertFalse(SafeExtractionRegex.isValid("(\\d+)\\1"))
    }
}
