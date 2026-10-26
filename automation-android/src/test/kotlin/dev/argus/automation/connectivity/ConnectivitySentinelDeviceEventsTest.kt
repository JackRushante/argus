package dev.argus.automation.connectivity

import dev.argus.automation.triggers.DeviceEventDemand
import dev.argus.automation.triggers.DeviceEventSource
import dev.argus.automation.triggers.deviceEventSource
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.4.1: la sentinella in primo piano serve anche i trigger di sistema senza Shizuku. */
class ConnectivitySentinelDeviceEventsTest {
    @Test
    fun `each system trigger maps to the source the service must register`() {
        assertEquals(DeviceEventSource.SCREEN, Trigger.Screen(ScreenEvent.ON).deviceEventSource())
        assertEquals(DeviceEventSource.BATTERY, Trigger.Battery(below = 10).deviceEventSource())
        assertEquals(
            DeviceEventSource.AUDIO_OUTPUT,
            Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED).deviceEventSource(),
        )
        assertEquals(DeviceEventSource.MODE_DND, Trigger.ModeChanged(ModeKind.DND).deviceEventSource())
        assertEquals(DeviceEventSource.MODE_RINGER, Trigger.ModeChanged(ModeKind.RINGER, "silent").deviceEventSource())
        assertEquals(DeviceEventSource.MEDIA, Trigger.Media(MediaPlayState.PLAYING).deviceEventSource())
        // Gli altri trigger non passano da qui (il Wi-Fi e l'alimentazione hanno la loro strada).
        assertNull(Trigger.Immediate.deviceEventSource())
        assertNull(Trigger.PhoneState(PhoneEvent.MISSED_CALL).deviceEventSource())
        assertNull(Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED).deviceEventSource())
    }

    @Test
    fun `an armed system trigger turns the sentinel on and publishes its source`() = runTest {
        val backend = RecordingBackend()
        val demand = DeviceEventDemand()
        val screen = rule("screen", Trigger.Screen(ScreenEvent.UNLOCKED))
        val battery = rule("battery", Trigger.Battery(below = 20))

        val report = ConnectivitySentinelCoordinator(
            RulesStore(listOf(screen, battery)),
            backend,
            deviceEvents = demand,
        ).reconcile()

        assertTrue(report.active)
        assertEquals(listOf(battery.id, screen.id), report.requiredBy)
        assertEquals(1, backend.startCalls)
        assertEquals(setOf(DeviceEventSource.SCREEN, DeviceEventSource.BATTERY), demand.sources.value)
    }

    @Test
    fun `a rule that is not armed does not request its source`() = runTest {
        val demand = DeviceEventDemand()
        val disabled = rule("media", Trigger.Media(MediaPlayState.PLAYING)).copy(enabled = false)
        val needsReview = rule("dnd", Trigger.ModeChanged(ModeKind.DND)).copy(status = AutomationStatus.NEEDS_REVIEW)

        val report = ConnectivitySentinelCoordinator(
            RulesStore(listOf(disabled, needsReview)),
            RecordingBackend(),
            deviceEvents = demand,
        ).reconcile()

        assertFalse(report.active)
        assertTrue(demand.sources.value.isEmpty())
    }

    @Test
    fun `the demand follows the rules while the service stays up for wifi`() = runTest {
        val backend = RecordingBackend()
        val demand = DeviceEventDemand()
        val wifi = rule("wifi", Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED))
        val ringer = rule("ringer", Trigger.ModeChanged(ModeKind.RINGER))
        val store = MutableRulesStore(listOf(wifi))
        val coordinator = ConnectivitySentinelCoordinator(store, backend, deviceEvents = demand)

        coordinator.reconcile()
        assertTrue(demand.sources.value.isEmpty())

        store.rules = listOf(wifi, ringer)
        coordinator.reconcile()
        assertEquals(setOf(DeviceEventSource.MODE_RINGER), demand.sources.value)

        // La regola sparisce: la sorgente si toglie, il servizio resta per il Wi-Fi.
        store.rules = listOf(wifi)
        val report = coordinator.reconcile()
        assertTrue(demand.sources.value.isEmpty())
        assertTrue(report.active)
        assertEquals(0, backend.stopCalls)

        store.rules = emptyList()
        coordinator.reconcile()
        assertEquals(1, backend.stopCalls)
    }

    @Test
    fun `a denied service names the system trigger rules like the wifi ones`() = runTest {
        val screen = rule("screen", Trigger.Screen(ScreenEvent.ON))
        val report = ConnectivitySentinelCoordinator(
            RulesStore(listOf(screen)),
            RecordingBackend(startSucceeds = false),
        ).reconcile()

        assertFalse(report.active)
        assertEquals(listOf(screen.id), report.failed)
    }

    private fun rule(id: String, trigger: Trigger) = Automation(
        id = AutomationId(id),
        name = id,
        createdBy = CreatedBy.USER,
        status = AutomationStatus.ARMED,
        trigger = trigger,
        actions = listOf(Action.ShowNotification("Argus", id)),
        enabled = true,
        approvalFingerprint = ApprovalFingerprint("a".repeat(64)),
    )

    private class RecordingBackend(private val startSucceeds: Boolean = true) : ConnectivitySentinelBackend {
        var startCalls = 0
        var stopCalls = 0
        override suspend fun start(): Boolean {
            startCalls += 1
            return startSucceeds
        }

        override suspend fun stop(): Boolean {
            stopCalls += 1
            return true
        }
    }

    private open class RulesStore(open var rules: List<Automation>) : AutomationStore {
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

    private class MutableRulesStore(initial: List<Automation>) : RulesStore(initial)
}
