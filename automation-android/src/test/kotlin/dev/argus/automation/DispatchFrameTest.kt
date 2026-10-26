package dev.argus.automation

import dev.argus.automation.power.ExecutionWakeLock
import dev.argus.automation.power.PowerLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DispatchFrameTest {
    private val lockEvents = mutableListOf<String>()
    private val wakeLock = ExecutionWakeLock(
        object : PowerLock {
            override fun acquire(timeoutMillis: Long) {
                lockEvents += "acquire"
            }

            override fun release() {
                lockEvents += "release"
            }
        },
    )

    @Test
    fun `a dispatch runs under the wakelock and reports its end after releasing it`() = runTest {
        val observed = mutableListOf<String>()
        lateinit var frame: DispatchFrame
        frame = DispatchFrame(wakeLock) {
            observed += "end holders=${wakeLock.activeHolders} idle=${frame.idle}"
        }

        val result = frame.run {
            observed += "inside holders=${wakeLock.activeHolders} idle=${frame.idle}"
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("inside holders=1 idle=false", "end holders=0 idle=true"), observed)
        assertEquals(listOf("acquire", "release"), lockEvents)
    }

    @Test
    fun `a failing dispatch still releases the wakelock and reports its end`() = runTest {
        var ended = 0
        val frame = DispatchFrame(wakeLock) { ended += 1 }

        assertFailsWith<IllegalStateException> { frame.run { error("forced") } }

        assertEquals(1, ended)
        assertEquals(0, wakeLock.activeHolders)
        assertTrue(frame.idle)
    }

    @Test
    fun `a failing end hook never changes the outcome of the dispatch`() = runTest {
        val frame = DispatchFrame(wakeLock) { error("maintenance exploded") }

        assertEquals(7, frame.run { 7 })
    }

    @Test
    fun `overlapping dispatches keep the frame busy until the last one ends`() = runTest {
        val frame = DispatchFrame(wakeLock)
        val gate = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { frame.run { gate.await() } }

        assertFalse(frame.idle)
        assertFalse(frame.run { frame.idle })

        gate.complete(Unit)
        first.join()
        assertTrue(frame.idle)
    }
}
