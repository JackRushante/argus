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
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Gate non distruttivo: Room -> registrar -> FGS reale -> ingress -> Engine -> Shizuku. */
@RunWith(AndroidJUnit4::class)
class ArgusConnectivityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            instrumentation.targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun powerRuleStartsSentinelAndRunsThroughProductionPipeline(): Unit = runBlocking {
        val store = services.automationStore()
        val existingConnectivity = store.armed().filter { it.trigger is Trigger.Connectivity }
        assumeTrue(
            "Gate saltato: esistono regole Connectivity reali e un evento sintetico non deve toccarle",
            existingConnectivity.isEmpty(),
        )

        var automationId: AutomationId? = null
        try {
            val submission = services.approvalFlow().submit(
                CompileResult(
                    reply = "diagnostic",
                    draft = AutomationDraft(
                        name = "$E2E_AUTOMATION_PREFIX ${System.currentTimeMillis()}",
                        trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
                        actions = listOf(Action.RunShell("/system/bin/true")),
                    ),
                    metaError = null,
                ),
            )
            assertTrue("Draft Connectivity non pronto: $submission", submission is DraftSubmissionResult.Ready)
            val ready = submission as DraftSubmissionResult.Ready
            assertTrue(
                "Draft Connectivity non armabile: ${ready.review.draft.issues}",
                ready.review.canArm,
            )

            val snapshot = ready.review.draft.snapshot
            val arm = services.approvalFlow().arm(
                snapshot.id,
                snapshot.revision,
                snapshot.fingerprint,
            )
            assertTrue("Arm Connectivity non riuscito: $arm", arm is FlowArmResult.Armed)
            automationId = (arm as FlowArmResult.Armed).automation.id

            withTimeout(SENTINEL_TIMEOUT_MILLIS) {
                services.connectivitySentinelStatus().active.first { it }
            }

            // Stato di base non armabile dal test; poi un solo bordo CONNECTED deterministico.
            services.connectivityEventIngress().observe(
                medium = ConnMedium.POWER,
                connectionState = ConnState.DISCONNECTED,
                name = null,
                sourceIdentity = DIAGNOSTIC_SOURCE,
                atMillis = System.currentTimeMillis(),
                initial = true,
            )
            services.connectivityEventIngress().observe(
                medium = ConnMedium.POWER,
                connectionState = ConnState.CONNECTED,
                name = null,
                sourceIdentity = DIAGNOSTIC_SOURCE,
                atMillis = System.currentTimeMillis() + 1,
            )

            val fired = withTimeout(FIRE_TIMEOUT_MILLIS) {
                services.database().auditDao()
                    .observeLogForAutomation(automationId.value, 20)
                    .first { records -> records.any { it.kind == AuditKind.FIRED } }
                    .first { it.kind == AuditKind.FIRED }
            }
            val actions = services.database().executionJournalDao()
                .actions(requireNotNull(fired.executionId))
            assertEquals(1, actions.size)
            assertEquals("run_shell", actions.single().actionType)
            assertEquals(ActionJournalOutcome.SUCCEEDED, actions.single().outcome)
        } finally {
            withContext(NonCancellable) {
                automationId?.let { store.delete(it) }
                val report = services.connectivityTriggerRuntime().reconcile()
                assertFalse(
                    "La regola diagnostica è rimasta nel requisito sentinella",
                    automationId != null && automationId in report.requiredBy,
                )
                assertTrue(
                    "Cleanup automazione diagnostica Connectivity non completato",
                    store.all().none { it.name.startsWith(E2E_AUTOMATION_PREFIX) },
                )
                if (report.requiredBy.isEmpty()) {
                    withTimeout(SENTINEL_TIMEOUT_MILLIS) {
                        services.connectivitySentinelStatus().active.first { !it }
                    }
                }
            }
        }
    }

    private companion object {
        const val E2E_AUTOMATION_PREFIX = "Argus E2E Connectivity"
        const val DIAGNOSTIC_SOURCE = "instrumentation-power"
        const val SENTINEL_TIMEOUT_MILLIS = 10_000L
        const val FIRE_TIMEOUT_MILLIS = 20_000L
    }
}
