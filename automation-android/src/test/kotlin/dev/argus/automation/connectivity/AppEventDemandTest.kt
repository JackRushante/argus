package dev.argus.automation.connectivity

import dev.argus.automation.triggers.DeviceEventDemand
import dev.argus.automation.triggers.DeviceEventSource
import dev.argus.automation.triggers.deviceEventSource
import dev.argus.engine.model.Action
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
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
import kotlin.test.assertTrue

/** 0.5.1: le regole «app aperta/chiusa» accendono la sentinella e dicono quali app osservare. */
class AppEventDemandTest {
    private val youtube = "com.google.android.youtube"
    private val chrome = "com.android.chrome"

    @Test
    fun `an app event trigger maps to the app source`() {
        assertEquals(DeviceEventSource.APP_EVENT, Trigger.AppEvent(youtube, AppEventKind.OPENED).deviceEventSource())
        assertEquals(DeviceEventSource.APP_EVENT, Trigger.AppEvent(youtube, AppEventKind.CLOSED).deviceEventSource())
    }

    @Test
    fun `armed app rules turn the sentinel on and publish the source and the watched packages`() = runTest {
        val backend = RecordingBackend()
        val demand = DeviceEventDemand()
        val open = rule("open", Trigger.AppEvent(youtube, AppEventKind.OPENED))
        val close = rule("close", Trigger.AppEvent(youtube, AppEventKind.CLOSED))
        val other = rule("other", Trigger.AppEvent(chrome, AppEventKind.OPENED))

        val report = ConnectivitySentinelCoordinator(
            RulesStore(listOf(open, close, other)),
            backend,
            deviceEvents = demand,
        ).reconcile()

        assertTrue(report.active)
        assertEquals(listOf(close.id, open.id, other.id).sortedBy { it.value }, report.requiredBy)
        assertEquals(1, backend.startCalls)
        assertEquals(setOf(DeviceEventSource.APP_EVENT), demand.sources.value)
        assertEquals(setOf(youtube, chrome), demand.appPackages.value)
    }

    @Test
    fun `without an armed app rule nothing is read and no package is watched`() = runTest {
        val demand = DeviceEventDemand()
        val disabled = rule("disabled", Trigger.AppEvent(youtube, AppEventKind.OPENED)).copy(enabled = false)
        val needsReview = rule("review", Trigger.AppEvent(chrome, AppEventKind.CLOSED))
            .copy(status = AutomationStatus.NEEDS_REVIEW)
        val screen = rule("screen", Trigger.Screen(ScreenEvent.ON))

        ConnectivitySentinelCoordinator(
            RulesStore(listOf(disabled, needsReview, screen)),
            RecordingBackend(),
            deviceEvents = demand,
        ).reconcile()

        assertEquals(setOf(DeviceEventSource.SCREEN), demand.sources.value)
        assertTrue(demand.appPackages.value.isEmpty())
    }

    @Test
    fun `the watched packages follow the rules and the source goes away with the last one`() = runTest {
        val demand = DeviceEventDemand()
        val store = RulesStore(listOf(rule("a", Trigger.AppEvent(youtube, AppEventKind.OPENED))))
        val coordinator = ConnectivitySentinelCoordinator(store, RecordingBackend(), deviceEvents = demand)

        coordinator.reconcile()
        assertEquals(setOf(youtube), demand.appPackages.value)

        store.rules = listOf(
            rule("a", Trigger.AppEvent(youtube, AppEventKind.OPENED)),
            rule("b", Trigger.AppEvent(chrome, AppEventKind.CLOSED)),
        )
        coordinator.reconcile()
        assertEquals(setOf(youtube, chrome), demand.appPackages.value)

        store.rules = emptyList()
        coordinator.reconcile()
        assertTrue(demand.appPackages.value.isEmpty())
        assertTrue(demand.sources.value.isEmpty())
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

    private class RecordingBackend : ConnectivitySentinelBackend {
        var startCalls = 0
        override suspend fun start(): Boolean {
            startCalls += 1
            return true
        }

        override suspend fun stop(): Boolean = true
    }

    private class RulesStore(var rules: List<Automation>) : AutomationStore {
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
}
