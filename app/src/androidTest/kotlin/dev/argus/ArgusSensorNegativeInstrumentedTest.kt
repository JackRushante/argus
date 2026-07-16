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
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AutomationStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Negativi del gate sensore, sulla STESSA catena di produzione dell'ingress positivo
 * ([ArgusSensorIngressInstrumentedTest]) ma stesso processo, senza richiedere movimenti a Lorenzo.
 *
 * Provano le proprietà di sicurezza complementari a "il movimento fa scattare":
 *  - A: una regola DISABILITATA non esegue anche se arriva un callback fisico;
 *  - B: una regola CANCELLATA non esegue anche se arriva un callback fisico;
 *  - C: cancellare l'ultima regola di un kind DEREGISTRA il sensore fisico (nessun leak).
 *
 * Restano fuori portata (richiedono Lorenzo o tempo reale): il process-restart fisico con movimento
 * e la misura del consumo/tempo dell'FGS.
 */
@RunWith(AndroidJUnit4::class)
class ArgusSensorNegativeInstrumentedTest {
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    /** A — una regola disabilitata non deve scattare al callback del sensore. */
    @Test
    fun disabledRuleDoesNotFireOnSensorCallback(): Unit = runBlocking {
        val store = services.automationStore()
        var automationId: AutomationId? = null
        try {
            val id = armSensorRule()
            automationId = id
            store.disable(id)
            assertTrue(
                "Precondizione fallita: la regola è ancora ARMED dopo disable",
                store.armed().none { it.id == id },
            )

            services.sensorEventIngress().onSensorTriggered(SensorKind.SIGNIFICANT_MOTION)
            delay(SETTLE_MILLIS)

            val fired = firedCount(store, id)
            assertEquals("Una regola disabilitata non deve produrre FIRED", 0, fired)
        } finally {
            cleanup(store, automationId)
        }
    }

    /** B — una regola cancellata non deve scattare al callback del sensore. */
    @Test
    fun deletedRuleDoesNotFireOnSensorCallback(): Unit = runBlocking {
        val store = services.automationStore()
        var automationId: AutomationId? = null
        try {
            val id = armSensorRule()
            automationId = id
            store.delete(id)
            assertTrue(
                "Precondizione fallita: la regola è ancora presente dopo delete",
                store.all().none { it.id == id },
            )

            services.sensorEventIngress().onSensorTriggered(SensorKind.SIGNIFICANT_MOTION)
            delay(SETTLE_MILLIS)

            val fired = firedCount(store, id)
            assertEquals("Una regola cancellata non deve produrre FIRED", 0, fired)
        } finally {
            cleanup(store, automationId)
        }
    }

    /**
     * C — cancellare l'ultima regola di un kind deregistra il sensore fisico (no leak).
     * Usa il backend reale: `reconcile` registra davvero il SIGNIFICANT_MOTION del OnePlus, poi
     * dopo il delete un secondo `reconcile` lo cancella. Chiude la metà "no residuo" del gate
     * negativo senza attendere un callback fisico.
     */
    @Test
    fun deletingLastRuleDeregistersPhysicalSensor(): Unit = runBlocking {
        val store = services.automationStore()
        val runtime = services.sensorTriggerRuntime()
        var automationId: AutomationId? = null
        try {
            val id = armSensorRule()
            automationId = id

            val afterArm = runtime.reconcile()
            assertTrue(
                "Il reconcile non ha registrato SIGNIFICANT_MOTION: $afterArm",
                SensorKind.SIGNIFICANT_MOTION in afterArm.registeredKinds,
            )
            assertTrue(
                "La regola armata deve figurare in requiredBy: $afterArm",
                id in afterArm.requiredBy,
            )

            store.delete(id)

            val afterDelete = runtime.reconcile()
            assertTrue(
                "SIGNIFICANT_MOTION ancora registrato dopo delete (leak fisico): $afterDelete",
                SensorKind.SIGNIFICANT_MOTION !in afterDelete.registeredKinds,
            )
            assertTrue(
                "Il cleanup del sensore deve riuscire: $afterDelete",
                afterDelete.cleanupSucceeded,
            )
            assertTrue(
                "requiredBy deve essere vuoto dopo il delete: $afterDelete",
                afterDelete.requiredBy.isEmpty(),
            )
        } finally {
            cleanup(store, automationId)
            // Deregistrazione finale garantita: nessuna regola diagnostica -> stop FGS/sensore.
            withContext(NonCancellable) { runtime.reconcile() }
        }
    }

    private suspend fun armSensorRule(): AutomationId {
        val submission = services.approvalFlow().submit(
            CompileResult(
                reply = "diagnostic-negative",
                draft = AutomationDraft(
                    name = "$E2E_PREFIX ${System.currentTimeMillis()}",
                    trigger = Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION),
                    actions = listOf(Action.ShowNotification("Argus", "negativo")),
                    cooldownMs = 60_000,
                ),
                metaError = null,
            ),
        )
        val ready = submission as? DraftSubmissionResult.Ready
            ?: error("Draft sensore non pronto: $submission")
        require(ready.review.canArm) { "Draft sensore non armabile: ${ready.review.draft.issues}" }
        val snapshot = ready.review.draft.snapshot
        val arm = services.approvalFlow().arm(snapshot.id, snapshot.revision, snapshot.fingerprint)
        return (arm as? FlowArmResult.Armed)?.automation?.id
            ?: error("Arm sensore non riuscito: $arm")
    }

    private suspend fun firedCount(store: AutomationStore, id: AutomationId): Int =
        services.database().auditDao().forAutomation(id.value).count { it.kind == AuditKind.FIRED }

    private suspend fun cleanup(store: AutomationStore, id: AutomationId?) {
        withContext(NonCancellable) {
            id?.let { store.delete(it) }
            assertTrue(
                "Cleanup automazione negativa non completato",
                store.all().none { it.name.startsWith(E2E_PREFIX) },
            )
        }
    }

    private companion object {
        const val E2E_PREFIX = "Argus E2E SensorNeg"
        const val SETTLE_MILLIS = 2_000L
    }
}
