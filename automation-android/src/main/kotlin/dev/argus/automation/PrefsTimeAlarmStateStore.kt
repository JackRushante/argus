package dev.argus.automation

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.TimePrecision
import kotlinx.serialization.Serializable

/**
 * 0.8: [TimeAlarmStateStore] delle sveglie di FINE delle regole-intervallo, su SharedPreferences: lo schema Room
 * resta quello di prima (la tabella delle sveglie tiene una riga per regola, quella dell'inizio). Stessa semantica
 * del Room store: un record per regola, sostituito a ogni pianificazione. Un record illeggibile si scarta (il
 * reconcile successivo ripianifica da capo).
 */
class PrefsTimeAlarmStateStore internal constructor(private val prefs: SharedPreferences) : TimeAlarmStateStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val lock = Any()

    @Serializable
    private data class Stored(
        val fingerprint: String,
        val eventAtMillis: Long,
        val wakeAtMillis: Long,
        val precision: TimePrecision,
        val mode: ScheduledAlarmMode,
        val updatedAtMillis: Long,
    )

    override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? = synchronized(lock) { read(automationId) }

    override suspend fun all(): List<ScheduledTimeAlarm> = synchronized(lock) {
        prefs.all.keys.filter { it.startsWith(PREFIX) }.sorted()
            .mapNotNull { read(AutomationId(it.removePrefix(PREFIX))) }
    }

    override suspend fun upsert(alarm: ScheduledTimeAlarm) {
        synchronized(lock) {
            val stored = Stored(
                fingerprint = alarm.approvalFingerprint.value,
                eventAtMillis = alarm.eventAtMillis,
                wakeAtMillis = alarm.wakeAtMillis,
                precision = alarm.requestedPrecision,
                mode = alarm.scheduledMode,
                updatedAtMillis = alarm.updatedAtMillis,
            )
            check(prefs.edit().putString(PREFIX + alarm.automationId.value, ArgusJson.encodeToString(Stored.serializer(), stored)).commit()) {
                "stato sveglia di fine non salvato"
            }
        }
    }

    override suspend fun delete(automationId: AutomationId) {
        synchronized(lock) { prefs.edit().remove(PREFIX + automationId.value).commit() }
    }

    private fun read(id: AutomationId): ScheduledTimeAlarm? {
        val raw = prefs.getString(PREFIX + id.value, null) ?: return null
        val alarm = runCatching {
            val stored = ArgusJson.decodeFromString(Stored.serializer(), raw)
            ScheduledTimeAlarm(
                automationId = id,
                approvalFingerprint = ApprovalFingerprint(stored.fingerprint),
                eventAtMillis = stored.eventAtMillis,
                wakeAtMillis = stored.wakeAtMillis,
                requestedPrecision = stored.precision,
                scheduledMode = stored.mode,
                updatedAtMillis = stored.updatedAtMillis,
            )
        }.getOrNull()
        if (alarm == null) prefs.edit().remove(PREFIX + id.value).commit()
        return alarm
    }

    companion object {
        internal const val PREFERENCES_NAME = "argus_span_exit_alarms"
        private const val PREFIX = "alarm."
    }
}
