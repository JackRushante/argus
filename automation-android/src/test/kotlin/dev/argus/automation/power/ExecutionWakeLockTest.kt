package dev.argus.automation.power

import dev.argus.engine.runtime.ProgramInterpreter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RecordingPowerLock : PowerLock {
    val events = mutableListOf<String>()
    override fun acquire(timeoutMillis: Long) {
        events += "acquire:$timeoutMillis"
    }

    override fun release() {
        events += "release"
    }
}

class ExecutionWakeLockTest {
    private val system = RecordingPowerLock()
    private val wakeLock = ExecutionWakeLock(system)

    @Test
    fun `acquires on the first holder with a safety timeout and releases on the last`() {
        wakeLock.acquire()
        wakeLock.acquire()
        assertEquals(2, wakeLock.activeHolders)
        assertEquals(listOf("acquire:${ExecutionWakeLock.DEFAULT_TIMEOUT_MILLIS}"), system.events)

        assertTrue(wakeLock.release())
        assertEquals(listOf("acquire:${ExecutionWakeLock.DEFAULT_TIMEOUT_MILLIS}"), system.events)

        assertTrue(wakeLock.release())
        assertEquals(0, wakeLock.activeHolders)
        assertEquals(listOf("acquire:${ExecutionWakeLock.DEFAULT_TIMEOUT_MILLIS}", "release"), system.events)
    }

    @Test
    fun `the safety timeout outlasts the longest program`() {
        assertTrue(
            ExecutionWakeLock.DEFAULT_TIMEOUT_MILLIS >
                ProgramInterpreter.MAX_EXECUTION_MILLIS + ExecutionWakeLock.REFRESH_EVERY_MILLIS,
        )
    }

    @Test
    fun `an overlapping holder extends the timeout of the system lock`() {
        var uptime = 0L
        val lock = ExecutionWakeLock(system, timeoutMillis = 1_000, uptimeMillis = { uptime }, refreshEveryMillis = 100)

        lock.acquire()
        uptime = 900
        // Il primo lavoro ha quasi esaurito il timeout: il secondo lo fa ripartire da adesso.
        lock.acquire()
        assertTrue(lock.release())

        assertEquals(listOf("acquire:1000", "acquire:1000"), system.events)
    }

    @Test
    fun `refresh renews a held lock at most once per interval and never an idle one`() {
        var uptime = 0L
        val lock = ExecutionWakeLock(system, timeoutMillis = 1_000, uptimeMillis = { uptime }, refreshEveryMillis = 100)

        lock.refresh()
        assertEquals(emptyList(), system.events)

        lock.acquire()
        uptime = 50
        lock.refresh()
        assertEquals(listOf("acquire:1000"), system.events)

        uptime = 150
        lock.refresh()
        uptime = 200
        lock.refresh()
        assertEquals(listOf("acquire:1000", "acquire:1000"), system.events)

        assertTrue(lock.release())
        uptime = 1_000
        lock.refresh()
        assertEquals(listOf("acquire:1000", "acquire:1000", "release"), system.events)
    }

    @Test
    fun `an unbalanced release is ignored and never touches the system lock`() {
        assertFalse(wakeLock.release())

        assertEquals(0, wakeLock.activeHolders)
        assertEquals(emptyList(), system.events)
    }

    @Test
    fun `holding releases on success and on failure`() = runTest {
        wakeLock.holding { assertEquals(1, wakeLock.activeHolders) }
        assertEquals(0, wakeLock.activeHolders)

        assertFailsWith<IllegalStateException> {
            wakeLock.holding { error("forced") }
        }
        assertEquals(0, wakeLock.activeHolders)
        assertEquals(
            listOf("acquire:${ExecutionWakeLock.DEFAULT_TIMEOUT_MILLIS}", "release", "acquire:${ExecutionWakeLock.DEFAULT_TIMEOUT_MILLIS}", "release"),
            system.events,
        )
    }
}
