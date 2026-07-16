package dev.argus.automation.sensor

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensorTriggerCoordinatorTest {
    private val implemented = setOf(SensorKind.SIGNIFICANT_MOTION)

    @Test
    fun `an eligible rule registers the physical sensor exactly once`() = runTest {
        val backend = RecordingSensorBackend()
        val coordinator = SensorTriggerCoordinator(
            RulesStore(listOf(rule("a"))),
            backend,
            implemented,
        )

        val first = coordinator.reconcile()
        assertEquals(listOf(AutomationId("a")), first.requiredBy)
        assertEquals(setOf(SensorKind.SIGNIFICANT_MOTION), first.registeredKinds)
        assertEquals(1, backend.registerCalls[SensorKind.SIGNIFICANT_MOTION])

        // Idempotenza: un secondo reconcile non ri-registra il sensore fisico.
        coordinator.reconcile()
        assertEquals(1, backend.registerCalls[SensorKind.SIGNIFICANT_MOTION])
    }

    @Test
    fun `two rules of the same kind share a single physical registration`() = runTest {
        val backend = RecordingSensorBackend()
        val report = SensorTriggerCoordinator(
            RulesStore(listOf(rule("a"), rule("b"))),
            backend,
            implemented,
        ).reconcile()

        assertEquals(listOf(AutomationId("a"), AutomationId("b")), report.requiredBy)
        assertEquals(1, backend.registerCalls[SensorKind.SIGNIFICANT_MOTION])
    }

    @Test
    fun `removing the last rule cancels the physical sensor`() = runTest {
        val backend = RecordingSensorBackend()
        val store = MutableRulesStore(listOf(rule("a")))
        val coordinator = SensorTriggerCoordinator(store, backend, implemented)

        coordinator.reconcile()
        store.rules = emptyList()
        val report = coordinator.reconcile()

        assertTrue(report.requiredBy.isEmpty())
        assertTrue(report.registeredKinds.isEmpty())
        assertEquals(1, backend.cancelCalls[SensorKind.SIGNIFICANT_MOTION])
    }

    @Test
    fun `one rule removed leaves the shared sensor alive for the other`() = runTest {
        val backend = RecordingSensorBackend()
        val store = MutableRulesStore(listOf(rule("a"), rule("b")))
        val coordinator = SensorTriggerCoordinator(store, backend, implemented)

        coordinator.reconcile()
        store.rules = listOf(rule("b"))
        val report = coordinator.reconcile()

        assertEquals(listOf(AutomationId("b")), report.requiredBy)
        assertEquals(setOf(SensorKind.SIGNIFICANT_MOTION), report.registeredKinds)
        assertEquals(null, backend.cancelCalls[SensorKind.SIGNIFICANT_MOTION])
    }

    @Test
    fun `a not-yet-implemented kind is never registered`() = runTest {
        val backend = RecordingSensorBackend()
        val report = SensorTriggerCoordinator(
            RulesStore(listOf(rule("step", SensorKind.STEP_COUNTER))),
            backend,
            implemented,
        ).reconcile()

        assertTrue(report.requiredBy.isEmpty())
        assertTrue(backend.registerCalls.isEmpty())
    }

    @Test
    fun `unavailable hardware routes the rule to needs review, not a retry`() = runTest {
        val backend = RecordingSensorBackend(outcome = SensorRegistrationOutcome.Unavailable)
        val report = SensorTriggerCoordinator(
            RulesStore(listOf(rule("a"))),
            backend,
            implemented,
        ).reconcile()

        assertEquals(listOf(AutomationId("a")), report.needsReview)
        assertTrue(report.requiredBy.isEmpty())
        assertTrue(report.registeredKinds.isEmpty())
    }

    @Test
    fun `a transient failure names the rule without needs review`() = runTest {
        val backend = RecordingSensorBackend(outcome = SensorRegistrationOutcome.Failure("busy"))
        val report = SensorTriggerCoordinator(
            RulesStore(listOf(rule("a"))),
            backend,
            implemented,
        ).reconcile()

        assertEquals(listOf(AutomationId("a")), report.failed)
        assertTrue(report.needsReview.isEmpty())
        assertTrue(report.registeredKinds.isEmpty())
    }

    @Test
    fun `a fresh coordinator does not believe a previous physical registration`() = runTest {
        // Process recreation: nuovo coordinator, stessa regola armata. Deve ri-registrare
        // il sensore da zero, senza fidarsi di alcuno stato "registered" persistito.
        val backend = RecordingSensorBackend()
        val store = RulesStore(listOf(rule("a")))

        SensorTriggerCoordinator(store, backend, implemented).reconcile()
        SensorTriggerCoordinator(store, backend, implemented).reconcile()

        assertEquals(2, backend.registerCalls[SensorKind.SIGNIFICANT_MOTION])
    }

    private fun rule(id: String, kind: SensorKind = SensorKind.SIGNIFICANT_MOTION): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Sensor(kind),
            actions = listOf(Action.ShowNotification("Argus", id)),
            enabled = true,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }
}

private class RecordingSensorBackend(
    private val outcome: SensorRegistrationOutcome = SensorRegistrationOutcome.Registered,
) : SensorTriggerBackend {
    val registerCalls = mutableMapOf<SensorKind, Int>()
    val cancelCalls = mutableMapOf<SensorKind, Int>()

    override fun register(kind: SensorKind): SensorRegistrationOutcome {
        registerCalls[kind] = (registerCalls[kind] ?: 0) + 1
        return outcome
    }

    override fun cancel(kind: SensorKind): Boolean {
        cancelCalls[kind] = (cancelCalls[kind] ?: 0) + 1
        return true
    }
}

private open class RulesStore(private val rules: List<Automation>) : AutomationStore {
    override suspend fun get(id: AutomationId): Automation? = rules.singleOrNull { it.id == id }
    override suspend fun all(): List<Automation> = rules
    override fun observeAll(): Flow<List<Automation>> = flowOf(rules)
    override suspend fun armed(): List<Automation> = rules.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }
    override suspend fun delete(id: AutomationId) = Unit
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = FireClaimResult.NotEligible
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null
}

private class MutableRulesStore(var rules: List<Automation>) : AutomationStore {
    override suspend fun get(id: AutomationId): Automation? = rules.singleOrNull { it.id == id }
    override suspend fun all(): List<Automation> = rules
    override fun observeAll(): Flow<List<Automation>> = flowOf(rules)
    override suspend fun armed(): List<Automation> = rules.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }
    override suspend fun delete(id: AutomationId) = Unit
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = FireClaimResult.NotEligible
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null
}
