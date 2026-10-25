package dev.argus.automation.connectivity

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
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

class ConnectivitySentinelCoordinatorTest {
    @Test
    fun `sentinel runs only for armed wifi or power rules`() = runTest {
        val backend = RecordingSentinelBackend()
        val wifi = rule("wifi", ConnMedium.WIFI)
        val power = rule("power", ConnMedium.POWER)
        val bluetooth = rule("bt", ConnMedium.BT)

        val started = ConnectivitySentinelCoordinator(RulesStore(listOf(wifi, bluetooth)), backend)
            .reconcile()
        assertTrue(started.active)
        assertEquals(listOf(wifi.id), started.requiredBy)
        assertEquals(1, backend.startCalls)

        val stopped = ConnectivitySentinelCoordinator(RulesStore(listOf(bluetooth)), backend)
            .reconcile()
        assertFalse(stopped.active)
        assertTrue(stopped.requiredBy.isEmpty())
        assertEquals(1, backend.stopCalls)

        val powerReport = ConnectivitySentinelCoordinator(RulesStore(listOf(power)), backend)
            .reconcile()
        assertTrue(powerReport.active)
        assertEquals(listOf(power.id), powerReport.requiredBy)
    }

    @Test
    fun `backend start failure names every affected rule`() = runTest {
        val wifi = rule("wifi", ConnMedium.WIFI)
        val power = rule("power", ConnMedium.POWER)
        val backend = RecordingSentinelBackend(startSucceeds = false)

        val report = ConnectivitySentinelCoordinator(RulesStore(listOf(wifi, power)), backend)
            .reconcile()

        assertFalse(report.active)
        assertEquals(listOf(power.id, wifi.id), report.failed.sortedBy { it.value })
    }

    // --- B20: servizio negato dal sistema, traccia e avviso -----------------------------------

    @Test
    fun `a denied service is audited once per rule and notified once per episode`() = runTest {
        val wifi = rule("wifi", ConnMedium.WIFI)
        val power = rule("power", ConnMedium.POWER)
        val bluetooth = rule("bt", ConnMedium.BT)
        val backend = RecordingSentinelBackend(startSucceeds = false)
        val audit = RecordingAudit()
        val notice = RecordingNotice()
        val coordinator = ConnectivitySentinelCoordinator(
            RulesStore(listOf(wifi, power, bluetooth)),
            backend,
            audit,
            notice,
            nowMillis = { 5_000L },
        )

        coordinator.reconcile()
        coordinator.reconcile()
        coordinator.reconcile()

        assertEquals(3, backend.startCalls, "ogni reconcile ritenta davvero l'avvio")
        assertEquals(
            listOf(power.id, wifi.id),
            audit.events.map { it.automationId }.sortedBy { it.value },
        )
        assertTrue(audit.events.all { it.kind == AuditKind.SCHEDULING_FAILED })
        assertTrue(audit.events.all { it.detail == "foreground_service_denied" })
        assertEquals(1, notice.shown)
        assertEquals(0, notice.cleared)
    }

    @Test
    fun `the notice is removed when the sentinel starts and a new denial opens a new episode`() = runTest {
        val wifi = rule("wifi", ConnMedium.WIFI)
        val backend = RecordingSentinelBackend(startSucceeds = false)
        val audit = RecordingAudit()
        val notice = RecordingNotice()
        val coordinator = ConnectivitySentinelCoordinator(RulesStore(listOf(wifi)), backend, audit, notice)

        coordinator.reconcile()
        assertEquals(1, notice.shown)

        // L'app torna in primo piano: il servizio parte, l'avviso sparisce.
        backend.startSucceeds = true
        val recovered = coordinator.reconcile()
        assertTrue(recovered.active)
        assertEquals(1, notice.cleared)

        // Un nuovo rifiuto è un episodio nuovo: traccia e avviso di nuovo.
        backend.startSucceeds = false
        coordinator.reconcile()
        assertEquals(2, notice.shown)
        assertEquals(2, audit.events.size)
    }

    @Test
    fun `a rule armed during an episode is traced once and the notice stays single`() = runTest {
        val wifi = rule("wifi", ConnMedium.WIFI)
        val power = rule("power", ConnMedium.POWER)
        val backend = RecordingSentinelBackend(startSucceeds = false)
        val audit = RecordingAudit()
        val notice = RecordingNotice()
        val store = MutableRulesStore(listOf(wifi))
        val coordinator = ConnectivitySentinelCoordinator(store, backend, audit, notice)

        coordinator.reconcile()
        store.rules = listOf(wifi, power)
        coordinator.reconcile()
        coordinator.reconcile()

        assertEquals(listOf(wifi.id, power.id), audit.events.map { it.automationId })
        assertEquals(1, notice.shown)
    }

    @Test
    fun `no armed wifi or power rule clears a leftover notice`() = runTest {
        val notice = RecordingNotice()
        val coordinator = ConnectivitySentinelCoordinator(
            RulesStore(listOf(rule("bt", ConnMedium.BT))),
            RecordingSentinelBackend(),
            RecordingAudit(),
            notice,
        )

        coordinator.reconcile()

        assertEquals(1, notice.cleared)
        assertEquals(0, notice.shown)
    }

    private fun rule(id: String, medium: ConnMedium): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Connectivity(medium, ConnState.CONNECTED),
            actions = listOf(Action.ShowNotification("Argus", id)),
            enabled = true,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }
}

private class RecordingSentinelBackend(
    var startSucceeds: Boolean = true,
) : ConnectivitySentinelBackend {
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

private class RulesStore(private val rules: List<Automation>) : AutomationStore {
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

private class RecordingAudit : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) {
        events += e
    }
}

private class RecordingNotice : ForegroundDeniedNotice {
    var shown = 0
    var cleared = 0
    override fun show() {
        shown += 1
    }

    override fun clear() {
        cleared += 1
    }
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
