package dev.argus.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** L'aggancio di debug risponde solo alla shell di adb (2000) e a root (0): ogni altro uid è rifiutato. */
class E2eCallerPolicyTest {
    @Test
    fun `shell and root are allowed`() {
        assertTrue(E2eCallerPolicy.allows(2000))
        assertTrue(E2eCallerPolicy.allows(0))
        assertEquals(2000, E2eCallerPolicy.SHELL_UID)
        assertEquals(0, E2eCallerPolicy.ROOT_UID)
    }

    @Test
    fun `system radio apps and the app itself are rejected`() {
        val rejected = listOf(
            1000, // system
            1001, // radio
            1027, // nfc
            1073, // network stack
            1999,
            2001, // il primo uid dopo la shell
            10_000, // la prima app
            10_123, // un'app qualsiasi
            19_999,
            99_999, // un uid di un altro utente
            1_010_123,
        )
        rejected.forEach { assertFalse(E2eCallerPolicy.allows(it), "uid $it deve essere rifiutato") }
    }

    @Test
    fun `invalid uids are rejected`() {
        assertFalse(E2eCallerPolicy.allows(-1))
        assertFalse(E2eCallerPolicy.allows(Int.MIN_VALUE))
        assertFalse(E2eCallerPolicy.allows(Int.MAX_VALUE))
    }

    @Test
    fun `only exactly two uids are allowed in the whole app uid range`() {
        val allowed = (0..20_000).filter(E2eCallerPolicy::allows)
        assertEquals(listOf(0, 2000), allowed)
    }
}
