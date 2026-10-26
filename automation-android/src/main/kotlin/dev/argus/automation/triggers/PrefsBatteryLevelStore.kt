package dev.argus.automation.triggers

import android.content.Context

/**
 * Ultimo livello batteria noto. Scrittura con `apply()` (asincrona ma garantita alla morte normale
 * del processo): la scrive il thread principale a ogni cambio di livello, un commit sincrono lì
 * sarebbe un I/O bloccante a ogni percento.
 */
class PrefsBatteryLevelStore(context: Context) : BatteryLevelStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun last(): BatteryReference? = runCatching {
        val level = prefs.getInt(KEY_LEVEL, -1)
        val atMillis = prefs.getLong(KEY_AT, -1L)
        require(level in 0..100 && atMillis >= 0)
        BatteryReference(level, atMillis)
    }.getOrNull()

    override fun record(level: Int, atMillis: Long) {
        require(level in 0..100) { "battery_level_invalid" }
        require(atMillis >= 0) { "battery_time_invalid" }
        prefs.edit().putInt(KEY_LEVEL, level).putLong(KEY_AT, atMillis).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_LEVEL).remove(KEY_AT).apply()
    }

    private companion object {
        const val PREFS_NAME = "argus_battery_trigger"
        const val KEY_LEVEL = "last_level"
        const val KEY_AT = "last_at"
    }
}
