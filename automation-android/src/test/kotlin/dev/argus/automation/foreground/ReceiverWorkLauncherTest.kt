package dev.argus.automation.foreground

import dev.argus.automation.connectivity.ConnectivitySentinelBackend
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
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

    // --- B20: lease negata, traccia nel Log -------------------------------------------------------

    @Test
    fun `a denied lease for a known rule leaves an ERROR audit and the work still runs`() = runTest {
        val audit = RecordingAudit()
        val launcher = ReceiverWorkLauncher(
            scope = this,
            sentinel = SharedForegroundSentinel(LauncherBackend(startResult = false)),
            audit = audit,
            nowMillis = { 7_000L },
        )
        var ran = false

        launcher.launch("time_alarm", AutomationId("rule-1")) { ran = true }
        advanceUntilIdle()

        assertTrue(ran, "il lavoro prosegue best-effort anche senza lease")
        val event = audit.events.single()
        assertEquals(AutomationId("rule-1"), event.automationId)
        assertEquals(AuditKind.ERROR, event.kind)
        assertEquals("foreground_service_denied", event.detail)
        assertEquals(7_000L, event.atMillis)
    }

    @Test
    fun `a denied lease without a known rule leaves no audit`() = runTest {
        val audit = RecordingAudit()
        val launcher = ReceiverWorkLauncher(
            scope = this,
            sentinel = SharedForegroundSentinel(LauncherBackend(startResult = false)),
            audit = audit,
        )

        launcher.launch("sms") { }
        advanceUntilIdle()

        assertEquals(emptyList(), audit.events)
    }

    @Test
    fun `a granted lease leaves no audit`() = runTest {
        val audit = RecordingAudit()
        val launcher = ReceiverWorkLauncher(
            scope = this,
            sentinel = SharedForegroundSentinel(LauncherBackend()),
            audit = audit,
        )

        launcher.launch("time_alarm", AutomationId("rule-1")) { }.join()

        assertEquals(emptyList(), audit.events)
    }
}

private class RecordingAudit : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) {
        events += e
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
