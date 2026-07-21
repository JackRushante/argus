package dev.argus.automation

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.automation.connectivity.ConnectivityReconcileReport
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.geofence.GeofenceReconcileReport
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `enable review rejection records ENABLE_FAILED review_required`() = runTest {
        val store = ToggleStore(enableResult = false)
        val runtime = RecordingRuntime { report() }
        val audit = EnableAuditRecorder()

        AutomationEnablementCoordinator(store, runtime, audit = audit).setEnabled(id, true)

        val event = audit.events.single { it.kind == AuditKind.ENABLE_FAILED }
        assertEquals("review_required", event.detail)
        assertEquals(id, event.automationId)
    }

    @Test
    fun `enable scheduling failure records ENABLE_FAILED scheduling_failed`() = runTest {
        val store = ToggleStore(enableResult = true)
        val runtime = RecordingRuntime { call ->
            if (call == 1) report(failed = listOf(id)) else report()
        }
        val audit = EnableAuditRecorder()

        val result = AutomationEnablementCoordinator(store, runtime, audit = audit).setEnabled(id, true)

        assertEquals(EnablementResult.SchedulingFailed, result)
        val event = audit.events.single { it.kind == AuditKind.ENABLE_FAILED }
        assertEquals("scheduling_failed", event.detail)
        assertEquals(id, event.automationId)
    }

    @Test
    fun `user disable records RULE_DISABLED with the user reason`() = runTest {
        val store = ToggleStore(enableResult = true, initiallyEnabled = true)
        val runtime = RecordingRuntime { report() }
        val audit = EnableAuditRecorder()

        val result = AutomationEnablementCoordinator(store, runtime, audit = audit)
            .setEnabled(id, false)

        assertEquals(EnablementResult.Updated, result)
        val event = audit.events.single { it.kind == AuditKind.RULE_DISABLED }
        assertEquals("user", event.detail)
        assertEquals(id, event.automationId)
    }

    @Test
    fun `deferred disable cleanup still records RULE_DISABLED because the store is disabled`() = runTest {
        val store = ToggleStore(enableResult = true, initiallyEnabled = true)
        val runtime = RecordingRuntime { error("cleanup unavailable") }
        val audit = EnableAuditRecorder()

        val result = AutomationEnablementCoordinator(store, runtime, audit = audit)
            .setEnabled(id, false)

        assertEquals(EnablementResult.DisableCleanupDeferred, result)
        val event = audit.events.single { it.kind == AuditKind.RULE_DISABLED }
        assertEquals("user", event.detail)
    }

    @Test
    fun `user enable records RULE_ENABLED with the user reason`() = runTest {
        val store = ToggleStore(enableResult = true)
        val runtime = RecordingRuntime { report() }
        val audit = EnableAuditRecorder()

        val result = AutomationEnablementCoordinator(store, runtime, audit = audit)
            .setEnabled(id, true)

        assertEquals(EnablementResult.Updated, result)
        val event = audit.events.single { it.kind == AuditKind.RULE_ENABLED }
        assertEquals("user", event.detail)
        assertEquals(id, event.automationId)
    }

    @Test
    fun `rolled back enable never records RULE_ENABLED`() = runTest {
        val store = ToggleStore(enableResult = true)
        val runtime = RecordingRuntime { call ->
            if (call == 1) report(failed = listOf(id)) else report()
        }
        val audit = EnableAuditRecorder()

        val result = AutomationEnablementCoordinator(store, runtime, audit = audit)
            .setEnabled(id, true)

        assertEquals(EnablementResult.SchedulingFailed, result)
        assertTrue(audit.events.none { it.kind == AuditKind.RULE_ENABLED })
        // Il rollback resta tracciato dal solo ENABLE_FAILED: nessun doppio evento ambiguo.
        assertTrue(audit.events.none { it.kind == AuditKind.RULE_DISABLED })
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

    @Test
    fun `geofence registration failure after enable rolls back and cleans the pending intent`() = runTest {
        val store = ToggleStore(enableResult = true)
        val scheduler = RecordingRuntime { report() }
        val geofence = RecordingGeofenceRuntime { call ->
            if (call == 1) {
                GeofenceReconcileReport(requiredBy = listOf(id), failed = listOf(id))
            } else {
                GeofenceReconcileReport()
            }
        }

        val result = AutomationEnablementCoordinator(
            store,
            scheduler,
            geofence = geofence,
        ).setEnabled(id, true)

        assertEquals(EnablementResult.SchedulingFailed, result)
        assertEquals(AutomationStatus.DISABLED, store.currentStatus)
        assertEquals(2, geofence.reconcileCalls)
    }

    @Test
    fun `re-enabling a consumed immediate rule re-fires and re-consumes it`() = runTest {
        val store = ToggleStore(enableResult = true, trigger = Trigger.Immediate)
        val scheduler = RecordingRuntime { report() }
        val audit = EnableAuditRecorder()
        val harness = ImmediateReArmHarness(store, audit)

        val result = AutomationEnablementCoordinator(
            store,
            scheduler,
            audit = audit,
            immediateReArm = harness.reArm,
        ).setEnabled(id, true)

        assertEquals(EnablementResult.Updated, result)
        // Ha dispatchato un ImmediateFired attraverso il percorso reale del registrar.
        assertEquals(1, harness.dispatched.size)
        val envelope = harness.dispatched.single()
        assertTrue(envelope.id.value.startsWith("immediate:"), envelope.id.value)
        val event = envelope.event
        assertTrue(event is TriggerEvent.ImmediateFired)
        assertEquals(id, event.automationId)
        // La regola one-shot si è ri-consumata: torna DISABLED con l'evento one_shot_consumed.
        assertEquals(AutomationStatus.DISABLED, store.currentStatus)
        assertTrue(audit.events.any { it.kind == AuditKind.RULE_ENABLED })
        val consumed = audit.events.single { it.kind == AuditKind.RULE_DISABLED }
        assertEquals("one_shot_consumed", consumed.detail)
        assertEquals(id, consumed.automationId)
    }

    @Test
    fun `re-enabling an immediate rule twice fires twice with unique ids`() = runTest {
        val store = ToggleStore(enableResult = true, trigger = Trigger.Immediate)
        val scheduler = RecordingRuntime { report() }
        val audit = EnableAuditRecorder()
        val harness = ImmediateReArmHarness(store, audit)
        val coordinator = AutomationEnablementCoordinator(
            store,
            scheduler,
            audit = audit,
            immediateReArm = harness.reArm,
        )

        assertEquals(EnablementResult.Updated, coordinator.setEnabled(id, true))
        assertEquals(EnablementResult.Updated, coordinator.setEnabled(id, true))

        assertEquals(2, harness.dispatched.size, "ogni riattivazione = un fire in più")
        assertEquals(
            2,
            harness.dispatched.map { it.id.value }.toSet().size,
            "id univoco per-arm: nessuna dedup permanente",
        )
    }

    @Test
    fun `re-enabling a non-immediate rule never dispatches an immediate`() = runTest {
        val store = ToggleStore(enableResult = true, trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"))
        val scheduler = RecordingRuntime { report() }
        val audit = EnableAuditRecorder()
        val harness = ImmediateReArmHarness(store, audit)

        val result = AutomationEnablementCoordinator(
            store,
            scheduler,
            audit = audit,
            immediateReArm = harness.reArm,
        ).setEnabled(id, true)

        assertEquals(EnablementResult.Updated, result)
        assertTrue(harness.rearmCalls.isEmpty(), "un trigger non-immediate non deve ri-armare l'immediato")
        assertTrue(harness.dispatched.isEmpty())
    }

    @Test
    fun `enable that fails scheduling rolls back and never fires the immediate`() = runTest {
        val store = ToggleStore(enableResult = true, trigger = Trigger.Immediate)
        val scheduler = RecordingRuntime { call ->
            if (call == 1) report(failed = listOf(id)) else report()
        }
        val audit = EnableAuditRecorder()
        val harness = ImmediateReArmHarness(store, audit)

        val result = AutomationEnablementCoordinator(
            store,
            scheduler,
            audit = audit,
            immediateReArm = harness.reArm,
        ).setEnabled(id, true)

        assertEquals(EnablementResult.SchedulingFailed, result)
        assertEquals(AutomationStatus.DISABLED, store.currentStatus)
        assertTrue(harness.rearmCalls.isEmpty(), "un enable rollato indietro non deve firare l'immediato")
        assertTrue(harness.dispatched.isEmpty())
    }

    private fun report(failed: List<AutomationId> = emptyList()) = ReconcileReport(
        scheduled = emptyList(),
        cancelled = emptyList(),
        recovered = emptyList(),
        failed = failed,
    )
}

/**
 * Guida il ri-arm immediato attraverso il percorso REALE del registrar ([registerImmediate]):
 * così le asserzioni su dispatch, id univoco e `one_shot_consumed` provengono dal codice di
 * produzione, non da un fake che li fabbrica.
 */
private class ImmediateReArmHarness(
    store: AutomationStore,
    audit: AuditSink,
) {
    val dispatched = mutableListOf<TriggerEnvelope>()
    val rearmCalls = mutableListOf<AutomationId>()
    private var tick = 0L

    private val registrar = AndroidArmedAutomationRegistrar(
        coordinator = TimeAlarmCoordinator(
            store = store,
            state = ReArmTimeAlarmStateStore(),
            backend = ReArmTimeAlarmBackend(),
            dispatcher = { },
            now = { java.time.Instant.parse("2026-07-14T08:00:00Z") },
        ),
        store = store,
        snapshots = FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = AndroidCapabilityProbe.KNOWN_TOOLS,
                availableCapabilities = setOf(CapabilityIds.TRIGGER_IMMEDIATE),
                whitelistedConversationIds = emptySet(),
            )
        },
        immediateDispatcher = { dispatched += it },
        // Clock crescente: ogni RE-ARM produce un id univoco, come in produzione.
        now = { java.time.Instant.ofEpochMilli(1_000L + tick++) },
        audit = audit,
        oneShotConsumptions = ProcessOneShotConsumptionRegistry(),
    )

    val reArm = ImmediateReArm { automation ->
        rearmCalls += automation.id
        registrar.register(automation)
    }
}

