package dev.argus.device

import dev.argus.engine.model.AppControlOp
import dev.argus.engine.runtime.ExecutionId
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import dev.argus.shizuku.ShizukuShellException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Azioni privilegiate tipizzate 0.5.1: argv esatti, seconde barriere e codici d'errore. */
class PrivilegedActionToolsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val id = ExecutionId("execution-priv-051")
    private val youtube = "com.google.android.youtube"
    private val mic = "android.permission.RECORD_AUDIO"

    private fun tools(shell: PrivilegedShell) = PrivilegedActionTools(shell, temporaryDirectory.toFile())

    @Test
    fun `every action is one fixed argv and never a command shell`() = runTest {
        val shell = PrivRecordingShell()
        val tools = tools(shell)

        tools.appControl(youtube, AppControlOp.FORCE_STOP, id, PRIORITY)
        tools.appControl(youtube, AppControlOp.FREEZE, id, PRIORITY)
        tools.appControl(youtube, AppControlOp.UNFREEZE, id, PRIORITY)
        tools.setAirplane(true, id, PRIORITY)
        tools.setAirplane(false, id, PRIORITY)
        tools.setLocation(true, id, PRIORITY)
        tools.setLocation(false, id, PRIORITY)
        tools.setNfc(true, id, PRIORITY)
        tools.setNfc(false, id, PRIORITY)
        tools.setBatterySaver(true, id, PRIORITY)
        tools.setBatterySaver(false, id, PRIORITY)
        tools.lockScreen(id, PRIORITY)
        tools.appPermission(youtube, mic, grant = true, id, PRIORITY)
        tools.appPermission(youtube, mic, grant = false, id, PRIORITY)

        assertEquals(
            listOf(
                listOf("/system/bin/am", "force-stop", "--user", "0", youtube),
                listOf("/system/bin/pm", "disable-user", "--user", "0", youtube),
                listOf("/system/bin/pm", "enable", "--user", "0", youtube),
                listOf("/system/bin/cmd", "connectivity", "airplane-mode", "enable"),
                listOf("/system/bin/cmd", "connectivity", "airplane-mode", "disable"),
                listOf("/system/bin/cmd", "location", "set-location-enabled", "true"),
                listOf("/system/bin/cmd", "location", "set-location-enabled", "false"),
                listOf("/system/bin/svc", "nfc", "enable"),
                listOf("/system/bin/svc", "nfc", "disable"),
                listOf("/system/bin/cmd", "power", "set-mode", "1"),
                listOf("/system/bin/cmd", "power", "set-mode", "0"),
                listOf("/system/bin/input", "keyevent", "KEYCODE_SLEEP"),
                listOf("/system/bin/pm", "grant", "--user", "0", youtube, mic),
                listOf("/system/bin/pm", "revoke", "--user", "0", youtube, mic),
            ),
            shell.calls.map { it.command },
        )
        assertEquals(setOf(id.value), shell.calls.mapNotNull { it.executionId }.toSet())
        assertEquals(setOf(PRIORITY), shell.calls.map { it.priority }.toSet())
        assertFalse(shell.calls.any { it.command.firstOrNull() in setOf("sh", "/system/bin/sh") })
        assertFalse(shell.calls.any { call -> call.command.any { ' ' in it || ';' in it } })
    }

    @Test
    fun `the screenshot is the png on stdout of screencap and leaves no temporary file`() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 7, 8, 9)
        val shell = PrivRecordingShell(fileHandler = { _, destination ->
            destination.writeBytes(png)
            ShellResult(0)
        })

        assertContentEquals(png, tools(shell).screenshot())

        assertEquals(listOf(listOf("/system/bin/screencap", "-p")), shell.calls.map { it.command })
        assertEquals(emptyList(), temporaryDirectory.listDirectoryEntries())
    }

    @Test
    fun `a screenshot that is not a png is rejected`() = runTest {
        val shell = PrivRecordingShell(fileHandler = { _, destination ->
            destination.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
            ShellResult(0)
        })

        assertEquals("screen_capture_invalid", assertFailsWith<DeviceToolException> { tools(shell).screenshot() }.code)
    }

    @Test
    fun `the second barrier refuses a malformed or forbidden target before any command`() = runTest {
        val shell = PrivRecordingShell()
        val tools = tools(shell)

        listOf("", "youtube", "com.x; rm -rf /", "\${pkg}", "dev.argus", "dev.argus.debug", "com.android.systemui")
            .forEach { pkg ->
                AppControlOp.entries.forEach { op ->
                    assertFailsWith<IllegalArgumentException>("$pkg $op") { tools.appControl(pkg, op, id) }
                }
            }
        listOf("", "youtube", "dev.argus", "com.x y").forEach { pkg ->
            assertFailsWith<IllegalArgumentException>(pkg) { tools.appPermission(pkg, mic, true, id) }
        }
        listOf(
            "android.permission.WRITE_SECURE_SETTINGS", "android.permission.INTERNET", "RECORD_AUDIO", "",
            "android.permission.RECORD_AUDIO;id",
        ).forEach { permission ->
            assertFailsWith<IllegalArgumentException>(permission) { tools.appPermission(youtube, permission, false, id) }
        }
        assertEquals(emptyList(), shell.calls)
    }

    @Test
    fun `an exit code becomes the operation name with that code`() = runTest {
        val tools = tools(PrivRecordingShell(result = ShellResult(1)))

        assertEquals("set_airplane_exit_1", assertFailsWith<DeviceToolException> { tools.setAirplane(true, id) }.code)
        assertEquals("set_location_exit_1", assertFailsWith<DeviceToolException> { tools.setLocation(true, id) }.code)
        assertEquals("set_nfc_exit_1", assertFailsWith<DeviceToolException> { tools.setNfc(true, id) }.code)
        assertEquals(
            "set_battery_saver_exit_1",
            assertFailsWith<DeviceToolException> { tools.setBatterySaver(true, id) }.code,
        )
        assertEquals("lock_screen_exit_1", assertFailsWith<DeviceToolException> { tools.lockScreen(id) }.code)
        assertEquals(
            "app_control_exit_1",
            assertFailsWith<DeviceToolException> { tools.appControl(youtube, AppControlOp.FREEZE, id) }.code,
        )
    }

    @Test
    fun `a permission change pm did not accept has one readable code`() = runTest {
        listOf(1, 255).forEach { exit ->
            val tools = tools(PrivRecordingShell(result = ShellResult(exit)))
            assertEquals(
                "permission_not_applied",
                assertFailsWith<DeviceToolException> { tools.appPermission(youtube, mic, true, id) }.code,
                "exit $exit",
            )
        }
        // Timeout e errori del servizio non si fanno passare per un rifiuto di pm.
        assertEquals(
            ShizukuShellException.TIMEOUT,
            assertFailsWith<DeviceToolException> {
                tools(PrivRecordingShell(result = ShellResult(-1, timedOut = true))).appPermission(youtube, mic, true, id)
            }.code,
        )
        assertEquals(
            "app_permission_failed",
            assertFailsWith<DeviceToolException> {
                tools(PrivRecordingShell(result = ShellResult(-1, errorCode = "service_error")))
                    .appPermission(youtube, mic, true, id)
            }.code,
        )
    }

    @Test
    fun `typed Shizuku failures of the shell keep their code`() = runTest {
        listOf(
            ShizukuShellException.UNAVAILABLE,
            ShizukuShellException.UNSUPPORTED,
            ShizukuShellException.TIMEOUT,
        ).forEach { code ->
            val tools = tools(PrivThrowingShell(ShizukuShellException(code)))
            assertEquals(code, assertFailsWith<DeviceToolException> { tools.setNfc(true, id) }.code)
            assertEquals(code, assertFailsWith<DeviceToolException> { tools.screenshot() }.code)
            assertEquals(code, assertFailsWith<DeviceToolException> { tools.appPermission(youtube, mic, true, id) }.code)
        }
    }

    private companion object {
        const val PRIORITY = 4
    }
}

private data class PrivShellCall(
    val command: List<String>,
    val executionId: String?,
    val priority: Int,
)

private class PrivRecordingShell(
    private val fileHandler: ((List<String>, File) -> ShellResult)? = null,
    private val result: ShellResult = ShellResult(0),
) : PrivilegedShell {
    val calls = mutableListOf<PrivShellCall>()

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        calls += PrivShellCall(command, executionId, priority)
        return result
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        calls += PrivShellCall(command, executionId, priority)
        return fileHandler?.invoke(command, destination) ?: result
    }
}

private class PrivThrowingShell(private val error: ShizukuShellException) : PrivilegedShell {
    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = throw error

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = throw error
}
