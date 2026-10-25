package dev.argus.automation.power

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
    fun `acquires on the first holder with a seventy minute safety timeout and releases on the last`() {
        wakeLock.acquire()
        wakeLock.acquire()
        assertEquals(2, wakeLock.activeHolders)
        assertEquals(listOf("acquire:${70 * 60 * 1_000L}"), system.events)

        assertTrue(wakeLock.release())
        assertEquals(listOf("acquire:${70 * 60 * 1_000L}"), system.events)

        assertTrue(wakeLock.release())
        assertEquals(0, wakeLock.activeHolders)
        assertEquals(listOf("acquire:${70 * 60 * 1_000L}", "release"), system.events)
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
            listOf("acquire:${70 * 60 * 1_000L}", "release", "acquire:${70 * 60 * 1_000L}", "release"),
            system.events,
        )
    }
}
