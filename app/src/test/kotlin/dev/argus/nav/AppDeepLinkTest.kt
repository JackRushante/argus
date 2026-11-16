package dev.argus.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppDeepLinkTest {
    @Test
    fun `a rule id opens the rule detail`() {
        assertEquals(AppDeepLink.Rule("rule-1"), AppDeepLink.parse("rule-1", openSystem = false))
        assertEquals(AppDeepLink.Rule("rule-1"), AppDeepLink.parse("rule-1", openSystem = true))
    }

    @Test
    fun `the system flag opens System`() {
        assertEquals(AppDeepLink.System, AppDeepLink.parse(null, openSystem = true))
        assertEquals(AppDeepLink.System, AppDeepLink.parse(" ", openSystem = true))
    }

    @Test
    fun `nothing or an oversized id opens nothing`() {
        assertNull(AppDeepLink.parse(null, openSystem = false))
        assertNull(AppDeepLink.parse("", openSystem = false))
        assertNull(AppDeepLink.parse("x".repeat(129), openSystem = false))
    }
}
