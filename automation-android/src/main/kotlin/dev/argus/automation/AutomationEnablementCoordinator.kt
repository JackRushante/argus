package dev.argus.automation

import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.NoopAuditSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import dev.argus.automation.geofence.NoopGeofenceTriggerRuntime
import dev.argus.automation.sensor.NoopSensorTriggerRuntime
import dev.argus.automation.sensor.SensorTriggerRuntime

sealed interface EnablementResult {
    data object Updated : EnablementResult
    data object ReviewRequired : EnablementResult
    data object SchedulingFailed : EnablementResult
    data object DisableCleanupDeferred : EnablementResult
}

/**
 * Ri-arma un trigger [Trigger.Immediate] quando la regola one-shot viene RI-abilitata dalla lista.
 *
 * Un immediate fira una sola volta all'arm iniziale e si auto-consuma (disable). Riabilitarlo deve
 * quindi ri-firare + ri-consumare, come chiede Lorenzo ("run again" alla Tasker). In produzione è
 * implementato dal percorso `registerImmediate` del registrar (dispatch al motore via FGS +
 * `disableIfApproved`), iniettato in [ArgusModule]. Interfaccia stretta apposta: evita di iniettare
 * l'intero registrar nel coordinator e qualunque ciclo Hilt.
 */
fun interface ImmediateReArm {
    /** @return true se il ri-arm immediato è partito (dispatch avviato); false se non applicabile. */
    suspend fun rearm(automation: Automation): Boolean
}

/** Default per i caller/test esistenti: nessun ri-arm. */
object NoopImmediateReArm : ImmediateReArm {
    override suspend fun rearm(automation: Automation): Boolean = false
}

