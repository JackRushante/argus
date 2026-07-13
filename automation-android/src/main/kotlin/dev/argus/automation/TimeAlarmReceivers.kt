package dev.argus.automation

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.argus.engine.model.AutomationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface TimeAlarmRuntime {
    suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: dev.argus.engine.model.ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult
    suspend fun reconcile(reason: ReconcileReason): ReconcileReport
}

class CoordinatorTimeAlarmRuntime(
    private val coordinator: TimeAlarmCoordinator,
) : TimeAlarmRuntime {
    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: dev.argus.engine.model.ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = coordinator.onAlarm(automationId, approvalFingerprint, eventAtMillis)

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport =
        coordinator.reconcile(reason)
}

/** Installato da Application.onCreate prima che Android consegni qualsiasi receiver. */
object TimeAlarmRuntimeRegistry {
    @Volatile
    private var installed: TimeAlarmRuntime? = null

    fun install(runtime: TimeAlarmRuntime) {
        installed = runtime
    }

    internal fun current(): TimeAlarmRuntime? = installed
}

class TimeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val signal = AndroidTimeAlarmBackend.parseSignal(intent) ?: return
        val runtime = TimeAlarmRuntimeRegistry.current() ?: run {
            Log.w(TAG, "runtime scheduler non inizializzato; alarm recuperabile da Room")
            return
        }
        launchAsync {
            runtime.onAlarm(signal.automationId, signal.approvalFingerprint, signal.eventAtMillis)
        }
    }
}

class TimeReconciliationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = reconcileReason(intent.action) ?: return
        val runtime = TimeAlarmRuntimeRegistry.current() ?: run {
            Log.w(TAG, "runtime scheduler non inizializzato; reconcile rinviata")
            return
        }
        launchAsync { runtime.reconcile(reason) }
    }
}

internal fun reconcileReason(action: String?): ReconcileReason? = when (action) {
    Intent.ACTION_BOOT_COMPLETED -> ReconcileReason.BOOT
    Intent.ACTION_TIME_CHANGED -> ReconcileReason.TIME_CHANGED
    Intent.ACTION_TIMEZONE_CHANGED -> ReconcileReason.TIMEZONE_CHANGED
    Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileReason.PACKAGE_REPLACED
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
        ReconcileReason.EXACT_ALARM_PERMISSION_CHANGED
    else -> null
}

private fun BroadcastReceiver.launchAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block()
        } catch (_: CancellationException) {
            Log.w(TAG, "receiver scheduler cancellato; recovery rinviata")
        } catch (error: Exception) {
            Log.e(TAG, "receiver scheduler fallito: ${error::class.java.simpleName}")
        } finally {
            pending.finish()
        }
    }
}

private const val TAG = "ArgusTimeAlarm"
