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
        val power = """
            POWER MANAGER (dumpsys power)
              mWakefulness=Awake
              Display Power: state=ON
        """.trimIndent()
        val shell = FakeStateShell(
            outputs = mapOf(
                command("settings", "get", "global", "mode_ringer") to "1",
                command("settings", "get", "global", "wifi_on") to "2",
                command("settings", "get", "global", "bluetooth_on") to "2",
                command("settings", "get", "global", "zen_mode") to "1",
                command("settings", "get", "global", "airplane_mode_on") to "0",
                command("dumpsys", "battery") to battery,
                command("dumpsys", "power") to power,
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
                StateKeys.SCREEN to "on",
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
    fun `screen parser maps wakefulness and display power fallback to on off`() {
        assertEquals("on", StateReader.parseScreen("mWakefulness=Awake\nDisplay Power: state=ON"))
        assertEquals("off", StateReader.parseScreen("  mWakefulness=Asleep"))
        assertEquals("off", StateReader.parseScreen("mWakefulness=Dozing"))
        // Fallback su Display Power quando manca il campo wakefulness.
        assertEquals("on", StateReader.parseScreen("Display Power: state=ON"))
        assertEquals("off", StateReader.parseScreen("Display Power: state=OFF"))
        // Nessun marcatore riconoscibile -> null (chiave assente, fail-closed).
        assertNull(StateReader.parseScreen("random unrelated dump"))
    }

    @Test
    fun `read screen issues dumpsys power and normalizes to on`() = runTest {
        val shell = FakeStateShell(
            outputs = mapOf(
                command("dumpsys", "power") to "mWakefulness=Awake\nDisplay Power: state=ON",
            ),
        )

        val state = StateReader(shell).read(setOf(StateKeys.SCREEN))

        assertEquals(mapOf(StateKeys.SCREEN to "on"), state.values)
        assertEquals(listOf(command("dumpsys", "power")), shell.calls)
    }

    @Test
    fun `split screen foreground app is the focused activity and not the first one listed`() {
        // Due task affiancati: il primo elencato (mail) non ha il focus, il secondo (notes) si'.
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            Display #0 (activities from top to bottom):
              * Task{a1 #21 type=standard A=10001:com.example.mail U=0 visible=true mode=multi-window sz=1}
                mBounds=Rect(0, 0 - 1080, 1200)
                topResumedActivity=ActivityRecord{b2 u0 com.example.mail/.InboxActivity t21}
                  * Hist  #0: ActivityRecord{b2 u0 com.example.mail/.InboxActivity t21}
              * Task{c3 #22 type=standard A=10002:com.example.notes U=0 visible=true mode=multi-window sz=1}
                mBounds=Rect(0, 1200 - 1080, 2400)
                topResumedActivity=ActivityRecord{d4 u0 com.example.notes/.EditorActivity t22}
                  * Hist  #0: ActivityRecord{d4 u0 com.example.notes/.EditorActivity t22}

              Resumed activities in task display areas (from top to bottom):
                Resumed: ActivityRecord{d4 u0 com.example.notes/.EditorActivity t22}
        """.trimIndent()

        assertEquals("com.example.notes", StateReader.parseForegroundPackage(dump))
    }

    @Test
    fun `foreground app ignores resumed activities of other displays`() {
        // Il display esterno (#2) e' in cima e ha il suo Resumed: conta solo quello di #0.
        val dump = """
            Display #2 (activities from top to bottom):
              * Task{e5 #31 type=standard A=10003:com.example.video U=0 visible=true mode=fullscreen sz=1}
                topResumedActivity=ActivityRecord{f6 u0 com.example.video/.PlayerActivity t31}
            Display #0 (activities from top to bottom):
              * Task{a1 #21 type=standard A=10001:com.example.mail U=0 visible=true mode=fullscreen sz=1}
                topResumedActivity=ActivityRecord{b2 u0 com.example.mail/.InboxActivity t21}

              Resumed activities in task display areas (from top to bottom):
                Resumed: ActivityRecord{f6 u0 com.example.video/.PlayerActivity t31}
                Resumed: ActivityRecord{b2 u0 com.example.mail/.InboxActivity t21}
        """.trimIndent()

        assertEquals("com.example.mail", StateReader.parseForegroundPackage(dump))
    }

    @Test
    fun `foreground app falls back to the first resumed activity of the default display`() {
        val dump = """
            Display #1 (activities from top to bottom):
              topResumedActivity=ActivityRecord{f6 u0 com.example.video/.PlayerActivity t31}
            Display #0 (activities from top to bottom):
              topResumedActivity=ActivityRecord{b2 u0 com.example.mail/.InboxActivity t21}
              topResumedActivity=ActivityRecord{d4 u0 com.example.notes/.EditorActivity t22}
        """.trimIndent()

        assertEquals("com.example.mail", StateReader.parseForegroundPackage(dump))
    }

    @Test
    fun `foreground app is unknown when the default display has no resumed activity`() {
        val dump = """
            Display #1 (activities from top to bottom):
              topResumedActivity=ActivityRecord{f6 u0 com.example.video/.PlayerActivity t31}
            Display #0 (activities from top to bottom):
              * Task{a1 #21 type=home U=0 visible=false mode=fullscreen sz=1}
        """.trimIndent()

        assertNull(StateReader.parseForegroundPackage(dump))
    }

    @Test
    fun `resumed lines after the display blocks are not attributed to the last display`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a1 #21 type=standard A=10001:com.example.mail U=0 visible=true sz=1}
            ActivityTaskSupervisor state:
              topResumedActivity=ActivityRecord{b2 u0 com.example.stale/.OldActivity t9}
        """.trimIndent()

        assertNull(StateReader.parseForegroundPackage(dump))
    }

    @Test
    fun `screen found before the output limit is used and a cut line is dropped`() = runTest {
        val power = ShellResult(
            exitCode = 0,
            stdout = (
                "Power Manager State:\n  mWakefulness=Awake\n  mWakefulnessChanging=false\n  mHalAutoSusp"
                ).toByteArray(),
            truncated = true,
        )
        val shell = FakeStateShell(results = mapOf(command("dumpsys", "power") to power))

        val state = StateReader(shell).read(setOf(StateKeys.SCREEN))

        assertEquals(mapOf(StateKeys.SCREEN to "on"), state.values)
    }

    @Test
    fun `value cut in the middle by the output limit stays unknown`() = runTest {
        val battery = ShellResult(
            exitCode = 0,
            stdout = "  status: 2\n  health: 2\n  level: 8".toByteArray(),
            truncated = true,
        )
        val shell = FakeStateShell(results = mapOf(command("dumpsys", "battery") to battery))

        val state = StateReader(shell).read(setOf(StateKeys.BATTERY, StateKeys.CHARGING))

        // Il livello e' tagliato a meta' ("8" di "85"): ignoto. Lo stato di carica era gia' stato letto.
        assertEquals(mapOf(StateKeys.CHARGING to "true"), state.values)
    }

    @Test
    fun `screen cut before the field stays unknown`() = runTest {
        val power = ShellResult(
            exitCode = 0,
            stdout = "Power Manager State:\n  mSuspendBlockers=\n".toByteArray(),
            truncated = true,
        )
        val shell = FakeStateShell(results = mapOf(command("dumpsys", "power") to power))

        assertEquals(emptyMap(), StateReader(shell).read(setOf(StateKeys.SCREEN)).values)
    }

    @Test
    fun `foreground app found before the limit survives a truncated activities dump`() = runTest {
        val dump = ShellResult(
            exitCode = 0,
            stdout = (
                "Display #0 (activities from top to bottom):\n" +
                    "  * Task{a1 #21 type=standard A=10001:com.example.mail U=0 visible=true sz=1}\n" +
                    "    topResumedActivity=ActivityRecord{b2 u0 com.example.mail/.InboxActivity t21}\n" +
                    "      * Hist  #0: ActivityRecord{b2 u0 com.example.mail/.Inb"
                ).toByteArray(),
            truncated = true,
        )
        val shell = FakeStateShell(results = mapOf(command("dumpsys", "activity", "activities") to dump))

        val state = StateReader(shell).read(emptySet(), includeForegroundApp = true)

        assertEquals("com.example.mail", state.foregroundApp)
    }

    @Test
    fun `truncated or timed out scalar settings are still discarded`() = runTest {
        val truncated = ShellResult(0, stdout = "1".toByteArray(), truncated = true)
        val timedOut = ShellResult(-1, stdout = "1".toByteArray(), timedOut = true)
        val shell = FakeStateShell(
            results = mapOf(
                command("settings", "get", "global", "wifi_on") to truncated,
                command("dumpsys", "power") to ShellResult(
                    -1,
                    stdout = "  mWakefulness=Awake\n".toByteArray(),
                    timedOut = true,
                ),
                command("settings", "get", "global", "zen_mode") to timedOut,
            ),
        )

        val state = StateReader(shell).read(setOf(StateKeys.WIFI, StateKeys.SCREEN, StateKeys.DND))

        assertEquals(emptyMap(), state.values)
    }

    @Test
    fun `reader never converts coroutine cancellation into a missing value`() = runTest {
        val shell = object : PrivilegedShell {
            override suspend fun run(
                command: List<String>,
                priority: Int,
                timeoutMillis: Long,
                maxOutputBytes: Int,
                executionId: String?,
            ): ShellResult = throw CancellationException("cancelled")

            override suspend fun runToFile(
                command: List<String>,
                destination: File,
                priority: Int,
                timeoutMillis: Long,
                maxOutputBytes: Int,
                executionId: String?,
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
    private val outputs: Map<List<String>, String> = emptyMap(),
    private val failing: Set<List<String>> = emptySet(),
    private val results: Map<List<String>, ShellResult> = emptyMap(),
) : PrivilegedShell {
    val calls = mutableListOf<List<String>>()

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        calls += command
        if (command in failing) throw IllegalStateException("transport")
        results[command]?.let { return it }
        return outputs[command]?.let { ShellResult(0, stdout = it.toByteArray()) } ?: ShellResult(1)
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = error("non usato")
}