/** Mantiene stato Room e registrazione AlarmManager coerenti anche se il reconcile fallisce. */
@Singleton
class AutomationEnablementCoordinator @Inject constructor(
    private val store: AutomationStore,
    private val scheduler: TimeAlarmRuntime,
    private val connectivity: ConnectivityTriggerRuntime = NoopConnectivityTriggerRuntime,
    private val geofence: GeofenceTriggerRuntime = NoopGeofenceTriggerRuntime,
    private val sensor: SensorTriggerRuntime = NoopSensorTriggerRuntime,
    private val audit: AuditSink = NoopAuditSink,
    private val immediateReArm: ImmediateReArm = NoopImmediateReArm,
) {
    private val mutex = Mutex()

    suspend fun setEnabled(id: AutomationId, enabled: Boolean): EnablementResult = mutex.withLock {
        val current = store.get(id) ?: return@withLock EnablementResult.ReviewRequired
        val fingerprint = current.approvalFingerprint
            ?: return@withLock EnablementResult.ReviewRequired
        if (!enabled) {
            if (current.status == AutomationStatus.DISABLED && !current.enabled) {
                return@withLock EnablementResult.Updated
            }
            if (!store.disableIfApproved(id, fingerprint)) {
                return@withLock EnablementResult.ReviewRequired
            }
            // Il disable è già durevole in Room: l'evento va emesso anche se la pulizia
            // runtime sotto viene rinviata (DisableCleanupDeferred).
            recordLifecycle(id, AuditKind.RULE_DISABLED, "user")
            return@withLock try {
                val schedulerClean = runCleanupReconcile()
                if (schedulerClean) EnablementResult.Updated
                else EnablementResult.DisableCleanupDeferred
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                EnablementResult.DisableCleanupDeferred
            }
        }

        if (current.status == AutomationStatus.ARMED && current.enabled) {
            return@withLock EnablementResult.Updated
        }
        if (!store.enableIfApproved(id, fingerprint)) {
            recordEnableFailed(id, "review_required")
            return@withLock EnablementResult.ReviewRequired
        }
        return@withLock try {
            val report = scheduler.reconcile(ReconcileReason.CAPABILITY_CHANGED)
            val connectivityReport = connectivity.reconcile()
            val geofenceReport = geofence.reconcile(recreateOsRegistrations = false)
            val sensorReport = sensor.reconcile()
            if (id in report.failed || id in connectivityReport.failed ||
                id in geofenceReport.failed ||
                id in sensorReport.failed || id in sensorReport.needsReview
            ) {
                rollbackEnable(id, fingerprint)
                recordEnableFailed(id, "scheduling_failed")
                EnablementResult.SchedulingFailed
            } else {
                // Solo l'esito finale riuscito è un RULE_ENABLED: il rollback resta
                // tracciato dal solo ENABLE_FAILED, senza doppi eventi ambigui.
                recordLifecycle(id, AuditKind.RULE_ENABLED, "user")
                // Il trigger immediato fira solo all'arm e si auto-consuma: riabilitarlo deve
                // ri-firare + ri-consumare ("run again"). Gli altri trigger restano gestiti dai
                // reconcile sopra e non vanno ri-armati qui.
                if (current.trigger is Trigger.Immediate) {
                    rearmImmediate(current)
                }
                EnablementResult.Updated
            }
        } catch (error: CancellationException) {
            rollbackEnable(id, fingerprint)
            throw error
        } catch (_: Exception) {
            rollbackEnable(id, fingerprint)
            recordEnableFailed(id, "scheduling_failed")
            EnablementResult.SchedulingFailed
        }
    }

    /**
     * Ri-arma l'immediato riusando il percorso del registrar (`registerImmediate`): dispatch al
     * motore e ri-consumo one-shot. In produzione il registrar sgancia il lavoro sull'FGS e torna
     * subito, quindi non blocca la coroutine di enable. Un guasto qui non invalida un enable già
     * durevole: la regola resta legittimamente armata, semplicemente non ha ri-firato.
     */
    private suspend fun rearmImmediate(automation: Automation) {
        try {
            immediateReArm.rearm(automation)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // L'enable è già durevole in Room; non rieseguire né rollbackare per un fire mancato.
        }
    }

    private suspend fun recordEnableFailed(id: AutomationId, reason: String) =
        recordLifecycle(id, AuditKind.ENABLE_FAILED, reason)

    private suspend fun recordLifecycle(id: AutomationId, kind: AuditKind, reason: String) {
        try {
            audit.record(
                AuditEvent(
                    id,
                    kind,
                    System.currentTimeMillis(),
                    detail = reason,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il logging non deve cambiare l'esito dell'operazione.
        }
    }

    private suspend fun rollbackEnable(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ) = withContext(NonCancellable) {
        store.disableIfApproved(id, fingerprint)
        try {
            scheduler.reconcile(ReconcileReason.CAPABILITY_CHANGED)
        } catch (_: Exception) {
            // La sorgente di verità è già DISABLED; il bootstrap ritenterà la pulizia OS.
        }
        try {
            connectivity.reconcile()
        } catch (_: Exception) {
            // Stesso recovery al prossimo bootstrap; non riabilitare mai la regola.
        }
        try {
            geofence.reconcile(recreateOsRegistrations = false)
        } catch (_: Exception) {
            // Stesso recovery: il receiver controlla comunque id e fingerprint approvati.
        }
        try {
            sensor.reconcile()
        } catch (_: Exception) {
            // Il sensore si ri-registra al prossimo bootstrap dallo stato armato desiderato.
        }
    }

    /** In disable tenta entrambe le pulizie anche se la prima fallisce. */
    private suspend fun runCleanupReconcile(): Boolean {
        var clean = true
        try {
            scheduler.reconcile(ReconcileReason.CAPABILITY_CHANGED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            clean = false
        }
        try {
            if (!connectivity.reconcile().cleanupSucceeded) clean = false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            clean = false
        }
        try {
            if (!geofence.reconcile(recreateOsRegistrations = false).cleanupSucceeded) clean = false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            clean = false
        }
        try {
            if (!sensor.reconcile().cleanupSucceeded) clean = false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            clean = false
        }
        return clean
    }
}
