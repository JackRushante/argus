package dev.argus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock

/** Gate P3-1A: usa provider Android reale senza mutare impostazioni o persistenza del device. */
@RunWith(AndroidJUnit4::class)
class ArgusMinimalStateReadInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            instrumentation.targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun batteryAndLocationConditionsReadOnlyTheirApprovedInputs(): Unit = runBlocking {
        assertEquals(ShizukuGatewayStatus.AUTHORIZED, services.shizukuGateway().status())
        val provider = services.deviceStateSnapshotProvider()

        val battery = signed(
            "p3-battery",
            Condition.StateEquals(StateKeys.BATTERY, CmpOp.GT, "-1"),
        )
        val batteryRequests = mutableListOf<StateReadRequest>()
        val batteryOutcomes = engine(battery).onTrigger(event("battery")) { request ->
            batteryRequests += request
            provider.current(request)
        }
        assertEquals(1, batteryOutcomes.size)
        assertEquals(
            StateReadRequest(keys = setOf(StateKeys.BATTERY)),
            batteryRequests.single(),
        )

        val initialLocation = provider.current(
            StateReadRequest(includeLocation = true),
        ).location
        assertNotNull(initialLocation)
        val locationPoint = requireNotNull(initialLocation)
        val location = signed(
            "p3-location",
            Condition.LocationIn(
                lat = locationPoint.lat,
                lng = locationPoint.lng,
                radiusM = 5_000.0,
            ),
        )
        val locationRequests = mutableListOf<StateReadRequest>()
        val locationOutcomes = engine(location).onTrigger(event("location")) { request ->
            locationRequests += request
            provider.current(request)
        }
        assertEquals(1, locationOutcomes.size)
        assertEquals(
            StateReadRequest(includeLocation = true),
            locationRequests.single(),
        )
    }

    private fun engine(automation: Automation) = Engine(
        store = SingleAutomationStore(automation),
        executor = ActionExecutor { _, _ -> ActionResult.Success },
        evaluator = ConditionEvaluator(Clock.systemUTC()),
        matcher = TriggerMatcher(),
        firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
        now = System::currentTimeMillis,
    )

    private fun signed(id: String, condition: Condition): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Notification(FIXTURE_PACKAGE),
            actions = listOf(Action.ShowNotification("fixture", "fixture")),
            conditions = condition,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private fun event(suffix: String) = TriggerEnvelope(
        TriggerEventId("p3-minimal-state:$suffix"),
        TriggerEvent.NotificationPosted(FIXTURE_PACKAGE),
    )

    private companion object {
        const val FIXTURE_PACKAGE = "dev.argus.fixture"
    }
}

private class SingleAutomationStore(private val automation: Automation) : AutomationStore {
    override suspend fun get(id: AutomationId): Automation? = automation.takeIf { it.id == id }
    override suspend fun all(): List<Automation> = listOf(automation)
    override fun observeAll(): Flow<List<Automation>> = flowOf(listOf(automation))
    override suspend fun armed(): List<Automation> = listOf(automation)
    override suspend fun delete(id: AutomationId) = Unit
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun enableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        FireClaimResult.Claimed
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null
}
