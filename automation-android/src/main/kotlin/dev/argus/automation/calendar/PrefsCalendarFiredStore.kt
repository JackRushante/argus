package dev.argus.automation.calendar

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.AutomationId

/**
 * [CalendarFiredStore] su SharedPreferences (nessuna tabella Room): per regola le istanze già scattate,
 * salvate come `idEvento:inizio:FASE@istante`. Solo numeri e la fase: nessun titolo o calendario.
 */
class PrefsCalendarFiredStore(context: Context) : CalendarFiredStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun fired(automationId: AutomationId): Set<String> =
        entries(automationId).mapTo(linkedSetOf()) { it.substringBefore(FIRE_AT_SEPARATOR) }

    @Synchronized
    override fun markFired(automationId: AutomationId, key: String, fireAtMillis: Long, nowMillis: Long): Boolean {
        val updated = prune(entries(automationId) + "$key$FIRE_AT_SEPARATOR$fireAtMillis", nowMillis)
        return prefs.edit().putStringSet(firedKey(automationId), updated).commit()
    }

    @Synchronized
    override fun scheduled(): Set<AutomationId> =
        prefs.getStringSet(SCHEDULED_KEY, null).orEmpty().mapTo(linkedSetOf(), ::AutomationId)

    @Synchronized
    override fun setScheduled(ids: Set<AutomationId>) {
        prefs.edit().putStringSet(SCHEDULED_KEY, ids.mapTo(linkedSetOf()) { it.value }).commit()
    }

    @Synchronized
    override fun forget(automationId: AutomationId) {
        prefs.edit().remove(firedKey(automationId)).commit()
    }

    private fun entries(automationId: AutomationId): Set<String> =
        prefs.getStringSet(firedKey(automationId), null)?.toSet().orEmpty()

    private fun firedKey(automationId: AutomationId) = "$FIRED_PREFIX${automationId.value}"

    companion object {
        internal const val PREFS_NAME = "argus_calendar_trigger"
        private const val SCHEDULED_KEY = "scheduled"
        private const val FIRED_PREFIX = "fired."
        private const val FIRE_AT_SEPARATOR = '@'

        /**
         * Uno scatto più vecchio di così non può tornare dovuto (la tolleranza di ritardo è di 30 minuti):
         * la sua voce si toglie.
         */
        const val RETENTION_MILLIS: Long = 2L * 24 * 60 * 60 * 1_000

        /** Toglie le voci il cui istante di scatto è più vecchio di [RETENTION_MILLIS] e quelle malformate. */
        internal fun prune(entries: Set<String>, nowMillis: Long): Set<String> = entries.filterTo(linkedSetOf()) { entry ->
            val fireAt = entry.substringAfter(FIRE_AT_SEPARATOR, "").toLongOrNull() ?: return@filterTo false
            fireAt >= nowMillis - RETENTION_MILLIS
        }
    }
}
