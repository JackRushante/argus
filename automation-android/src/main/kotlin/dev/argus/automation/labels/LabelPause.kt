package dev.argus.automation.labels

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.RuleLabels
import dev.argus.engine.runtime.AutomationPause
import dev.argus.engine.runtime.PauseCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gruppi in pausa (0.7.2): le chiavi ([RuleLabels.key]) delle etichette sospese. Come la pausa globale, è uno
 * stato dell'utente che non si toglie da solo: sopravvive al riavvio del processo e del telefono.
 */
interface LabelPauseStore {
    val pausedKeys: StateFlow<Set<String>>

    /** Sospende o riprende il gruppo dell'etichetta [label] (con qualunque maiuscola). Idempotente. */
    fun setPaused(label: String, paused: Boolean)

    /**
     * Dimentica le pause delle etichette non più in uso: un'etichetta che perde l'ultima regola sparisce, e se
     * più tardi la si riusa riparte non in pausa.
     */
    fun retainOnly(keysInUse: Set<String>)
}

/** Su SharedPreferences, come [dev.argus.automation.manual.PrefsAutomationPauseStore]: lettura in memoria. */
class PrefsLabelPauseStore internal constructor(
    private val preferences: SharedPreferences,
) : LabelPauseStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val state = MutableStateFlow(readStored())

    override val pausedKeys: StateFlow<Set<String>> = state.asStateFlow()

    override fun setPaused(label: String, paused: Boolean) {
        val key = RuleLabels.key(label)
        if (key.isEmpty()) return
        update { current -> if (paused) current + key else current - key }
    }

    override fun retainOnly(keysInUse: Set<String>) {
        update { current -> current intersect keysInUse }
    }

    @Synchronized
    private fun update(change: (Set<String>) -> Set<String>) {
        val next = change(state.value)
        if (next == state.value) return
        preferences.edit().putStringSet(KEY_PAUSED, HashSet(next)).apply()
        state.value = next
    }

    private fun readStored(): Set<String> = runCatching {
        preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty().toSet()
    }.getOrDefault(emptySet())

    companion object {
        private const val PREFERENCES_NAME = "argus_label_pause"
        private const val KEY_PAUSED = "paused_label_keys"
    }
}

/**
 * La pausa letta dal motore (0.7.2): l'interruttore globale (F21) più i gruppi in pausa, decisi regola per
 * regola. Una regola con più etichette è sospesa se ALMENO una è in pausa. Senza gruppi in pausa non legge
 * nulla oltre all'interruttore: il percorso di ogni evento resta senza I/O.
 *
 * Se le etichette della regola non si leggono mentre un gruppo è in pausa, la regola resta ferma: una pausa
 * chiesta dall'utente non deve saltare per un guasto del database.
 */
class LabelAwarePause(
    private val global: AutomationPause,
    private val groups: LabelPauseStore,
    private val labelKeysOf: suspend (AutomationId) -> Set<String>,
) : AutomationPause {
    override fun isPaused(): Boolean = global.isPaused()

    override suspend fun pauseCodeFor(automationId: AutomationId): String? {
        if (global.isPaused()) return PauseCodes.PAUSED
        val paused = groups.pausedKeys.value
        if (paused.isEmpty()) return null
        val keys = try {
            labelKeysOf(automationId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return PauseCodes.GROUP_PAUSED
        }
        return if (keys.any { it in paused }) PauseCodes.GROUP_PAUSED else null
    }
}
