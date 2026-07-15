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
    private val startSucceeds: Boolean = true,
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
