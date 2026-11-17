package dev.argus.automation.geofence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.argus.automation.foreground.launchReceiverWork
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Un DWELL in attesa (0.8): la regola [automationId] è entrata nell'area con la transizione numero
 * [enterSequence] e va ricontrollata a [dueAtMillis]. Legato all'impronta: una modifica lo annulla.
 */
data class PendingDwell(
    val automationId: AutomationId,
    val fingerprint: ApprovalFingerprint,
    val enterSequence: Long,
    val dueAtMillis: Long,
)

/** I DWELL in attesa: persistiti, perché un riavvio del processo non deve perderli. */
interface GeofenceDwellStore {
    fun all(): List<PendingDwell>
    fun get(automationId: AutomationId): PendingDwell?
    fun put(dwell: PendingDwell)
    fun remove(automationId: AutomationId)
}

/** Sveglia del ricontrollo: una per regola, ricreata dopo un riavvio dal reconcile. */
interface GeofenceDwellTimer {
    fun schedule(automationId: AutomationId, dueAtMillis: Long)
    fun cancel(automationId: AutomationId)
}

/**
 * Tutto ciò che serve all'ingress per i DWELL. [delayOf] = l'attesa della regola se è un DWELL (null per
 * ENTER/EXIT o regola sparita).
 */
class GeofenceDwellSupport(
    val delayOf: suspend (AutomationId) -> Long?,
    val store: GeofenceDwellStore,
    val timer: GeofenceDwellTimer,
    val nowMillis: () -> Long = System::currentTimeMillis,
)

class MemoryGeofenceDwellStore : GeofenceDwellStore {
    private val values = linkedMapOf<AutomationId, PendingDwell>()

    @Synchronized override fun all(): List<PendingDwell> = values.values.toList()

    @Synchronized override fun get(automationId: AutomationId): PendingDwell? = values[automationId]

    @Synchronized override fun put(dwell: PendingDwell) {
        values[dwell.automationId] = dwell
    }

    @Synchronized override fun remove(automationId: AutomationId) {
        values.remove(automationId)
    }
}

/**
 * [GeofenceDwellStore] su `SharedPreferences` con `commit()` sincrono, come lo stato delle geofence. Un
 * record illeggibile si scarta (al peggio un DWELL non scatta, mai un crash).
 */
class PrefsGeofenceDwellStore internal constructor(
    private val preferences: SharedPreferences,
) : GeofenceDwellStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun all(): List<PendingDwell> = synchronized(this) {
        preferences.all.keys.filter { it.startsWith(PREFIX) }.mapNotNull { key ->
            val id = runCatching {
                String(Base64.getUrlDecoder().decode(key.removePrefix(PREFIX)), StandardCharsets.UTF_8)
            }.getOrNull() ?: return@mapNotNull null
            parse(AutomationId(id), preferences.getString(key, null))
        }
    }

    override fun get(automationId: AutomationId): PendingDwell? = synchronized(this) {
        parse(automationId, preferences.getString(key(automationId), null))
    }

    override fun put(dwell: PendingDwell) = synchronized(this) {
        val value = "${dwell.fingerprint.value} ${dwell.enterSequence} ${dwell.dueAtMillis}"
        check(preferences.edit().putString(key(dwell.automationId), value).commit()) { "dwell_commit_failed" }
    }

    override fun remove(automationId: AutomationId) = synchronized(this) {
        check(preferences.edit().remove(key(automationId)).commit()) { "dwell_commit_failed" }
    }

    private fun parse(id: AutomationId, value: String?): PendingDwell? {
        val parts = value?.split(' ') ?: return null
        if (parts.size != 3) return null
        val sequence = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val due = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val fingerprint = parts[0].takeIf { it.isNotBlank() } ?: return null
        return PendingDwell(id, ApprovalFingerprint(fingerprint), sequence, due)
    }

    private fun key(id: AutomationId): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(id.value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val PREFERENCES_NAME = "argus_geofence_dwell_v1"
        const val PREFIX = "dwell."
    }
}

/**
 * Sveglia del ricontrollo DWELL: esatta se il permesso c'è, altrimenti inexact (allow-while-idle). Intent
 * esplicito verso un receiver NON esportato; l'id della regola è nell'URI così le sveglie non si
 * sovrascrivono fra regole.
 */
class AndroidGeofenceDwellTimer(context: Context) : GeofenceDwellTimer {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun schedule(automationId: AutomationId, dueAtMillis: Long) {
        val manager = alarmManager ?: return
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent(appContext, automationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMillis, operation)
                return
            }
        } catch (_: SecurityException) {
            // Revoca concorrente: si ripiega sull'inexact.
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMillis, operation)
    }

    override fun cancel(automationId: AutomationId) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent(appContext, automationId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager?.cancel(operation)
        operation.cancel()
    }

    companion object {
        internal const val ACTION_DWELL_CHECK = "dev.argus.automation.action.GEOFENCE_DWELL"
        private const val REQUEST_CODE = 0

        internal fun intent(context: Context, automationId: AutomationId): Intent =
            Intent(context, GeofenceDwellReceiver::class.java).apply {
                action = ACTION_DWELL_CHECK
                data = Uri.Builder()
                    .scheme("argus")
                    .authority("geofence-dwell")
                    .appendPath(automationId.value)
                    .build()
            }

        internal fun parse(intent: Intent?): AutomationId? = runCatching {
            require(intent?.action == ACTION_DWELL_CHECK)
            require(intent.data?.scheme == "argus" && intent.data?.host == "geofence-dwell")
            val id = requireNotNull(intent.data?.lastPathSegment).trim()
            require(id.isNotEmpty() && id.length <= 256)
            AutomationId(id)
        }.getOrNull()
    }
}

/** Receiver non esportato: lo attiva solo la sveglia del ricontrollo DWELL. */
class GeofenceDwellReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val automationId = AndroidGeofenceDwellTimer.parse(intent) ?: return
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GeofenceIngressEntryPoint::class.java,
        )
        launchReceiverWork(context, "geofence_dwell", automationId) {
            try {
                entry.geofenceEventIngress().onDwellDue(automationId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "ricontrollo dwell fallito: ${error::class.java.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "ArgusGeofence"
    }
}
