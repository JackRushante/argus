package dev.argus.automation.manual

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.runtime.AutomationPause
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Interruttore globale «Pausa automazioni» (0.4.1, F21). È la sorgente unica dello stato: il motore lo
 * legge come [AutomationPause] a ogni scatto; il riquadro delle impostazioni rapide, l'interruttore in
 * Impostazioni e il banner della lista lo comandano e lo osservano da [paused].
 *
 * In pausa non scatta nessuna regola, comprese le manuali; le sveglie restano registrate. Alla
 * ripresa NON si recupera ciò che è stato saltato.
 */
interface AutomationPauseStore : AutomationPause {
    /** Stato corrente, osservabile. */
    val paused: StateFlow<Boolean>

    /** Accende o spegne la pausa. Idempotente. */
    fun setPaused(paused: Boolean)

    override fun isPaused(): Boolean = paused.value
}

/**
 * Pausa su SharedPreferences: sopravvive al riavvio del processo e del telefono (una pausa non si
 * toglie da sola). La lettura è in memoria, quindi sta nel percorso di ogni evento senza I/O; la
 * scrittura usa `apply()` (la chiama il thread principale da un tocco: niente I/O bloccante).
 */
class PrefsAutomationPauseStore internal constructor(
    private val preferences: SharedPreferences,
) : AutomationPauseStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val state = MutableStateFlow(readStored())

    override val paused: StateFlow<Boolean> = state.asStateFlow()

    override fun setPaused(paused: Boolean) {
        if (state.value == paused) return
        preferences.edit().putBoolean(KEY_PAUSED, paused).apply()
        state.value = paused
    }

    private fun readStored(): Boolean = runCatching { preferences.getBoolean(KEY_PAUSED, false) }
        .getOrDefault(false)

    companion object {
        private const val PREFERENCES_NAME = "argus_pause"
        private const val KEY_PAUSED = "paused"
    }
}
