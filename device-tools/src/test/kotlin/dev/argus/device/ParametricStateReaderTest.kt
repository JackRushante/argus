package dev.argus.device

import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQuery
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ParametricStateReaderTest {
    @Test
    fun `all query families execute bounded argv commands and return opaque ids`() = runTest {
        val builtin = StateQuery.Builtin(StateKeys.WIFI)
        val setting = StateQuery.Setting(SettingNamespace.SECURE, "location_mode")
        val property = StateQuery.SystemProperty("ro.build.version.sdk")
        val sysfs = StateQuery.Sysfs("/sys/class/power_supply/battery/voltage_now")
        val dumpsys = StateQuery.DumpsysField("battery", "voltage")
        val shell = QueryShell(
            outputs = mapOf(
                command("settings", "get", "global", "wifi_on") to result("1"),
                command("settings", "get", "secure", "location_mode") to result("3"),
                command("getprop", "ro.build.version.sdk") to result("36"),
                command("cat", "/sys/resolved/voltage_now") to result("4200000"),
                command("dumpsys", "battery") to result("level: 80\n  voltage: 4200\n"),
            ),
        )
        val reader = ParametricStateReader(
            shell = shell,
            sysfsResolver = { "/sys/resolved/voltage_now" },
        )

        val values = reader.read(linkedSetOf(builtin, setting, property, sysfs, dumpsys))

        assertEquals("on", values[builtin.canonicalId])
        assertEquals("3", values[setting.canonicalId])
        assertEquals("36", values[property.canonicalId])
        assertEquals("4200000", values[sysfs.canonicalId])
        assertEquals("4200", values[dumpsys.canonicalId])
        assertEquals(5, shell.calls.size)
        assertFalse(shell.calls.any { command -> command.any { it == "sh" || it == "-c" } })
        assertEquals(setOf(10_000L), shell.timeouts.toSet())
        assertEquals(setOf(64 * 1024), shell.outputCaps.toSet())
    }

    @Test
    fun `invalid parameters and sysfs escaping never reach the shell`() = runTest {
        val shell = QueryShell()
        val reader = ParametricStateReader(shell, sysfsResolver = { "/data/local/tmp/value" })
        val values = reader.read(
            setOf(
                StateQuery.Setting(SettingNamespace.GLOBAL, "bad\nkey"),
                StateQuery.SystemProperty("bad/property"),
                StateQuery.Sysfs("/proc/version"),
                StateQuery.Sysfs("/sys/class/power_supply/battery/voltage_now"),
                StateQuery.DumpsysField("battery;id", "voltage"),
            ),
        )

        assertEquals(emptyMap(), values)
        assertEquals(emptyList(), shell.calls)
    }

    @Test
    fun `missing settings and ambiguous dumpsys fields remain unavailable`() = runTest {
        val setting = StateQuery.Setting(SettingNamespace.SECURE, "fixture_missing")
        val ambiguous = StateQuery.DumpsysField("fixture", "state")
        val shell = QueryShell(
            outputs = mapOf(
                command("settings", "get", "secure", "fixture_missing") to result("null\n"),
                command("dumpsys", "fixture") to result("state: first\nstate: second\n"),
            ),
        )

        assertEquals(
            emptyMap(),
            ParametricStateReader(shell).read(setOf(setting, ambiguous)),
        )
    }

    @Test
    fun `truncated timed out failed and non scalar outputs remain unavailable`() = runTest {
        val truncated = StateQuery.SystemProperty("fixture.truncated")
        val timedOut = StateQuery.SystemProperty("fixture.timeout")
        val failed = StateQuery.SystemProperty("fixture.failed")
        val multiline = StateQuery.SystemProperty("fixture.multiline")
        val shell = QueryShell(
            outputs = mapOf(
                command("getprop", "fixture.truncated") to result("x", truncated = true),
                command("getprop", "fixture.timeout") to result("x", timedOut = true),
                command("getprop", "fixture.failed") to ShellResult(exitCode = 1),
                command("getprop", "fixture.multiline") to result("one\ntwo"),
            ),
        )

        assertEquals(
            emptyMap(),
            ParametricStateReader(shell).read(setOf(truncated, timedOut, failed, multiline)),
        )
    }

    @Test
    fun `multiple fields from one dumpsys service share one bounded process`() = runTest {
        val level = StateQuery.DumpsysField("battery", "level")
        val voltage = StateQuery.DumpsysField("battery", "voltage")
        val shell = QueryShell(
            outputs = mapOf(
                command("dumpsys", "battery") to result("level: 80\nvoltage: 4200"),
            ),
        )

        val values = ParametricStateReader(shell).read(setOf(level, voltage))

        assertEquals("80", values[level.canonicalId])
        assertEquals("4200", values[voltage.canonicalId])
        assertEquals(listOf(command("dumpsys", "battery")), shell.calls)
    }

    @Test
    fun `cancellation propagates without being converted to unknown`() = runTest {
        val query = StateQuery.SystemProperty("ro.build.version.sdk")
        val shell = QueryShell(cancel = true)

        assertFailsWith<CancellationException> {
            ParametricStateReader(shell).read(setOf(query))
        }
    }

    private companion object {
        fun command(binary: String, vararg args: String): List<String> =
            listOf("/system/bin/$binary", *args)

        fun result(
            stdout: String,
            truncated: Boolean = false,
            timedOut: Boolean = false,
        ) = ShellResult(
            exitCode = 0,
            stdout = stdout.toByteArray(),
            truncated = truncated,
            timedOut = timedOut,
        )
    }
}

private class QueryShell(
    private val outputs: Map<List<String>, ShellResult> = emptyMap(),
    private val cancel: Boolean = false,
) : PrivilegedShell {
    val calls = mutableListOf<List<String>>()
    val timeouts = mutableListOf<Long>()
    val outputCaps = mutableListOf<Int>()

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        if (cancel) throw CancellationException("cancelled")
        calls += command
        timeouts += timeoutMillis
        outputCaps += maxOutputBytes
        return outputs[command] ?: ShellResult(exitCode = 1)
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = error("not used")
}
