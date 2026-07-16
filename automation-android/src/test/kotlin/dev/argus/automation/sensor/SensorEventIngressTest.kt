package dev.argus.automation.sensor

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SensorKind
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SensorEventIngressTest {
    private val kind = SensorKind.SIGNIFICANT_MOTION

    @Test
    fun `a shared callback fans out one envelope per eligible rule`() = runTest {
        val delivered = mutableListOf<TriggerEnvelope>()
        val rules = listOf(rule("a"), rule("b"))
        val ingress = ingress(RecordingDispatcher { delivered += it }, rules)

        ingress.onSensorTriggered(kind)

        assertEquals(
            listOf(AutomationId("a"), AutomationId("b")),
            delivered.map { (it.event as TriggerEvent.SensorChanged).automationId },
        )
        // Ogni regola ha un event id distinto.
        assertEquals(2, delivered.map { it.id.value }.toSet().size)
    }

    @Test
    fun `the one-shot is marked consumed and re-armed around the dispatch`() = runTest {
        val rearmer = RecordingRearmer()
        val ingress = ingress(RecordingDispatcher {}, listOf(rule("a")), rearmer)

        ingress.onSensorTriggered(kind)

        assertEquals(listOf(kind), rearmer.consumed)
        assertEquals(1, rearmer.rearmCalls)
    }

    @Test
    fun `redelivery reuses the same event id, the next detection gets a new one`() = runTest {
        val store = MemorySensorDetectionStore()
        val delivered = mutableListOf<TriggerEnvelope>()
        val rules = listOf(rule("a"))
        val ingress = ingress(RecordingDispatcher { delivered += it }, rules, store = store)

        ingress.onSensorTriggered(kind)
        val firstId = delivered.single().id.value

        // Simula un crash prima del complete: il pending resta e la redelivery riusa l'id.
        store.reopenLastPending()
        delivered.clear()
        ingress.recoverPending(setOf(AutomationId("a")))
        assertEquals(firstId, delivered.single().id.value)

        // Una detection successiva completata ottiene una sequenza (ed event id) diversa.
        delivered.clear()
        ingress.onSensorTriggered(kind)
        assertNotEquals(firstId, delivered.single().id.value)
    }

    @Test
    fun `a dispatch failure still re-arms and surfaces the error`() = runTest {
        val rearmer = RecordingRearmer()
        val ingress = ingress(
            RecordingDispatcher { error("engine down") },
            listOf(rule("a")),
            rearmer,
        )

        assertFailsWith<IllegalStateException> { ingress.onSensorTriggered(kind) }
        assertEquals(1, rearmer.rearmCalls)
    }

    @Test
    fun `a cancellation re-arms in NonCancellable and is rethrown`() = runTest {
        val rearmer = RecordingRearmer()
        val ingress = ingress(
            RecordingDispatcher { throw CancellationException("dispatch cancelled") },
            listOf(rule("a")),
            rearmer,
        )

        assertFailsWith<CancellationException> { ingress.onSensorTriggered(kind) }
        assertEquals(1, rearmer.rearmCalls)
    }

    @Test
    fun `recovery ignores a pending whose rule fingerprint has changed`() = runTest {
        val store = MemorySensorDetectionStore()
        val delivered = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(RecordingDispatcher { delivered += it }, listOf(rule("a")), store = store)

        ingress.onSensorTriggered(kind)
        store.reopenLastPending()
        delivered.clear()

        // La regola è stata modificata: il fingerprint corrente non è più quello del pending.
        val edited = ingress(
            RecordingDispatcher { delivered += it },
            listOf(rule("a", fingerprintChar = 'e')),
            store = store,
        )
        assertTrue(edited.recoverPending(setOf(AutomationId("a"))).isEmpty())
        assertTrue(delivered.isEmpty())
    }

    private fun ingress(
        dispatcher: SensorEventDispatcher,
        rules: List<EligibleSensorRule>,
        rearmer: SensorRearmer = RecordingRearmer(),
        store: SensorDetectionStore = MemorySensorDetectionStore(),
    ) = SensorEventIngress(
        store = store,
        dispatcher = dispatcher,
        rearmer = rearmer,
        eligibleRules = { k -> rules.filter { it.kind == k } },
    )

    // Il fingerprint deve essere 64 hex: deriva un char stabile dall'id (o dall'override).
    private fun rule(id: String, fingerprintChar: Char = hexOf(id)) =
        EligibleSensorRule(AutomationId(id), ApprovalFingerprint(fingerprintChar.toString().repeat(64)), kind)

    private fun hexOf(id: String): Char = "0123456789abcdef"[id.first().code % 16]
}

private class RecordingDispatcher(
    private val onDispatch: suspend (TriggerEnvelope) -> Unit,
) : SensorEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) = onDispatch(envelope)
}

private class RecordingRearmer : SensorRearmer {
    val consumed = mutableListOf<SensorKind>()
    var rearmCalls = 0
    override suspend fun markConsumed(kind: SensorKind) { consumed += kind }
    override suspend fun rearm(): SensorReconcileReport {
        rearmCalls += 1
        return SensorReconcileReport(emptyList(), emptySet())
    }
}

private class MemorySensorDetectionStore : SensorDetectionStore {
    private data class Entry(
        val fingerprint: ApprovalFingerprint,
        val kind: SensorKind,
        val sequence: Long,
        var pending: Boolean,
    )
    private val entries = mutableMapOf<AutomationId, Entry>()
    private val counters = mutableMapOf<AutomationId, Long>()
    private var lastId: AutomationId? = null

    override fun knownIds(): Set<AutomationId> = entries.keys.toSet()

    override fun pending(id: AutomationId, fingerprint: ApprovalFingerprint): SensorDetection? {
        val entry = entries[id] ?: return null
        if (entry.fingerprint != fingerprint || !entry.pending) return null
        return SensorDetection(entry.kind, entry.sequence)
    }

    override fun beginDetection(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
        kind: SensorKind,
    ): SensorDetection {
        val next = (counters[id] ?: 0L) + 1
        counters[id] = next
        entries[id] = Entry(fingerprint, kind, next, pending = true)
        lastId = id
        return SensorDetection(kind, next)
    }

    override fun completeDetection(id: AutomationId, fingerprint: ApprovalFingerprint, sequence: Long) {
        val entry = entries[id] ?: return
        if (entry.fingerprint == fingerprint && entry.sequence == sequence) entry.pending = false
    }

    override fun forget(id: AutomationId) {
        entries.remove(id)
        counters.remove(id)
    }

    /** Test helper: simula un crash che ha lasciato l'ultima detection non completata. */
    fun reopenLastPending() {
        lastId?.let { entries[it] = entries.getValue(it).copy(pending = true) }
    }
}
