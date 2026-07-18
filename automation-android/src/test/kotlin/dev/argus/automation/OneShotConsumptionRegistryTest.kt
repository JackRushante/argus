package dev.argus.automation

import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OneShotConsumptionRegistryTest {
    @Test
    fun `inactive lane waits for an in-flight auto-disable decision`() = runTest {
        val registry = ProcessOneShotConsumptionRegistry()
        val eventId = TriggerEventId("time:fingerprint:1")
        val attempt = registry.begin(eventId)

        val decision = async { registry.wasAutoConsumed(eventId) }
        runCurrent()
        assertFalse(decision.isCompleted)

        attempt.complete(consumed = true)

        assertTrue(decision.await())
        assertTrue(registry.wasAutoConsumed(eventId))
    }

    @Test
    fun `failed conditional disable cannot authorize later delivery`() = runTest {
        val registry = ProcessOneShotConsumptionRegistry()
        val eventId = TriggerEventId("immediate:fingerprint:2")

        registry.begin(eventId).complete(consumed = false)

        assertFalse(registry.wasAutoConsumed(eventId))
    }
}
