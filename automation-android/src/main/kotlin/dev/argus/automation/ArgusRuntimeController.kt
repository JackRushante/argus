package dev.argus.automation

import android.util.Log
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.data.RoomJournalMaintenance
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime

/** Bootstrap idempotente del runtime event-driven, condiviso da Application e lifecycle processo. */
class ArgusRuntimeController(
    private val scope: CoroutineScope,
    private val scheduler: TimeAlarmRuntime,
    private val capabilities: CapabilityReconciler,
    private val maintenance: RoomJournalMaintenance,
    private val shizukuStatus: Flow<ShizukuGatewayStatus>,
    private val preferences: AppPreferencesStore,
    private val replyRegistry: ActiveNotificationReplyRegistry,
    private val connectivity: ConnectivityTriggerRuntime = NoopConnectivityTriggerRuntime,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val started = AtomicBoolean(false)
    private val reconcileMutex = Mutex()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            runCatchingPreservingCancellation { maintenance.run(nowMillis()) }
            reconcile(ReconcileReason.APP_START)
        }
        scope.launch {
            shizukuStatus
                .drop(1)
                .collect { reconcile(ReconcileReason.CAPABILITY_CHANGED) }
        }
        scope.launch {
            // revokePrivacy() avviene con l'app già foreground: senza questo collector il
            // runtime resterebbe armato fino al prossimo ON_START.
            preferences.observe()
                .map { it.privacyAccepted }
                .distinctUntilChanged()
                .drop(1)
                .collect { accepted ->
                    if (!accepted) replyRegistry.clear()
                    reconcile(ReconcileReason.CAPABILITY_CHANGED)
                }
        }
    }

    fun onForeground() {
        if (!started.get()) return
        scope.launch { reconcile(ReconcileReason.CAPABILITY_CHANGED) }
    }

    internal suspend fun reconcile(reason: ReconcileReason) = reconcileMutex.withLock {
        runCatchingPreservingCancellation { capabilities.reconcile() }
            .onFailure { error ->
                Log.w(TAG, "reconcile capability fallito: ${error::class.java.simpleName}")
            }
        runCatchingPreservingCancellation { scheduler.reconcile(reason) }
            .onFailure { error ->
                Log.w(TAG, "reconcile scheduler fallito: ${error::class.java.simpleName}")
            }
        runCatchingPreservingCancellation { connectivity.reconcile() }
            .onFailure { error ->
                Log.w(TAG, "reconcile connectivity fallito: ${error::class.java.simpleName}")
            }
        Unit
    }

    private suspend fun <T> runCatchingPreservingCancellation(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private companion object {
        const val TAG = "ArgusRuntime"
    }
}
