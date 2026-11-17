package dev.argus.automation

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.TimePrecision

/**
 * 0.8: [TimeAlarmStateStore] delle sveglie di FINE delle regole-intervallo, su SharedPreferences: lo schema Room
 * resta quello di prima (la tabella delle sveglie tiene una riga per regola, quella dell'inizio). Stessa semantica
 * del Room store: un record per regola, sostituito a ogni pianificazione, salvato come
 * `impronta|evento|sveglia|precisione|modo|aggiornato`. Un record illeggibile si scarta (il reconcile successivo
 * ripianifica da capo).
 */
class PrefsTimeAlarmStateStore internal constructor(private val prefs: SharedPreferences) : TimeAlarmStateStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val lock = Any()

    override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? = synchronized(lock) { read(automationId) }

    override suspend fun all(): List<ScheduledTimeAlarm> = synchronized(lock) {
        prefs.all.keys.filter { it.startsWith(PREFIX) }.sorted()
            .mapNotNull { read(AutomationId(it.removePrefix(PREFIX))) }
    }

    override suspend fun upsert(alarm: ScheduledTimeAlarm) {
        synchronized(lock) {
            val encoded = listOf(
                alarm.approvalFingerprint.value,
                alarm.eventAtMillis.toString(),
                alarm.wakeAtMillis.toString(),
                alarm.requestedPrecision.name,
                alarm.scheduledMode.name,
                alarm.updatedAtMillis.toString(),
            ).joinToString(SEPARATOR)
            check(prefs.edit().putString(PREFIX + alarm.automationId.value, encoded).commit()) {
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
            val parts = raw.split(SEPARATOR)
            require(parts.size == 6) { "record_invalid" }
            ScheduledTimeAlarm(
                automationId = id,
                approvalFingerprint = ApprovalFingerprint(parts[0]),
                eventAtMillis = parts[1].toLong(),
                wakeAtMillis = parts[2].toLong(),
                requestedPrecision = TimePrecision.valueOf(parts[3]),
                scheduledMode = ScheduledAlarmMode.valueOf(parts[4]),
                updatedAtMillis = parts[5].toLong(),
            )
        }.getOrNull()
        if (alarm == null) prefs.edit().remove(PREFIX + id.value).commit()
        return alarm
    }

    companion object {
        internal const val PREFERENCES_NAME = "argus_span_exit_alarms"
        private const val PREFIX = "alarm."
        private const val SEPARATOR = "|"
    }
}
