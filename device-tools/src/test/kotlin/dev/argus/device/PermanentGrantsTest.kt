package dev.argus.device

import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import dev.argus.shizuku.ShizukuShellException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermanentGrantsTest {
    private class RecordingShell(private val reply: (List<String>) -> ShellResult) : PrivilegedShell {
        val commands = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()

        override suspend fun run(
            command: List<String>,
            priority: Int,
            timeoutMillis: Long,
            maxOutputBytes: Int,
            executionId: String?,
        ): ShellResult {
            commands += command
            timeouts += timeoutMillis
            return reply(command)
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

    @Test
    fun `the four commands are exactly the ones in the brief`() {
        val pkg = "dev.argus"
        assertEquals(
            listOf("pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS"),
            PermanentGrant.WRITE_SECURE_SETTINGS.command(pkg),
        )
        assertEquals(
            listOf("appops", "set", pkg, "WRITE_SETTINGS", "allow"),
            PermanentGrant.WRITE_SETTINGS.command(pkg),
        )
        assertEquals(
            listOf("appops", "set", pkg, "GET_USAGE_STATS", "allow"),
            PermanentGrant.USAGE_STATS.command(pkg),
        )
        assertEquals(
            listOf("cmd", "notification", "allow_dnd", pkg),
            PermanentGrant.DND_ACCESS.command(pkg),
        )
    }

    @Test
    fun `notification access is never part of the permanent grants`() {
        val everything = PermanentGrant.entries.flatMap { it.command("dev.argus") }
        assertFalse(everything.any { it.contains("listener", ignoreCase = true) })
        assertEquals(5, PermanentGrant.entries.size)
    }

    @Test
    fun `ids round trip and unknown ids are rejected`() {
        PermanentGrant.entries.forEach { assertEquals(it, PermanentGrant.fromId(it.id)) }
        assertNull(PermanentGrant.fromId("allow_listener"))
    }

    @Test
    fun `only the selected grants run, in a fixed order`() = runBlocking {
        val shell = RecordingShell { ShellResult(0) }
        val controller = PermanentGrantsController(shell, "dev.argus")

        val outcomes = controller.grant(setOf(PermanentGrant.DND_ACCESS, PermanentGrant.WRITE_SECURE_SETTINGS))

        assertEquals(
            listOf(
                PermanentGrant.WRITE_SECURE_SETTINGS.command("dev.argus"),
                PermanentGrant.DND_ACCESS.command("dev.argus"),
            ),
            shell.commands,
        )
        assertEquals(
            mapOf(
                PermanentGrant.WRITE_SECURE_SETTINGS to GrantOutcome.SENT,
                PermanentGrant.DND_ACCESS to GrantOutcome.SENT,
            ),
            outcomes,
        )
        assertTrue(shell.timeouts.all { it in 1..PrivilegedShell.MAX_TIMEOUT_MILLIS })
    }

    @Test
    fun `nothing selected runs nothing`() = runBlocking {
        val shell = RecordingShell { ShellResult(0) }
        assertTrue(PermanentGrantsController(shell, "dev.argus").grant(emptySet()).isEmpty())
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `a failing command is reported without stopping the others`() = runBlocking {
        val shell = RecordingShell { command ->
            if (command.first() == "pm") ShellResult(1, stderr = "Exception".toByteArray()) else ShellResult(0)
        }
        val outcomes = PermanentGrantsController(shell, "dev.argus").grant(PermanentGrant.entries.toSet())
        assertEquals(GrantOutcome.FAILED, outcomes[PermanentGrant.WRITE_SECURE_SETTINGS])
        assertEquals(GrantOutcome.SENT, outcomes[PermanentGrant.WRITE_SETTINGS])
        assertEquals(GrantOutcome.SENT, outcomes[PermanentGrant.USAGE_STATS])
        assertEquals(GrantOutcome.SENT, outcomes[PermanentGrant.DND_ACCESS])
        assertEquals(GrantOutcome.SENT, outcomes[PermanentGrant.ACCESSIBILITY])
    }

    @Test
    fun `a backend that is gone is reported as unavailable`() = runBlocking {
        val shell = RecordingShell { throw ShizukuShellException(ShizukuShellException.UNAVAILABLE) }
        val outcomes = PermanentGrantsController(shell, "dev.argus").grant(setOf(PermanentGrant.USAGE_STATS))
        assertEquals(GrantOutcome.UNAVAILABLE, outcomes[PermanentGrant.USAGE_STATS])
    }

    @Test
    fun `a timed out command is a failure`() = runBlocking {
        val shell = RecordingShell { ShellResult(-1, timedOut = true) }
        val outcomes = PermanentGrantsController(shell, "dev.argus").grant(setOf(PermanentGrant.WRITE_SETTINGS))
        assertEquals(GrantOutcome.FAILED, outcomes[PermanentGrant.WRITE_SETTINGS])
    }

    @Test
    fun `a malformed package name is refused`() {
        val shell = RecordingShell { ShellResult(0) }
        assertFailsWith<IllegalArgumentException> { PermanentGrantsController(shell, "dev.argus; reboot") }
        assertFailsWith<IllegalArgumentException> { PermanentGrantsController(shell, "argus") }
    }
}
