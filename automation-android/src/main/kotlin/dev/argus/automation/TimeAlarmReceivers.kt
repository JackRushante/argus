package dev.argus.automation

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.argus.automation.foreground.launchReceiverWork
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.SpanPhase

interface TimeAlarmRuntime {
    suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: dev.argus.engine.model.ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult
    suspend fun reconcile(reason: ReconcileReason): ReconcileReport

    /** 0.8: la sveglia di [phase]; una sveglia di fine senza coordinatore di fine si ignora. */
    suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: dev.argus.engine.model.ApprovalFingerprint,
        eventAtMillis: Long,
        phase: SpanPhase,
    ): AlarmDeliveryResult = if (phase == SpanPhase.START) {
        onAlarm(automationId, approvalFingerprint, eventAtMillis)
    } else {
        AlarmDeliveryResult.Ignored
    }
}

/**
 * 0.8: [exitCoordinator] pianifica i trigger di fine delle regole-intervallo, con stato e sveglie propri. Il
 * reconcile li tiene allineati insieme e il rapporto li somma.
 */
class CoordinatorTimeAlarmRuntime(
    private val coordinator: TimeAlarmCoordinator,
    private val exitCoordinator: TimeAlarmCoordinator? = null,
) : TimeAlarmRuntime {
    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: dev.argus.engine.model.ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = coordinator.onAlarm(automationId, approvalFingerprint, eventAtMillis)

    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: dev.argus.engine.model.ApprovalFingerprint,
        eventAtMillis: Long,
        phase: SpanPhase,
    ): AlarmDeliveryResult = when (phase) {
        SpanPhase.START -> coordinator.onAlarm(automationId, approvalFingerprint, eventAtMillis)
        SpanPhase.EXIT -> exitCoordinator?.onAlarm(automationId, approvalFingerprint, eventAtMillis)
            ?: AlarmDeliveryResult.Ignored
    }

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport {
        val start = coordinator.reconcile(reason)
        val exit = exitCoordinator?.reconcile(reason) ?: return start
        return ReconcileReport(
            scheduled = (start.scheduled + exit.scheduled).distinct(),
            cancelled = (start.cancelled + exit.cancelled).distinct(),
            recovered = (start.recovered + exit.recovered).distinct(),
            failed = (start.failed + exit.failed).distinct(),
        )
    }
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
        launchReceiverWork(context, "time_alarm", signal.automationId) {
            runtime.onAlarm(signal.automationId, signal.approvalFingerprint, signal.eventAtMillis, signal.phase)
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
        launchReceiverWork(context, "time_reconcile") { runtime.reconcile(reason) }
    }
}

internal fun reconcileReason(action: String?): ReconcileReason? = when (action) {
    Intent.ACTION_BOOT_COMPLETED -> ReconcileReason.BOOT
    Intent.ACTION_TIME_CHANGED -> ReconcileReason.TIME_CHANGED
    Intent.ACTION_TIMEZONE_CHANGED -> ReconcileReason.TIMEZONE_CHANGED
    Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileReason.PACKAGE_REPLACED
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
        ReconcileReason.EXACT_ALARM_PERMISSION_CHANGED
    // La revoca del permesso non ha broadcast: la sveglia di riserva riconcilia come se l'avesse.
    AndroidTimeAlarmBackend.ACTION_TIME_WATCHDOG -> ReconcileReason.EXACT_ALARM_PERMISSION_CHANGED
    // 0.8: la sveglia di riserva delle sveglie di fine delle regole-intervallo.
    AndroidTimeAlarmBackend.ACTION_TIME_WATCHDOG_EXIT -> ReconcileReason.EXACT_ALARM_PERMISSION_CHANGED
    // 0.8: la prossima sveglia è cambiata (impostata, spostata, suonata): ripianifica le regole next_alarm.
    AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> ReconcileReason.NEXT_ALARM_CHANGED
    else -> null
}

private const val TAG = "ArgusTimeAlarm"
