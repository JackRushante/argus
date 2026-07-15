package dev.argus.automation

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.automation.connectivity.ConnectivityReconcileReport
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutomationEnablementCoordinatorTest {
    private val id = AutomationId("automation-1")

    @Test
    fun `scheduler exception after enable rolls back to disabled`() = runTest {
        val store = ToggleStore(enableResult = true)
        val runtime = RecordingRuntime { call ->
            if (call == 1) error("alarm manager unavailable") else report()
        }

        val result = AutomationEnablementCoordinator(store, runtime).setEnabled(id, true)

        assertEquals(EnablementResult.SchedulingFailed, result)
        assertEquals(AutomationStatus.DISABLED, store.currentStatus)
        assertEquals(1, store.disableCalls)
        assertEquals(2, runtime.reconcileCalls)
    }

    @Test
    fun `failed scheduler report after enable rolls back to disabled`() = runTest {
        val store = ToggleStore(enableResult = true)
        val runtime = RecordingRuntime { call ->
            if (call == 1) report(failed = listOf(id)) else report()
        }

        val result = AutomationEnablementCoordinator(store, runtime).setEnabled(id, true)

        assertEquals(EnablementResult.SchedulingFailed, result)
        assertEquals(AutomationStatus.DISABLED, store.currentStatus)
        assertEquals(1, store.disableCalls)
        assertEquals(2, runtime.reconcileCalls)
    }

    @Test
    fun `cancellation during enable reconcile still rolls back then propagates`() = runTest {
        val store = ToggleStore(enableResult = true)
        val runtime = RecordingRuntime { call ->
            if (call == 1) throw CancellationException("view model cleared") else report()
        }

        assertFailsWith<CancellationException> {
            AutomationEnablementCoordinator(store, runtime).setEnabled(id, true)
        }
        assertEquals(AutomationStatus.DISABLED, store.currentStatus)
        assertEquals(1, store.disableCalls)
        assertEquals(2, runtime.reconcileCalls)
    }

    @Test
    fun `review rejection never touches scheduler`() = runTest {
        val store = ToggleStore(enableResult = false)
        val runtime = RecordingRuntime { report() }

        val result = AutomationEnablementCoordinator(store, runtime).setEnabled(id, true)

        assertEquals(EnablementResult.ReviewRequired, result)
        assertEquals(0, store.disableCalls)
        assertEquals(0, runtime.reconcileCalls)
    }

    @Test
    fun `disable remains durable when scheduler cleanup is deferred`() = runTest {
        val store = ToggleStore(enableResult = true, initiallyEnabled = true)
        val runtime = RecordingRuntime { error("cleanup unavailable") }

        val result = AutomationEnablementCoordinator(store, runtime).setEnabled(id, false)

        assertEquals(EnablementResult.DisableCleanupDeferred, result)
        assertEquals(AutomationStatus.DISABLED, store.currentStatus)
        assertEquals(1, store.disableCalls)
        assertEquals(1, runtime.reconcileCalls)
    }

    @Test
    fun `connectivity registration failure after enable rolls back atomically`() = runTest {
        val store = ToggleStore(enableResult = true)
        val scheduler = RecordingRuntime { report() }
        val connectivity = RecordingConnectivityRuntime { call ->
            if (call == 1) {
                ConnectivityReconcileReport(listOf(id), active = false, failed = listOf(id))
            } else {
                ConnectivityReconcileReport(emptyList(), active = false)
            }
        }

        val result = AutomationEnablementCoordinator(store, scheduler, connectivity)
            .setEnabled(id, true)

        assertEquals(EnablementResult.SchedulingFailed, result)
        assertEquals(AutomationStatus.DISABLED, store.currentStatus)
        assertEquals(2, connectivity.reconcileCalls)
    }

    private fun report(failed: List<AutomationId> = emptyList()) = ReconcileReport(
        scheduled = emptyList(),
        cancelled = emptyList(),
        recovered = emptyList(),
        failed = failed,
    )
}

private class RecordingConnectivityRuntime(
    private val result: suspend (Int) -> ConnectivityReconcileReport,
) : ConnectivityTriggerRuntime {
    var reconcileCalls = 0
    override suspend fun reconcile(): ConnectivityReconcileReport = result(++reconcileCalls)
}

private class RecordingRuntime(
    private val reconcileResult: suspend (Int) -> ReconcileReport,
) : TimeAlarmRuntime {
    var reconcileCalls = 0

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport =
        reconcileResult(++reconcileCalls)

    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = error("not used")
}

private class ToggleStore(
    private val enableResult: Boolean,
    initiallyEnabled: Boolean = false,
) : AutomationStore {
    private val fingerprint = ApprovalFingerprint("0".repeat(64))
    private var current = automation(
        if (initiallyEnabled) AutomationStatus.ARMED else AutomationStatus.DISABLED,
    )
    var disableCalls = 0
        private set
    val currentStatus: AutomationStatus get() = current.status

    override suspend fun enableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean {
        if (!enableResult || current.approvalFingerprint != fingerprint) return false
        current = current.copy(status = AutomationStatus.ARMED, enabled = true)
        return true
    }
    override suspend fun disable(id: AutomationId) {
        disableCalls += 1
        current = current.copy(status = AutomationStatus.DISABLED, enabled = false)
    }
    override suspend fun get(id: AutomationId): Automation = current
    override suspend fun all(): List<Automation> = error("not used")
    override fun observeAll(): Flow<List<Automation>> = flowOf(emptyList())
    override suspend fun armed(): List<Automation> = error("not used")
    override suspend fun delete(id: AutomationId) = error("not used")
    override suspend fun disableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean {
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint
        ) return false
        disable(id)
        return true
    }
    override suspend fun markNeedsReview(id: AutomationId) = error("not used")
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = error("not used")
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = error("not used")
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = error("not used")
    override suspend fun lastFiredAt(id: AutomationId): Long? = error("not used")

    private fun automation(status: AutomationStatus) = Automation(
        id = AutomationId("automation-1"),
        name = "test",
        createdBy = CreatedBy.USER,
        status = status,
        trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
        actions = listOf(Action.ShowNotification("Argus", "test")),
        enabled = status == AutomationStatus.ARMED,
        approvalFingerprint = fingerprint,
    )
}
