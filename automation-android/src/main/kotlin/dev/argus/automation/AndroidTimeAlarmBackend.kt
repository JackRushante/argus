package dev.argus.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.runtime.SpanPhase

data class TimeAlarmSignal(
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    val eventAtMillis: Long,
    /** 0.8: la sveglia d'inizio o quella di fine di una regola-intervallo. */
    val phase: SpanPhase = SpanPhase.START,
)

/**
 * 0.8: la prossima sveglia del telefono (`AlarmManager.getNextAlarmClock`), per i trigger `next_alarm`.
 * Argus non usa mai `setAlarmClock`, quindi le sue sveglie interne non compaiono qui. Nessun permesso.
 */
class AndroidNextAlarmClock(context: Context) {
    private val alarmManager = context.applicationContext.getSystemService(AlarmManager::class.java)

    fun nextAlarmAt(): Long? = try {
        alarmManager?.nextAlarmClock?.triggerTime?.takeIf { it > 0 }
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * AlarmManager RTC_WAKEUP: le specifiche Argus sono orari civili, non elapsed realtime.
 *
 * 0.8: [phase] separa le sveglie di fine delle regole-intervallo da quelle d'inizio (URI e sveglia di riserva
 * propri), così ogni regola può averne una per fase senza che una sostituisca l'altra.
 */
class AndroidTimeAlarmBackend(
    context: Context,
    private val phase: SpanPhase = SpanPhase.START,
) : TimeAlarmBackend {
    private val appContext = context.applicationContext
    private val alarmManager = requireNotNull(
        appContext.getSystemService(AlarmManager::class.java),
    ) { "AlarmManager non disponibile" }

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode {
        val operation = operation(registration)
        if (registration.requestedPrecision == TimePrecision.EXACT && canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    registration.wakeAtMillis,
                    operation,
                )
                return ScheduledAlarmMode.EXACT
            } catch (_: SecurityException) {
                // Revoca concorrente: degrada senza perdere l'automazione.
            }
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            registration.wakeAtMillis,
            operation,
        )
        return ScheduledAlarmMode.INEXACT
    }

    override fun cancel(automationId: AutomationId) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            identityIntent(appContext, automationId, phase),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    override fun syncWatchdog(atMillis: Long?) {
        if (atMillis == null) {
            PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE,
                watchdogIntent(appContext, phase),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { operation ->
                alarmManager.cancel(operation)
                operation.cancel()
            }
            return
        }
        // Sempre inexact: deve sopravvivere proprio alla revoca del permesso delle exact.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            atMillis,
            PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE,
                watchdogIntent(appContext, phase),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun operation(registration: TimeAlarmRegistration): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            identityIntent(appContext, registration.automationId, phase).apply {
                putExtra(EXTRA_AUTOMATION_ID, registration.automationId.value)
                putExtra(EXTRA_APPROVAL_FINGERPRINT, registration.approvalFingerprint.value)
                putExtra(EXTRA_EVENT_AT_MILLIS, registration.eventAtMillis)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        internal const val ACTION_TIME_ALARM = "dev.argus.automation.action.TIME_ALARM"
        internal const val EXTRA_AUTOMATION_ID = "automation_id"
        internal const val EXTRA_APPROVAL_FINGERPRINT = "approval_fingerprint"
        internal const val EXTRA_EVENT_AT_MILLIS = "event_at_millis"
        private const val REQUEST_CODE = 0

        /** URI delle sveglie d'inizio (com'erano prima della 0.8) e di fine delle regole-intervallo. */
        private const val AUTHORITY_START = "time"
        private const val AUTHORITY_EXIT = "time-exit"

        /** Sveglia di riserva interna: intent esplicito verso un receiver non esportato. */
        internal const val ACTION_TIME_WATCHDOG = "dev.argus.automation.action.TIME_WATCHDOG"

        /** 0.8: la sveglia di riserva delle sveglie di fine (una per coordinatore: non si sostituiscono). */
        internal const val ACTION_TIME_WATCHDOG_EXIT = "dev.argus.automation.action.TIME_WATCHDOG_EXIT"

        internal fun watchdogIntent(context: Context, phase: SpanPhase = SpanPhase.START): Intent =
            Intent(context, TimeReconciliationReceiver::class.java).apply {
                action = if (phase == SpanPhase.START) ACTION_TIME_WATCHDOG else ACTION_TIME_WATCHDOG_EXIT
            }

        internal fun identityIntent(
            context: Context,
            automationId: AutomationId,
            phase: SpanPhase = SpanPhase.START,
        ): Intent =
            Intent(context, TimeAlarmReceiver::class.java).apply {
                action = ACTION_TIME_ALARM
                data = Uri.Builder()
                    .scheme("argus")
                    .authority(if (phase == SpanPhase.START) AUTHORITY_START else AUTHORITY_EXIT)
                    .appendPath(automationId.value)
                    .build()
                // Campo #63: Android 14+ accoda i broadcast verso processi cached/frozen — un
                // alarm EXACT arrivava comunque con decine di secondi di ritardo. Il flag forza
                // la consegna immediata. (I flag non entrano in filterEquals: i PendingIntent già
                // registrati restano matchabili e vengono aggiornati da FLAG_UPDATE_CURRENT.)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }

        fun parseSignal(intent: Intent?): TimeAlarmSignal? = runCatching {
            require(intent?.action == ACTION_TIME_ALARM) { "action_invalid" }
            val id = requireNotNull(intent.getStringExtra(EXTRA_AUTOMATION_ID)).trim()
            require(id.isNotEmpty() && id.length <= 256) { "automation_id_invalid" }
            require(intent.data?.scheme == "argus") { "uri_invalid" }
            val phase = when (intent.data?.host) {
                AUTHORITY_START -> SpanPhase.START
                AUTHORITY_EXIT -> SpanPhase.EXIT
                else -> throw IllegalArgumentException("uri_invalid")
            }
            require(intent.data?.lastPathSegment == id) { "uri_id_mismatch" }
            val fingerprint = ApprovalFingerprint(
                requireNotNull(intent.getStringExtra(EXTRA_APPROVAL_FINGERPRINT)),
            )
            val eventAt = intent.getLongExtra(EXTRA_EVENT_AT_MILLIS, -1L)
            require(eventAt >= 0) { "event_at_invalid" }
            TimeAlarmSignal(AutomationId(id), fingerprint, eventAt, phase)
        }.getOrNull()
    }
}