private class ReArmTimeAlarmStateStore : TimeAlarmStateStore {
    private val alarms = mutableMapOf<AutomationId, ScheduledTimeAlarm>()
    override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? = alarms[automationId]
    override suspend fun all(): List<ScheduledTimeAlarm> = alarms.values.toList()
    override suspend fun upsert(alarm: ScheduledTimeAlarm) { alarms[alarm.automationId] = alarm }
    override suspend fun delete(automationId: AutomationId) { alarms.remove(automationId) }
}

private class ReArmTimeAlarmBackend : TimeAlarmBackend {
    override fun canScheduleExact(): Boolean = false
    override fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode =
        ScheduledAlarmMode.INEXACT
    override fun cancel(automationId: AutomationId) = Unit
}

private class EnableAuditRecorder : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) { events += e }
}

private class RecordingGeofenceRuntime(
    private val result: suspend (Int) -> GeofenceReconcileReport,
) : GeofenceTriggerRuntime {
    var reconcileCalls = 0
    override suspend fun reconcile(recreateOsRegistrations: Boolean): GeofenceReconcileReport =
        result(++reconcileCalls)
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
    private val trigger: Trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
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
        trigger = trigger,
        actions = listOf(Action.ShowNotification("Argus", "test")),
        enabled = status == AutomationStatus.ARMED,
        approvalFingerprint = fingerprint,
    )
}
