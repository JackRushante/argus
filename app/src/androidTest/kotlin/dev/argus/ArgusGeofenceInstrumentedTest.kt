package dev.argus

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
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

/** Gate non distruttivo: Room -> LocationManager reale -> ingress -> Engine -> Shizuku. */
@RunWith(AndroidJUnit4::class)
class ArgusGeofenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun geofenceRegistersWithFrameworkAndRunsThroughProductionPipeline(): Unit = runBlocking {
        assumeTrue("ACCESS_FINE_LOCATION non concesso", granted(Manifest.permission.ACCESS_FINE_LOCATION))
        assumeTrue(
            "ACCESS_BACKGROUND_LOCATION non concesso",
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )
        val store = services.automationStore()
        assumeTrue(
            "Gate saltato: esistono regole Geofence reali e il segnale sintetico non deve toccarle",
            store.armed().none { it.trigger is Trigger.Geofence },
        )

        var automationId: AutomationId? = null
        try {
            val submission = services.approvalFlow().submit(
                CompileResult(
                    reply = "diagnostic",
                    draft = AutomationDraft(
                        name = "$E2E_AUTOMATION_PREFIX ${System.currentTimeMillis()}",
                        // Punto deliberatamente non personale e lontano: il framework registra,
                        // ma non genera un ENTER iniziale che possa competere col gate sintetico.
                        trigger = Trigger.Geofence(
                            lat = 89.0,
                            lng = 0.0,
                            radiusM = 150.0,
                            transition = Transition.EXIT,
                        ),
                        actions = listOf(Action.RunShell("/system/bin/true")),
                    ),
                    metaError = null,
                ),
            )
            assertTrue("Draft Geofence non pronto: $submission", submission is DraftSubmissionResult.Ready)
            val ready = submission as DraftSubmissionResult.Ready
            assertTrue("Draft Geofence non armabile: ${ready.review.draft.issues}", ready.review.canArm)

            val snapshot = ready.review.draft.snapshot
            val arm = services.approvalFlow().arm(
                snapshot.id,
                snapshot.revision,
                snapshot.fingerprint,
            )
            assertTrue("Arm Geofence non riuscito: $arm", arm is FlowArmResult.Armed)
            val armed = (arm as FlowArmResult.Armed).automation
            automationId = armed.id
            assertTrue(requireNotNull(services.geofenceStateStore().get(armed.id)).active)

            assertTrue(
                "Il primo EXIT diagnostico deve essere consegnato",
                services.geofenceEventIngress().onTransition(
                    armed.id,
                    requireNotNull(armed.approvalFingerprint),
                    Transition.EXIT,
                ),
            )

            val fired = withTimeout(FIRE_TIMEOUT_MILLIS) {
                services.database().auditDao()
                    .observeLogForAutomation(armed.id.value, 20)
                    .first { records -> records.any { it.kind == AuditKind.FIRED } }
                    .first { it.kind == AuditKind.FIRED }
            }
            val actions = services.database().executionJournalDao()
                .actions(requireNotNull(fired.executionId))
            assertEquals(1, actions.size)
            assertEquals("run_shell", actions.single().actionType)
            assertEquals(ActionJournalOutcome.SUCCEEDED, actions.single().outcome)

            assertFalse(
                "Un callback EXIT duplicato non deve rieseguire la regola",
                services.geofenceEventIngress().onTransition(
                    armed.id,
                    requireNotNull(armed.approvalFingerprint),
                    Transition.EXIT,
                ),
            )
        } finally {
            withContext(NonCancellable) {
                automationId?.let { store.delete(it) }
                val report = services.geofenceTriggerRuntime()
                    .reconcile(recreateOsRegistrations = false)
                assertFalse(
                    "La regola diagnostica è rimasta registrata",
                    automationId != null && automationId in report.requiredBy,
                )
                assertTrue(
                    "Cleanup automazione diagnostica Geofence non completato",
                    store.all().none { it.name.startsWith(E2E_AUTOMATION_PREFIX) },
                )
                automationId?.let {
                    assertFalse(it in services.geofenceStateStore().knownIds())
                }
            }
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val E2E_AUTOMATION_PREFIX = "Argus E2E Geofence"
        const val FIRE_TIMEOUT_MILLIS = 20_000L
    }
}
