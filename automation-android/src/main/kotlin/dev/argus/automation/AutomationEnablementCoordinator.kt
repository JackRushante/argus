package dev.argus.automation

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.runtime.AutomationStore
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

sealed interface EnablementResult {
    data object Updated : EnablementResult
    data object ReviewRequired : EnablementResult
    data object SchedulingFailed : EnablementResult
    data object DisableCleanupDeferred : EnablementResult
}

/** Mantiene stato Room e registrazione AlarmManager coerenti anche se il reconcile fallisce. */
@Singleton
class AutomationEnablementCoordinator @Inject constructor(
    private val store: AutomationStore,
    private val scheduler: TimeAlarmRuntime,
    private val connectivity: ConnectivityTriggerRuntime = NoopConnectivityTriggerRuntime,
    private val geofence: GeofenceTriggerRuntime = NoopGeofenceTriggerRuntime,
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
            return@withLock EnablementResult.ReviewRequired
        }
        return@withLock try {
            val report = scheduler.reconcile(ReconcileReason.CAPABILITY_CHANGED)
            val connectivityReport = connectivity.reconcile()
            val geofenceReport = geofence.reconcile(recreateOsRegistrations = false)
            if (id in report.failed || id in connectivityReport.failed ||
                id in geofenceReport.failed
            ) {
                rollbackEnable(id, fingerprint)
                EnablementResult.SchedulingFailed
            } else {
                EnablementResult.Updated
            }
        } catch (error: CancellationException) {
            rollbackEnable(id, fingerprint)
            throw error
        } catch (_: Exception) {
            rollbackEnable(id, fingerprint)
            EnablementResult.SchedulingFailed
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
        return clean
    }
}
