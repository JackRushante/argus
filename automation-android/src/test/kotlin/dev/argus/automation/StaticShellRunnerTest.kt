package dev.argus.automation

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticShellRunnerTest {
    private val context = FireContext(
        event = TriggerEvent.TimeFired(
            AutomationId("automation-1"),
            ApprovalFingerprint("0".repeat(64)),
        ),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
        priority = 7,
    )

    @Test
    fun `runner invokes system shell with literal command and execution metadata`() = runTest {
        val shell = RecordingShell(ShellResult(exitCode = 0))
        val runner = ShizukuStaticShellRunner(shell)

        assertEquals(ActionResult.Success, runner.run("id >/dev/null", context))
        assertEquals(listOf("/system/bin/sh", "-c", "id >/dev/null"), shell.command)
        assertEquals(7, shell.priority)
        assertEquals(30_000L, shell.timeoutMillis)
        assertEquals(16 * 1_024, shell.maxOutputBytes)
        assertEquals("execution-1", shell.executionId)
    }

    @Test
    fun `runner maps timeout and nonzero exit without exposing output`() = runTest {
        val timedOut = ShizukuStaticShellRunner(
            RecordingShell(ShellResult(exitCode = -1, timedOut = true, stderr = "secret".encodeToByteArray())),
        )
        assertEquals(ActionResult.Failure("shell_timeout"), timedOut.run("sleep 99", context))

        val failed = ShizukuStaticShellRunner(
            RecordingShell(ShellResult(exitCode = 9, stderr = "secret".encodeToByteArray())),
        )
        assertEquals(ActionResult.Failure("shell_failed"), failed.run("false", context))
    }
}

private class RecordingShell(private val result: ShellResult) : PrivilegedShell {
    var command: List<String>? = null
    var priority: Int? = null
    var timeoutMillis: Long? = null
    var maxOutputBytes: Int? = null
    var executionId: String? = null

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        this.command = command
        this.priority = priority
        this.timeoutMillis = timeoutMillis
        this.maxOutputBytes = maxOutputBytes
        this.executionId = executionId
        return result
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = error("runToFile non atteso")
}
