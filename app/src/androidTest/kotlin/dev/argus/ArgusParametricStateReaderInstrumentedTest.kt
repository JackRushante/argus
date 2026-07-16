package dev.argus

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.automation.StateQueryProbeResult
import dev.argus.automation.StateQueryProbeRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import kotlin.math.abs

/** Gate P3-1B: reader reale, confronto con API Android indipendente e uso Engine. */
@RunWith(AndroidJUnit4::class)
class ArgusParametricStateReaderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            instrumentation.targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun dumpsysBatteryVoltageIsProbedComparedAndUsedWithoutLeakingSample(): Unit = runBlocking {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val condition = Condition.StateCompare(
            query,
            StateValueType.NUMBER,
            CmpOp.GT,
            "0",
        )
        assertEquals(
            StateQueryProbeResult.AVAILABLE,
            services.stateQueryProbe().probe(
                StateQueryProbeRequest(condition.query, condition.valueType),
            ),
        )

        val provider = services.deviceStateSnapshotProvider()
        val state = provider.current(StateReadRequest(queries = setOf(query)))
        val readerVoltage = state.queryValues[query.canonicalId]?.toIntOrNull()
        assertNotNull(readerVoltage)

        val context = instrumentation.targetContext
        val battery = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val frameworkVoltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
        assertNotNull(frameworkVoltage)
        assertTrue(abs(requireNotNull(readerVoltage) - requireNotNull(frameworkVoltage)) <= 250)

        val unsigned = Automation(
            id = AutomationId("p3-voltage"),
            name = "p3-voltage",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Notification(FIXTURE_PACKAGE),
            actions = listOf(Action.ShowNotification("fixture", "fixture")),
            conditions = condition,
        )
        val automation = unsigned.copy(
            approvalFingerprint = ApprovalFingerprints.of(unsigned),
        )
        val requests = mutableListOf<StateReadRequest>()
        val outcomes = Engine(
            store = SingleAutomationStore(automation),
            executor = ActionExecutor { _, _ -> ActionResult.Success },
            evaluator = ConditionEvaluator(Clock.systemUTC()),
            matcher = TriggerMatcher(),
            firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
            now = System::currentTimeMillis,
        ).onTrigger(
            TriggerEnvelope(
                TriggerEventId("p3-voltage:event"),
                TriggerEvent.NotificationPosted(FIXTURE_PACKAGE),
            ),
        ) { request ->
            requests += request
            provider.current(request)
        }

        assertEquals(1, outcomes.size)
        assertEquals(StateReadRequest(queries = setOf(query)), requests.single())
    }

    private companion object {
        const val FIXTURE_PACKAGE = "dev.argus.fixture"
    }
}
