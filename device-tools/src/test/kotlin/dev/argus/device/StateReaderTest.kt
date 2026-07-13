package dev.argus.device

import dev.argus.engine.model.StateKeys
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StateReaderTest {
    @Test
    fun `read executes only commands needed by requested keys`() = runTest {
        val shell = FakeStateShell(
            outputs = mapOf(
                command("settings", "get", "global", "wifi_on") to "1",
                command("settings", "get", "global", "zen_mode") to "2",
            ),
        )

        val state = StateReader(shell).read(setOf(StateKeys.WIFI, StateKeys.DND))

        assertEquals(mapOf(StateKeys.WIFI to "on", StateKeys.DND to "total"), state.values)
        assertEquals(
            listOf(
                command("settings", "get", "global", "wifi_on"),
                command("settings", "get", "global", "zen_mode"),
            ),
            shell.calls,
        )
        assertNull(state.foregroundApp)
    }

    @Test
    fun `all registered values and foreground package are normalized`() = runTest {
        val battery = """
            AC powered: false
            USB powered: true
            status: 2
            level: 87
        """.trimIndent()
        val activities = """
            ACTIVITY MANAGER ACTIVITIES
              topResumedActivity=ActivityRecord{123 u0 com.example.foreground/.MainActivity t7}
        """.trimIndent()
        val shell = FakeStateShell(
            outputs = mapOf(
                command("settings", "get", "global", "mode_ringer") to "1",
                command("settings", "get", "global", "wifi_on") to "2",
                command("settings", "get", "global", "bluetooth_on") to "2",
                command("settings", "get", "global", "zen_mode") to "1",
                command("settings", "get", "global", "airplane_mode_on") to "0",
                command("dumpsys", "battery") to battery,
                command("dumpsys", "activity", "activities") to activities,
            ),
        )

        val state = StateReader(shell).read(StateKeys.ALL.keys, includeForegroundApp = true)

        assertEquals(
            mapOf(
                StateKeys.RINGER to "vibrate",
                StateKeys.WIFI to "on",
                StateKeys.BLUETOOTH to "on",
                StateKeys.DND to "priority",
                StateKeys.AIRPLANE to "off",
                StateKeys.BATTERY to "87",
                StateKeys.CHARGING to "true",
            ),
            state.values,
        )
        assertEquals("com.example.foreground", state.foregroundApp)
        assertEquals(1, shell.calls.count { it == command("dumpsys", "battery") })
    }

    @Test
    fun `unknown or failed reads are omitted without hiding cancellation policy`() = runTest {
        val wifi = command("settings", "get", "global", "wifi_on")
        val shell = FakeStateShell(
            outputs = mapOf(
                command("settings", "get", "global", "zen_mode") to "3",
                command("settings", "get", "global", "airplane_mode_on") to "1",
                command("dumpsys", "battery") to "status: 1\nlevel: 120",
            ),
            failing = setOf(wifi),
        )

        val state = StateReader(shell).read(
            setOf(StateKeys.WIFI, StateKeys.DND, StateKeys.AIRPLANE, StateKeys.BATTERY),
        )

        assertEquals(mapOf(StateKeys.AIRPLANE to "on"), state.values)
        assertFailsWith<IllegalArgumentException> { StateReader(shell).read(setOf("invented")) }
    }

    @Test
    fun `pure parsers cover airplane overrides battery and foreground variants`() {
        assertEquals("off", StateReader.parseWifi("3"))
        assertEquals("on", StateReader.parseWifi("2"))
        assertEquals("total", StateReader.parseDnd("2"))
        assertNull(StateReader.parseDnd("3"))
        assertEquals(
            "com.example",
            StateReader.parseForegroundPackage(
                "mResumedActivity: ActivityRecord{abc u0 com.example/com.example.Main t1}",
            ),
        )
        assertEquals(
            "com.teslacoilsw.launcher",
            StateReader.parseForegroundPackage(
                "ResumedActivity: ActivityRecord{56055641 u0 " +
                    "com.teslacoilsw.launcher/.NovaLauncher t17329}",
            ),
        )
        assertEquals(
            StateReader.BatterySnapshot(level = 42, charging = false),
            StateReader.parseBattery("level: 42\nstatus: 3"),
        )
    }

    @Test
    fun `reader never converts coroutine cancellation into a missing value`() = runTest {
        val shell = object : PrivilegedShell {
            override suspend fun run(
                command: List<String>,
                priority: Int,
                timeoutMillis: Long,
                maxOutputBytes: Int,
            ): ShellResult = throw CancellationException("cancelled")

            override suspend fun runToFile(
                command: List<String>,
                destination: File,
                priority: Int,
                timeoutMillis: Long,
                maxOutputBytes: Int,
            ): ShellResult = error("non usato")
        }

        assertFailsWith<CancellationException> {
            StateReader(shell).read(setOf(StateKeys.WIFI))
        }
    }
}

private fun command(binary: String, vararg arguments: String): List<String> =
    listOf("/system/bin/$binary", *arguments)

private class FakeStateShell(
    private val outputs: Map<List<String>, String>,
    private val failing: Set<List<String>> = emptySet(),
) : PrivilegedShell {
    val calls = mutableListOf<List<String>>()

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): ShellResult {
        calls += command
        if (command in failing) throw IllegalStateException("transport")
        return outputs[command]?.let { ShellResult(0, stdout = it.toByteArray()) } ?: ShellResult(1)
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): ShellResult = error("non usato")
}
