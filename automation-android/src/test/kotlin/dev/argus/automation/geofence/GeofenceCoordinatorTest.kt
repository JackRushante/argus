package dev.argus.automation.geofence

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.automation.DeviceLocation
import dev.argus.automation.CurrentLocationProvider
import dev.argus.engine.runtime.TriggerEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeofenceCoordinatorTest {
    @Test
    fun `new armed rule is prepared registered and activated`() = runTest {
        val rule = rule("home")
        val state = MemoryGeofenceStateStore()
        val backend = RecordingBackend()

        val report = GeofenceCoordinator(RulesStore(listOf(rule)), state, backend).reconcile()

        assertEquals(listOf(rule.id), report.registered)
        assertEquals(listOf(rule.id), report.requiredBy)
        assertEquals(listOf(rule.id), backend.registered.map { it.automationId })
        assertTrue(requireNotNull(state.get(rule.id)).active)
    }

    @Test
    fun `process recovery recreates OS registration and preserves transition sequence`() = runTest {
        val rule = rule("recover")
        val fingerprint = requireNotNull(rule.approvalFingerprint)
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration.from(rule))
            activate(rule.id, fingerprint)
            beginTransition(rule.id, fingerprint, Transition.ENTER)!!.also {
                completeTransition(rule.id, fingerprint, it.sequence)
            }
        }
        val backend = RecordingBackend()

        val report = GeofenceCoordinator(RulesStore(listOf(rule)), state, backend)
            .reconcile(recreateOsRegistrations = true)

        assertEquals(listOf(rule.id), backend.unregistered)
        assertEquals(listOf(rule.id), report.registered)
        val recovered = requireNotNull(state.get(rule.id))
        assertEquals(Transition.ENTER, recovered.lastTransition)
        assertEquals(1, recovered.sequence)
        assertTrue(recovered.active)
    }

    @Test
    fun `process recovery detects an exit missed while the process was unavailable`() = runTest {
        val rule = rule("missed-exit")
        val fingerprint = requireNotNull(rule.approvalFingerprint)
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration.from(rule))
            activate(rule.id, fingerprint)
            beginTransition(rule.id, fingerprint, Transition.ENTER)!!.also {
                completeTransition(rule.id, fingerprint, it.sequence)
            }
        }
        val envelopes = mutableListOf<TriggerEnvelope>()
        val ingress = GeofenceEventIngress(state) { envelopes += it }
        val current = CurrentLocationProvider { DeviceLocation(46.0, 10.0) }

        val report = GeofenceCoordinator(
            RulesStore(listOf(rule)),
            state,
            RecordingBackend(),
            current,
            ingress,
        ).reconcile(recreateOsRegistrations = true)

        assertEquals(listOf(rule.id), report.recovered)
        assertEquals(Transition.EXIT, requireNotNull(state.get(rule.id)).lastTransition)
        assertEquals(1, envelopes.size)
    }

    @Test
    fun `process recovery does not invent a transition inside the boundary hysteresis`() = runTest {
        val rule = rule("boundary")
        val fingerprint = requireNotNull(rule.approvalFingerprint)
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration.from(rule))
            activate(rule.id, fingerprint)
            beginTransition(rule.id, fingerprint, Transition.ENTER)!!.also {
                completeTransition(rule.id, fingerprint, it.sequence)
            }
        }
        val envelopes = mutableListOf<TriggerEnvelope>()
        val ingress = GeofenceEventIngress(state) { envelopes += it }
        // ~160 m dal centro con raggio 150 m: fuori nominalmente, ma entro i 25 m di guardia.
        val current = CurrentLocationProvider { DeviceLocation(45.00144, 9.0) }

        val report = GeofenceCoordinator(
            RulesStore(listOf(rule)),
            state,
            RecordingBackend(),
            current,
            ingress,
        ).reconcile(recreateOsRegistrations = true)

        assertEquals(emptyList(), report.recovered)
        assertEquals(emptyList(), envelopes)
        assertEquals(Transition.ENTER, requireNotNull(state.get(rule.id)).lastTransition)
    }

    @Test
    fun `disable or delete unregisters every known stale rule`() = runTest {
        val stale = rule("stale")
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration.from(stale))
            activate(stale.id, requireNotNull(stale.approvalFingerprint))
        }
        val backend = RecordingBackend()

        val report = GeofenceCoordinator(RulesStore(emptyList()), state, backend).reconcile()

        assertEquals(listOf(stale.id), report.unregistered)
        assertEquals(emptySet(), state.knownIds())
        assertTrue(report.cleanupSucceeded)
    }

    @Test
    fun `bootstrap without a known geofence state never wakes the location provider`() = runTest {
        var locationReads = 0
        val current = CurrentLocationProvider {
            locationReads += 1
            DeviceLocation(45.0, 9.0)
        }
        val ingress = GeofenceEventIngress(MemoryGeofenceStateStore()) { }

        GeofenceCoordinator(
            RulesStore(emptyList()),
            MemoryGeofenceStateStore(),
            RecordingBackend(),
            current,
            ingress,
        ).reconcile(recreateOsRegistrations = true)

        assertEquals(0, locationReads)
    }

    @Test
    fun `unsupported dwell and backend failure remain fail closed and recoverable`() = runTest {
        val dwell = rule("dwell", Transition.DWELL)
        val normal = rule("normal")
        val state = MemoryGeofenceStateStore()
        val failedBackend = RecordingBackend(failRegister = true)

        val report = GeofenceCoordinator(
            RulesStore(listOf(dwell, normal)),
            state,
            failedBackend,
        ).reconcile()

        assertEquals(setOf(dwell.id, normal.id), report.failed.toSet())
        assertFalse(requireNotNull(state.get(normal.id)).active)
        assertEquals(listOf(normal.id), failedBackend.registered.map { it.automationId })
        assertFalse(dwell.id in state.knownIds())
    }

    @Test
    fun `cancellation from backend is never converted into ordinary registration failure`() = runTest {
        val rule = rule("cancel")
        val backend = object : GeofenceBackend {
            override fun register(registration: GeofenceRegistration) {
                throw CancellationException("stop")
            }

            override fun unregister(automationId: AutomationId) = Unit
        }

        assertFailsWith<CancellationException> {
            GeofenceCoordinator(RulesStore(listOf(rule)), MemoryGeofenceStateStore(), backend)
                .reconcile()
        }
    }

    private fun rule(id: String, transition: Transition = Transition.EXIT): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            enabled = true,
            trigger = Trigger.Geofence(45.0, 9.0, 150.0, transition),
            actions = listOf(Action.SetWifi(false)),
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }
}

private class RecordingBackend(
    private val failRegister: Boolean = false,
) : GeofenceBackend {
    val registered = mutableListOf<GeofenceRegistration>()
    val unregistered = mutableListOf<AutomationId>()

    override fun register(registration: GeofenceRegistration) {
        registered += registration
        if (failRegister) error("register failed")
    }

    override fun unregister(automationId: AutomationId) {
        unregistered += automationId
    }
}

private class RulesStore(private val rules: List<Automation>) : AutomationStore {
    override suspend fun get(id: AutomationId): Automation? = rules.firstOrNull { it.id == id }
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
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ) = false
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        FireClaimResult.NotEligible
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null
}
