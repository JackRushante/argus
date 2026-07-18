package dev.argus.automation.foreground

import dev.argus.automation.connectivity.ConnectivitySentinelBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverWorkLauncherTest {
    @Test
    fun `a foreground lease releases the broadcast before long work completes`() = runTest {
        val backend = LauncherBackend()
        val launcher = ReceiverWorkLauncher(
            scope = this,
            sentinel = SharedForegroundSentinel(backend),
        )
        val workStarted = CompletableDeferred<Unit>()
        val finishWork = CompletableDeferred<Unit>()
        var receiverReleased = false

        val job = launcher.launch("alarm", { receiverReleased = true }) {
            workStarted.complete(Unit)
            finishWork.await()
        }
        runCurrent()

        assertTrue(workStarted.isCompleted)
        assertTrue(receiverReleased)
        assertEquals(1, backend.startCalls)
        assertEquals(0, backend.stopCalls)

        finishWork.complete(Unit)
        job.join()
        assertEquals(1, backend.stopCalls)
    }

    @Test
    fun `two receiver executions keep the service until both complete`() = runTest {
        val backend = LauncherBackend()
        val launcher = ReceiverWorkLauncher(
            scope = this,
            sentinel = SharedForegroundSentinel(backend),
        )
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()

        val firstJob = launcher.launch("first", {}) { first.await() }
        val secondJob = launcher.launch("second", {}) { second.await() }
        runCurrent()
        assertEquals(1, backend.startCalls)

        first.complete(Unit)
        runCurrent()
        assertEquals(0, backend.stopCalls)

        second.complete(Unit)
        joinAll(firstJob, secondJob)
        assertEquals(1, backend.stopCalls)
    }

    @Test
    fun `a non receiver callback keeps the same foreground lease until completion`() = runTest {
        val backend = LauncherBackend()
        val launcher = ReceiverWorkLauncher(
            scope = this,
            sentinel = SharedForegroundSentinel(backend),
        )
        val finishWork = CompletableDeferred<Unit>()

        val job = launcher.launch("notification") { finishWork.await() }
        runCurrent()
        assertEquals(1, backend.startCalls)
        assertEquals(0, backend.stopCalls)

        finishWork.complete(Unit)
        job.join()
        assertEquals(1, backend.stopCalls)
    }

    @Test
    fun `failed foreground start keeps broadcast protection for a bounded fallback`() = runTest {
        val backend = LauncherBackend(startResult = false)
        val launcher = ReceiverWorkLauncher(
            scope = this,
            sentinel = SharedForegroundSentinel(backend),
        )
        val finishWork = CompletableDeferred<Unit>()
        var receiverReleased = false

        launcher.launch("sms", { receiverReleased = true }) { finishWork.await() }
        runCurrent()
        assertFalse(receiverReleased)

        advanceTimeBy(ReceiverWorkLauncher.FALLBACK_RECEIVER_LEASE_MILLIS - 1)
        runCurrent()
        assertFalse(receiverReleased)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(receiverReleased)
        assertEquals(0, backend.stopCalls)

        finishWork.complete(Unit)
        advanceUntilIdle()
    }
}

private class LauncherBackend(
    private val startResult: Boolean = true,
) : ConnectivitySentinelBackend {
    var startCalls = 0
    var stopCalls = 0

    override suspend fun start(): Boolean {
        startCalls += 1
        return startResult
    }

    override suspend fun stop(): Boolean {
        stopCalls += 1
        return true
    }
}
