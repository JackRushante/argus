package dev.argus.device

import dev.argus.engine.model.DndMode
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.runtime.ExecutionId
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DeviceToolsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `typed actions map to direct argv without a command shell`() = runTest {
        val shell = RecordingShell()
        val tools = DeviceTools(shell, temporaryDirectory.toFile())

        tools.setWifi(true, EXECUTION_ID, PRIORITY)
        tools.setWifi(false, EXECUTION_ID, PRIORITY)
        tools.setBluetooth(true, EXECUTION_ID, PRIORITY)
        tools.setDnd(DndMode.OFF, EXECUTION_ID, PRIORITY)
        tools.setDnd(DndMode.PRIORITY, EXECUTION_ID, PRIORITY)
        tools.setDnd(DndMode.TOTAL, EXECUTION_ID, PRIORITY)
        tools.setRinger(RingerMode.VIBRATE, EXECUTION_ID, PRIORITY)
        tools.launchApp("com.example.app", EXECUTION_ID, PRIORITY)
        tools.openUrl("https://example.com/a?q=uno%20due", EXECUTION_ID, PRIORITY)
        tools.tap(123, 456, EXECUTION_ID, PRIORITY)
        tools.inputText("ciao mondo", EXECUTION_ID, PRIORITY)
        tools.writeSetting(SettingNamespace.SECURE, "adb_enabled", "1", EXECUTION_ID, PRIORITY)

        assertEquals(
            listOf(
                listOf("/system/bin/svc", "wifi", "enable"),
                listOf("/system/bin/svc", "wifi", "disable"),
                listOf("/system/bin/svc", "bluetooth", "enable"),
                listOf("/system/bin/cmd", "notification", "set_dnd", "all"),
                listOf("/system/bin/cmd", "notification", "set_dnd", "priority"),
                listOf("/system/bin/cmd", "notification", "set_dnd", "none"),
                listOf("/system/bin/cmd", "audio", "set-ringer-mode", "VIBRATE"),
                listOf(
                    "/system/bin/am",
                    "start",
                    "--user",
                    "current",
                    "-a",
                    "android.intent.action.MAIN",
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "-p",
                    "com.example.app",
                ),
                listOf(
                    "/system/bin/am",
                    "start",
                    "--user",
                    "current",
                    "-a",
                    "android.intent.action.VIEW",
                    "-d",
                    "https://example.com/a?q=uno%20due",
                ),
                listOf("/system/bin/input", "tap", "123", "456"),
                listOf("/system/bin/input", "text", "ciao mondo"),
                listOf("/system/bin/settings", "put", "secure", "adb_enabled", "1"),
            ),
            shell.calls.map { it.command },
        )
        assertEquals(setOf(EXECUTION_ID.value), shell.calls.mapNotNull { it.executionId }.toSet())
        assertEquals(setOf(PRIORITY), shell.calls.map { it.priority }.toSet())
        assertFalse(shell.calls.any { it.command.firstOrNull() in setOf("sh", "/system/bin/sh") })
    }

    @Test
    fun `invalid external values never reach privileged transport`() = runTest {
        val shell = RecordingShell()
        val tools = DeviceTools(shell, temporaryDirectory.toFile())

        assertFailsWith<IllegalArgumentException> { tools.launchApp("bad package", EXECUTION_ID) }
        assertFailsWith<IllegalArgumentException> {
            tools.openUrl("javascript:alert(1)", EXECUTION_ID)
        }
        assertFailsWith<IllegalArgumentException> { tools.tap(-1, 2, EXECUTION_ID) }
        assertFailsWith<IllegalArgumentException> { tools.inputText("riga\nnuova", EXECUTION_ID) }
        assertFailsWith<IllegalArgumentException> { tools.inputText("letterale %s", EXECUTION_ID) }
        assertFailsWith<IllegalArgumentException> {
            tools.writeSetting(SettingNamespace.SECURE, "bad key", "1", EXECUTION_ID)
        }
        assertFailsWith<IllegalArgumentException> {
            tools.writeSetting(SettingNamespace.SECURE, "adb_enabled", "x\nnewline", EXECUTION_ID)
        }
        assertEquals(emptyList(), shell.calls)
    }

    @Test
    fun `capture and dump use capped file transport validate payload and clean temp files`() = runTest {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3,
        )
        val xml = """
            prefix
            <?xml version="1.0"?><displays><display><window><hierarchy><node /></hierarchy></window></display></displays>
            suffix
        """.trimIndent()
        val shell = RecordingShell(fileHandler = { command, destination ->
            destination.writeBytes(
                if (command.first() == "/system/bin/screencap") png else xml.toByteArray(),
            )
            ShellResult(0)
        })
        val tools = DeviceTools(shell, temporaryDirectory.toFile())

        assertContentEquals(png, tools.capture())
        assertEquals(
            "<?xml version=\"1.0\"?><displays><display><window><hierarchy><node /></hierarchy>" +
                "</window></display></displays>",
            tools.dumpUi(),
        )
        assertEquals(emptyList(), temporaryDirectory.listDirectoryEntries())
        assertEquals(listOf("/system/bin/screencap", "-p"), shell.calls[0].command)
        val remotePath = shell.calls[1].command.last()
        assertEquals("/system/bin/uiautomator", shell.calls[1].command.first())
        assertEquals(
            listOf("dump", "--compressed", "--windows"),
            shell.calls[1].command.drop(1).dropLast(1),
        )
        assertEquals(listOf("/system/bin/cat", remotePath), shell.calls[2].command)
        assertEquals(listOf("/system/bin/rm", "-f", remotePath), shell.calls[3].command)
        assertFalse(shell.calls.any { "/dev/tty" in it.command })
    }

    @Test
    fun `tool failures expose a stable code and not privileged stderr`() = runTest {
        val shell = RecordingShell(result = ShellResult(1, stderr = "sensitive".toByteArray()))
        val tools = DeviceTools(shell, temporaryDirectory.toFile())

        val error = assertFailsWith<DeviceToolException> { tools.setWifi(true, EXECUTION_ID) }

        assertEquals("set_wifi_failed", error.code)
        assertFalse(error.message.orEmpty().contains("sensitive"))
    }

    @Test
    fun `cancelled UI dump still removes its remote temporary file`() = runTest {
        val shell = CancellingDumpShell()
        val tools = DeviceTools(shell, temporaryDirectory.toFile())

        assertFailsWith<CancellationException> { tools.dumpUi() }

        val dumpPath = shell.calls.single { it.first() == "/system/bin/uiautomator" }.last()
        assertEquals(
            listOf("/system/bin/rm", "-f", dumpPath),
            shell.calls.single { it.first() == "/system/bin/rm" },
        )
    }

    @Test
    fun `UI XML extraction accepts legacy hierarchy and rejects incomplete dumps`() {
        assertEquals(
            "<hierarchy><node /></hierarchy>",
            extractUiXmlPayload("noise<hierarchy><node /></hierarchy>tail"),
        )
        assertEquals(null, extractUiXmlPayload("<displays><display /></display"))
        assertEquals(null, extractUiXmlPayload("<displays><display /></displays>"))
    }
}

private val EXECUTION_ID = ExecutionId("execution-device-tools")
private const val PRIORITY = 9

private data class ShellCall(
    val command: List<String>,
    val destination: File?,
    val executionId: String?,
    val priority: Int,
)

private class RecordingShell(
    private val fileHandler: ((List<String>, File) -> ShellResult)? = null,
    private val result: ShellResult = ShellResult(0),
) : PrivilegedShell {
    val calls = mutableListOf<ShellCall>()

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        calls += ShellCall(command, null, executionId, priority)
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
        calls += ShellCall(command, destination, executionId, priority)
        return fileHandler?.invoke(command, destination) ?: result
    }
}

private class CancellingDumpShell : PrivilegedShell {
    val calls = mutableListOf<List<String>>()

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        calls += command
        return ShellResult(0)
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        calls += command
        throw CancellationException("cancelled")
    }
}
