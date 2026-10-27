package dev.argus.shizuku

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivilegedBackendTest {
    private val adb = ShizukuIdentity(2000)
    private val root = ShizukuIdentity(0)

    @Test
    fun `no privileges at all`() {
        for (status in ShizukuGatewayStatus.entries.filter { it != ShizukuGatewayStatus.AUTHORIZED }) {
            assertEquals(
                PrivilegedBackend.NONE,
                PrivilegedBackend.resolve(status, null, sui = false, rootActive = false),
                "status $status",
            )
        }
        assertFalse(PrivilegedBackend.NONE.available)
    }

    @Test
    fun `shizuku authorized started from adb`() {
        val backend = PrivilegedBackend.resolve(ShizukuGatewayStatus.AUTHORIZED, adb, sui = false, rootActive = false)
        assertEquals(PrivilegedBackend.SHIZUKU_SHELL, backend)
        assertTrue(backend.available)
    }

    @Test
    fun `shizuku authorized started as root`() {
        assertEquals(
            PrivilegedBackend.SHIZUKU_ROOT,
            PrivilegedBackend.resolve(ShizukuGatewayStatus.AUTHORIZED, root, sui = false, rootActive = false),
        )
    }

    @Test
    fun `shizuku authorized with unknown identity`() {
        assertEquals(
            PrivilegedBackend.SHIZUKU,
            PrivilegedBackend.resolve(ShizukuGatewayStatus.AUTHORIZED, null, sui = false, rootActive = false),
        )
    }

    @Test
    fun `sui wins over the identity and is shown as sui`() {
        assertEquals(
            PrivilegedBackend.SUI,
            PrivilegedBackend.resolve(ShizukuGatewayStatus.AUTHORIZED, root, sui = true, rootActive = false),
        )
        assertEquals(
            PrivilegedBackend.SUI,
            PrivilegedBackend.resolve(ShizukuGatewayStatus.AUTHORIZED, null, sui = true, rootActive = true),
        )
    }

    @Test
    fun `root su counts only when shizuku is not authorized`() {
        assertEquals(
            PrivilegedBackend.ROOT,
            PrivilegedBackend.resolve(ShizukuGatewayStatus.INSTALLED_NOT_RUNNING, null, sui = false, rootActive = true),
        )
        assertEquals(
            PrivilegedBackend.ROOT,
            PrivilegedBackend.resolve(ShizukuGatewayStatus.NOT_INSTALLED, null, sui = false, rootActive = true),
        )
        assertEquals(
            PrivilegedBackend.SHIZUKU_SHELL,
            PrivilegedBackend.resolve(ShizukuGatewayStatus.AUTHORIZED, adb, sui = false, rootActive = true),
        )
    }

    // --- RoutedPrivilegedShell ---

    private class Named(private val name: String, private val log: MutableList<String>) : PrivilegedShell {
        override suspend fun run(
            command: List<String>,
            priority: Int,
            timeoutMillis: Long,
            maxOutputBytes: Int,
            executionId: String?,
        ): ShellResult {
            log += name
            return ShellResult(0, stdout = name.toByteArray())
        }

        override suspend fun runToFile(
            command: List<String>,
            destination: File,
            priority: Int,
            timeoutMillis: Long,
            maxOutputBytes: Int,
            executionId: String?,
        ): ShellResult = run(command)
    }

    private fun route(shizukuAvailable: Boolean, rootActive: Boolean): String = runBlocking {
        val log = mutableListOf<String>()
        val routed = RoutedPrivilegedShell(
            Named("shizuku", log),
            Named("root", log),
            shizukuAvailable = { shizukuAvailable },
            rootActive = { rootActive },
        )
        routed.run(listOf("id")).stdoutText
    }

    @Test
    fun `shizuku wins whenever it is available`() {
        assertEquals("shizuku", route(shizukuAvailable = true, rootActive = false))
        assertEquals("shizuku", route(shizukuAvailable = true, rootActive = true))
    }

    @Test
    fun `root is used when shizuku is not available and root is active`() {
        assertEquals("root", route(shizukuAvailable = false, rootActive = true))
    }

    @Test
    fun `without either the shizuku shell answers so the usual error code surfaces`() {
        assertEquals("shizuku", route(shizukuAvailable = false, rootActive = false))
    }

    @Test
    fun `a failing availability check never reaches root`() {
        val log = mutableListOf<String>()
        val routed = RoutedPrivilegedShell(
            Named("shizuku", log),
            Named("root", log),
            shizukuAvailable = { error("boom") },
            rootActive = { false },
        )
        assertEquals("shizuku", runBlocking { routed.run(listOf("id")).stdoutText })
    }
}
