package dev.argus.shizuku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PrioritizedPrivilegedShellTest {
    @Test
    fun `queued commands are single writer and highest priority goes first`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        var active = 0
        var maxActive = 0
        val transport = ShellTransport { request ->
            active++
            maxActive = maxOf(maxActive, active)
            val name = request.command.single()
            started += "${request.executionId}:$name"
            if (name == "running") gate.await()
            active--
            ShellResult(0, stdout = name.toByteArray())
        }
        val shell = PrioritizedPrivilegedShell(
            transport,
            CoroutineScope(SupervisorJob() + dispatcher),
        )

        val running = async(dispatcher) {
            shell.run(listOf("running"), executionId = "execution-running")
        }
        runCurrent()
        val low = async(dispatcher) {
            shell.run(listOf("low"), priority = 1, executionId = "execution-low")
        }
        val high = async(dispatcher) {
            shell.run(listOf("high"), priority = 10, executionId = "execution-high")
        }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals("running", running.await().stdoutText)
        assertEquals("high", high.await().stdoutText)
        assertEquals("low", low.await().stdoutText)
        assertEquals(
            listOf(
                "execution-running:running",
                "execution-high:high",
                "execution-low:low",
            ),
            started,
        )
        assertEquals(1, maxActive)
        shell.close()
    }

    @Test
    fun `invalid requests are rejected before reaching privileged transport`() = runTest {
        var calls = 0
        val shell = PrioritizedPrivilegedShell(
            ShellTransport { calls++; ShellResult(0) },
            this,
        )

        assertFailsWith<IllegalArgumentException> { shell.run(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            shell.run(listOf("id"), timeoutMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            shell.run(listOf("id"), executionId = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            shell.runToFile(
                listOf("screencap"),
                File("unused"),
                maxOutputBytes = PrivilegedShell.DEFAULT_FILE_OUTPUT_BYTES + 1,
            )
        }
        assertEquals(0, calls)
        shell.close()
    }

    @Test
    fun `execution id reaches privileged transport and correlated result`() = runTest {
        var captured: ShellRequest? = null
        val shell = PrioritizedPrivilegedShell(
            ShellTransport { request ->
                captured = request
                ShellResult(0)
            },
            this,
        )

        val result = shell.run(listOf("id"), executionId = "execution-42")

        assertEquals("execution-42", captured?.executionId)
        assertEquals("execution-42", result.executionId)
        shell.close()
    }

    @Test
    fun `close cancels running and queued commands and rejects new work`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val shell = PrioritizedPrivilegedShell(
            ShellTransport { gate.await(); ShellResult(0) },
            CoroutineScope(SupervisorJob() + dispatcher),
        )

        val running = async(dispatcher) { shell.run(listOf("running")) }
        runCurrent()
        val queued = async(dispatcher) { shell.run(listOf("queued")) }
        runCurrent()

        shell.close()
        runCurrent()

        assertTrue(running.isCancelled)
        assertTrue(queued.isCancelled)
        assertFailsWith<IllegalStateException> { shell.run(listOf("late")) }
    }
}
