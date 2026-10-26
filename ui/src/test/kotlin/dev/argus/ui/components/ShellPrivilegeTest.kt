package dev.argus.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D10: l'etichetta dei comandi shell dipende da chi esegue davvero. `null` (servizio non ancora
 * collegato) non deve mai passare per «shell, UID 2000».
 */
class ShellPrivilegeTest {
    @Test
    fun `an unknown identity is not presented as shell`() {
        assertEquals(ShellPrivilege.UNKNOWN, shellPrivilegeOf(null))
    }

    @Test
    fun `a service that is not root is shell`() {
        assertEquals(ShellPrivilege.SHELL, shellPrivilegeOf(false))
    }

    @Test
    fun `a service running as root is root`() {
        assertEquals(ShellPrivilege.ROOT, shellPrivilegeOf(true))
    }
}
