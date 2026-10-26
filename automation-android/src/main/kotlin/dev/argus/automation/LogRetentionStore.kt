package dev.argus.automation

import android.content.Context
import android.content.SharedPreferences
import dev.argus.data.JournalRetentionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Giorni di conservazione del Log scelti dall'utente (7, 30 o 90). Store minimo su
 * SharedPreferences: nessun dato personale, solo un numero. Chi non tocca niente resta sul
 * default storico della manutenzione ([JournalRetentionPolicy.DEFAULT_RETENTION_DAYS]); un
 * valore corrotto o fuori dall'elenco ricade sul default.
 */
class LogRetentionStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val state = MutableStateFlow(read())

    /** Valore corrente, osservabile dalla schermata Log. */
    val days: StateFlow<Int> = state.asStateFlow()

    /** Provider per [dev.argus.data.RoomJournalMaintenance]: legge sempre il valore piu' recente. */
    fun currentDays(): Int = state.value

    /** false se [value] non e' una delle opzioni ammesse o la scrittura fallisce. */
    fun setDays(value: Int): Boolean {
        if (value !in OPTIONS) return false
        val saved = preferences.edit().putInt(KEY_DAYS, value).commit()
        if (saved) state.value = value
        return saved
    }

    private fun read(): Int =
        preferences.getInt(KEY_DAYS, DEFAULT_DAYS).takeIf { it in OPTIONS } ?: DEFAULT_DAYS

    companion object {
        val OPTIONS: List<Int> = listOf(7, 30, 90)
        const val DEFAULT_DAYS: Int = JournalRetentionPolicy.DEFAULT_RETENTION_DAYS
        private const val PREFERENCES_NAME = "argus_log_retention"
        private const val KEY_DAYS = "retention_days"
    }
}
