package dev.argus.shizuku

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessTreeKillerTest {
    @Test
    fun `with setsid available the command runs in its own group behind setsid`() {
        val plan = ProcessTreeKiller.plan(listOf("sh", "-c", "sleep 100"), setsidExists = true)

        assertEquals(listOf("/system/bin/setsid", "sh", "-c", "sleep 100"), plan.argv)
        assertTrue(plan.ownGroup)
    }

    @Test
    fun `without setsid the command is left untouched and no group kill is planned`() {
        val command = listOf("sh", "-c", "sleep 100")
        val plan = ProcessTreeKiller.plan(command, setsidExists = false)

        assertEquals(command, plan.argv)
        assertFalse(plan.ownGroup)
    }

    @Test
    fun `timeout kills the whole process group and also reaps the started process`() {
        val process = FakeProcess()
        val killed = mutableListOf<Int>()

        ProcessTreeKiller.terminate(
            process,
            ProcessTreeKiller.plan(listOf("sh"), setsidExists = true),
            pidOf = { 4242 },
            killGroup = { killed += it },
        )

        assertEquals(listOf(4242), killed)
        assertEquals(1, process.destroyed)
    }

    @Test
    fun `an unknown pid falls back to destroyForcibly only`() {
        val process = FakeProcess()
        val killed = mutableListOf<Int>()

        ProcessTreeKiller.terminate(
            process,
            ProcessTreeKiller.plan(listOf("sh"), setsidExists = true),
            pidOf = { null },
            killGroup = { killed += it },
        )

        assertTrue(killed.isEmpty())
        assertEquals(1, process.destroyed)
    }

    @Test
    fun `a failing group kill or pid lookup still falls back to destroyForcibly`() {
        val failingKill = FakeProcess()
        ProcessTreeKiller.terminate(
            failingKill,
            ProcessTreeKiller.plan(listOf("sh"), setsidExists = true),
            pidOf = { 4242 },
            killGroup = { error("ESRCH") },
        )
        assertEquals(1, failingKill.destroyed)

        val failingLookup = FakeProcess()
        ProcessTreeKiller.terminate(
            failingLookup,
            ProcessTreeKiller.plan(listOf("sh"), setsidExists = true),
            pidOf = { error("hidden api") },
            killGroup = { error("must not be called") },
        )
        assertEquals(1, failingLookup.destroyed)
    }

    @Test
    fun `without an own group the whole-group kill is never used`() {
        val process = FakeProcess()
        val killed = mutableListOf<Int>()

        ProcessTreeKiller.terminate(
            process,
            ProcessTreeKiller.plan(listOf("sh"), setsidExists = false),
            pidOf = { 4242 },
            killGroup = { killed += it },
        )

        assertTrue(killed.isEmpty(), "senza setsid il pgid non è del processo: niente kill(-pid)")
        assertEquals(1, process.destroyed)
    }

    @Test
    fun `a pid that is not a real process never reaches the group kill`() {
        // kill(-1) colpirebbe ogni processo raggiungibile, kill(0) il gruppo del chiamante.
        listOf(-1, 0, 1).forEach { pid ->
            val killed = mutableListOf<Int>()
            ProcessTreeKiller.terminate(
                FakeProcess(),
                ProcessTreeKiller.plan(listOf("sh"), setsidExists = true),
                pidOf = { pid },
                killGroup = { killed += it },
            )
            assertTrue(killed.isEmpty(), "pid $pid")
        }
    }

    @Test
    fun `reflected pid is read from the pid field and null when it is not available`() {
        // Su JDK `Process.pid()` base lancia UnsupportedOperationException: il ripiego legge il campo.
        assertEquals(31337, ProcessTreeKiller.reflectedPid(FakeProcess(pid = 31337)))
        assertNull(ProcessTreeKiller.reflectedPid(ProcessWithoutPid()))
    }

    private open class BaseFakeProcess : Process() {
        var destroyed = 0

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process {
            destroyed += 1
            return this
        }
    }

    private class FakeProcess(private val pid: Int = 1) : BaseFakeProcess() {
        fun pidForTest(): Int = pid
    }

    private class ProcessWithoutPid : BaseFakeProcess()
}
