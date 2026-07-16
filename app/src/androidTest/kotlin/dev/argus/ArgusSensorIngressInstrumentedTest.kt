package dev.argus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Riproduce la catena sensore nello STESSO processo del test: arma una regola significant-motion
 * e chiama direttamente l'ingress, senza il callback fisico e senza il process-kill che il gate
 * via `am instrument` introduceva. Se il journal si popola qui, il bug sul campo era l'artefatto
 * del metodo; se resta vuoto, è un difetto reale ingress→Engine.
 */
@RunWith(AndroidJUnit4::class)
class ArgusSensorIngressInstrumentedTest {
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun significantMotionFlowsThroughProductionPipeline(): Unit = runBlocking {
        val store = services.automationStore()
        var automationId: AutomationId? = null
        try {
            val submission = services.approvalFlow().submit(
                CompileResult(
                    reply = "diagnostic",
                    draft = AutomationDraft(
                        name = "$E2E_PREFIX ${System.currentTimeMillis()}",
                        trigger = Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION),
                        actions = listOf(Action.ShowNotification("Argus", "movimento")),
                        cooldownMs = 60_000,
                    ),
                    metaError = null,
                ),
            )
            assertTrue("Draft sensore non pronto: $submission", submission is DraftSubmissionResult.Ready)
            val ready = submission as DraftSubmissionResult.Ready
            assertTrue("Draft sensore non armabile: ${ready.review.draft.issues}", ready.review.canArm)

            val snapshot = ready.review.draft.snapshot
            val arm = services.approvalFlow().arm(snapshot.id, snapshot.revision, snapshot.fingerprint)
            assertTrue("Arm sensore non riuscito: $arm", arm is FlowArmResult.Armed)
            automationId = (arm as FlowArmResult.Armed).automation.id

            // Callback fisico simulato: la stessa chiamata che fa il TriggerEventListener reale.
            services.sensorEventIngress().onSensorTriggered(SensorKind.SIGNIFICANT_MOTION)

            val fired = withTimeout(FIRE_TIMEOUT_MILLIS) {
                services.database().auditDao()
                    .observeLogForAutomation(automationId.value, 20)
                    .first { records -> records.any { it.kind == AuditKind.FIRED } }
                    .first { it.kind == AuditKind.FIRED }
            }
            val actions = services.database().executionJournalDao()
                .actions(requireNotNull(fired.executionId))
            assertEquals(1, actions.size)
            assertEquals("show_notification", actions.single().actionType)
            assertEquals(ActionJournalOutcome.SUCCEEDED, actions.single().outcome)
        } finally {
            withContext(NonCancellable) {
                automationId?.let { store.delete(it) }
                assertTrue(
                    "Cleanup automazione diagnostica sensore non completato",
                    store.all().none { it.name.startsWith(E2E_PREFIX) },
                )
            }
        }
    }

    private companion object {
        const val E2E_PREFIX = "Argus E2E Sensor"
        const val FIRE_TIMEOUT_MILLIS = 20_000L
    }
}
