package dev.argus.automation.foreground

import dev.argus.automation.connectivity.ConnectivitySentinelBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedForegroundSentinelTest {
    @Test
    fun `the fgs starts on the first demand and stops only when the union empties`() = runTest {
        val backend = RecordingBackend()
        val sentinel = SharedForegroundSentinel(backend)

        sentinel.setDemand(SentinelDemand.Connectivity, required = true)
        assertEquals(1, backend.startCalls)

        // Un secondo demand non riavvia il FGS già acceso.
        sentinel.setDemand(SentinelDemand.Sensor, required = true)
        assertEquals(1, backend.startCalls)
        assertEquals(0, backend.stopCalls)

        // Togliere la connettività NON ferma il FGS: resta il sensore.
        sentinel.setDemand(SentinelDemand.Connectivity, required = false)
        assertEquals(0, backend.stopCalls)

        // Solo quando anche l'ultimo demand cade il FGS si ferma.
        sentinel.setDemand(SentinelDemand.Sensor, required = false)
        assertEquals(1, backend.stopCalls)
    }

    @Test
    fun `re-declaring the same demand does not toggle the fgs`() = runTest {
        val backend = RecordingBackend()
        val sentinel = SharedForegroundSentinel(backend)

        sentinel.setDemand(SentinelDemand.Sensor, required = true)
        sentinel.setDemand(SentinelDemand.Sensor, required = true)
        assertEquals(1, backend.startCalls)

        sentinel.setDemand(SentinelDemand.Sensor, required = false)
        sentinel.setDemand(SentinelDemand.Sensor, required = false)
        assertEquals(1, backend.stopCalls)
    }

    @Test
    fun `a failed start is not remembered and the next demand retries it`() = runTest {
        val backend = RecordingBackend(startResults = ArrayDeque(listOf(false, true)))
        val sentinel = SharedForegroundSentinel(backend)

        assertFalse(sentinel.setDemand(SentinelDemand.Sensor, required = true))
        assertTrue(sentinel.setDemand(SentinelDemand.Sensor, required = true))

        assertEquals(2, backend.startCalls)
        assertEquals(0, backend.stopCalls)
    }

    @Test
    fun `a failed stop keeps the demand so cleanup can retry`() = runTest {
        val backend = RecordingBackend(stopResults = ArrayDeque(listOf(false, true)))
        val sentinel = SharedForegroundSentinel(backend)

        assertTrue(sentinel.setDemand(SentinelDemand.Sensor, required = true))
        assertFalse(sentinel.setDemand(SentinelDemand.Sensor, required = false))
        assertTrue(sentinel.setDemand(SentinelDemand.Sensor, required = false))

        assertEquals(1, backend.startCalls)
        assertEquals(2, backend.stopCalls)
    }
}

private class RecordingBackend(
    private val startResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val stopResults: ArrayDeque<Boolean> = ArrayDeque(),
) : ConnectivitySentinelBackend {
    var startCalls = 0
    var stopCalls = 0
    override suspend fun start(): Boolean {
        startCalls += 1
        return startResults.removeFirstOrNull() ?: true
    }
    override suspend fun stop(): Boolean {
        stopCalls += 1
        return stopResults.removeFirstOrNull() ?: true
    }
}
