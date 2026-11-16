package dev.argus.automation.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.foreground.launchReceiverWork
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Sveglia `RTC_WAKEUP` per regola: esatta se il permesso c'è e serve, altrimenti inesatta. */
class AndroidCalendarAlarmScheduler(context: Context) : CalendarAlarmScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = requireNotNull(appContext.getSystemService(AlarmManager::class.java)) {
        "AlarmManager non disponibile"
    }

    override fun schedule(automationId: AutomationId, fingerprint: ApprovalFingerprint, atMillis: Long, exact: Boolean) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            identityIntent(appContext, automationId).apply {
                putExtra(EXTRA_AUTOMATION_ID, automationId.value)
                putExtra(EXTRA_APPROVAL_FINGERPRINT, fingerprint.value)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact && canExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                return
            } catch (_: SecurityException) {
                // Revoca concorrente: ripiega sulla sveglia inesatta.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
    }

    override fun cancel(automationId: AutomationId) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            identityIntent(appContext, automationId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    companion object {
        internal const val ACTION_CALENDAR_ALARM = "dev.argus.automation.action.CALENDAR_ALARM"
        private const val EXTRA_AUTOMATION_ID = "automation_id"
        private const val EXTRA_APPROVAL_FINGERPRINT = "approval_fingerprint"
        private const val REQUEST_CODE = 0

        /** Una sveglia per regola: l'URI distingue i PendingIntent (gli extra non contano). */
        internal fun identityIntent(context: Context, automationId: AutomationId): Intent =
            Intent(context, CalendarAlarmReceiver::class.java).apply {
                action = ACTION_CALENDAR_ALARM
                data = Uri.Builder().scheme("argus").authority("calendar").appendPath(automationId.value).build()
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }

        internal fun parse(intent: Intent?): Pair<AutomationId, ApprovalFingerprint>? = runCatching {
            require(intent?.action == ACTION_CALENDAR_ALARM)
            val id = requireNotNull(intent.getStringExtra(EXTRA_AUTOMATION_ID)).trim()
            require(id.isNotEmpty() && id.length <= 256)
            require(intent.data?.scheme == "argus" && intent.data?.host == "calendar")
            require(intent.data?.lastPathSegment == id)
            AutomationId(id) to ApprovalFingerprint(requireNotNull(intent.getStringExtra(EXTRA_APPROVAL_FINGERPRINT)))
        }.getOrNull()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CalendarTriggerEntryPoint {
    fun calendarTriggerCoordinator(): CalendarTriggerCoordinator
}

private fun coordinator(context: Context): CalendarTriggerCoordinator = EntryPointAccessors
    .fromApplication(context.applicationContext, CalendarTriggerEntryPoint::class.java)
    .calendarTriggerCoordinator()

/** La sveglia di una regola calendario (intent esplicito, receiver non esportato). */
class CalendarAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (automationId, fingerprint) = AndroidCalendarAlarmScheduler.parse(intent) ?: return
        val runtime = coordinator(context)
        launchReceiverWork(context, "calendar_alarm", automationId) {
            runtime.onAlarm(automationId, fingerprint)
        }
    }
}

/** Riavvio, cambio di ora o di fuso, aggiornamento dell'app: si ripianificano le istanze. */
class CalendarReplanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REPLAN_ACTIONS) return
        val runtime = coordinator(context)
        launchReceiverWork(context, "calendar_replan") { runtime.reconcile() }
    }

    private companion object {
        val REPLAN_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

/**
 * Ciclo di vita del trigger calendario nel processo: reconcile all'avvio e quando l'insieme delle regole
 * calendario armate cambia (arm, disarm, modifica, cancellazione), e un `ContentObserver` sul calendario
 * finché il processo vive e almeno una regola calendario è armata (con il permesso concesso).
 */
class CalendarTriggerLifecycle(
    context: Context,
    private val coordinator: CalendarTriggerCoordinator,
    private val store: AutomationStore,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private val observerLock = Any()
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            store.observeAll()
                .map { rules ->
                    rules.filter { it.enabled && it.trigger is Trigger.CalendarEvent }
                        .map { Triple(it.id, it.status, it.approvalFingerprint) }
                        .toSet()
                }
                .distinctUntilChanged()
                .collect { calendarRules ->
                    reconcileQuietly()
                    syncObserver(calendarRules.isNotEmpty())
                }
        }
    }

    /** Al ritorno in primo piano: il permesso può essere cambiato. */
    fun onForeground() {
        if (!started.get()) return
        scope.launch {
            reconcileQuietly()
            syncObserver(observerWanted())
        }
    }

    private suspend fun observerWanted(): Boolean = runCatching {
        store.armed().any { it.trigger is Trigger.CalendarEvent }
    }.getOrDefault(false)

    private fun syncObserver(wanted: Boolean): Unit = synchronized(observerLock) {
        val allowed = wanted && ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        val current = observer
        if (allowed && current == null) {
            val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onCalendarChanged()
            }
            try {
                appContext.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, created)
                observer = created
            } catch (error: RuntimeException) {
                Log.w(TAG, "osservatore calendario non registrato: ${error::class.java.simpleName}")
            }
        } else if (!allowed && current != null) {
            runCatching { appContext.contentResolver.unregisterContentObserver(current) }
            observer = null
        }
    }

    /** Un cambio arriva a raffiche (sincronizzazione): si ripianifica una volta, a raffica finita. */
    private fun onCalendarChanged(): Unit = synchronized(observerLock) {
        pending?.cancel()
        pending = scope.launch {
            delay(CHANGE_DEBOUNCE_MILLIS)
            reconcileQuietly()
        }
    }

    private suspend fun reconcileQuietly() {
        try {
            coordinator.reconcile()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "reconcile calendario fallito: ${error::class.java.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ArgusCalendar"
        const val CHANGE_DEBOUNCE_MILLIS = 2_000L
    }
}
