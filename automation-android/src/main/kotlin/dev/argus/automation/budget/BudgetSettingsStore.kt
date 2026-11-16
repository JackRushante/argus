package dev.argus.automation.budget

import android.content.Context
import android.content.SharedPreferences
import dev.argus.brain.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * null = illimitato. La UI usa 0 come "illimitato": lo store normalizza 0 -> null in scrittura.
 * [maxCostPerMonthMicros] vale per i provider costTracked (dollari a listino noto);
 * [maxTokensPerMonth] (tokensIn+tokensOut) per i provider TOKEN-ONLY (Hermes/OpenRouter/Custom).
 */
data class BudgetLimits(
    val maxCallsPerHour: Int? = null,
    val maxCallsPerDay: Int? = null,
    val maxCostPerMonthMicros: Long? = null,
    val maxTokensPerMonth: Long? = null,
)

/**
 * [usingDefaults] = l'utente non ha mai salvato limiti: vale [DEFAULT_GLOBAL_LIMITS] (al massimo
 * [DEFAULT_MAX_CALLS_PER_DAY] chiamate AI al giorno), così una regola generativa in loop o iniettata
 * non resta senza tetto. Chi ha salvato almeno una volta conserva esattamente quello che ha salvato.
 */
data class BudgetSettings(
    val global: BudgetLimits = BudgetLimits(),
    val perProvider: Map<String, BudgetLimits> = emptyMap(), // chiave = ProviderId.wireName
    val softThresholdPct: Int = DEFAULT_SOFT_THRESHOLD_PCT,
    val usingDefaults: Boolean = false,
) {
    companion object {
        const val DEFAULT_SOFT_THRESHOLD_PCT = 80
        const val DEFAULT_MAX_CALLS_PER_DAY = 200
        val DEFAULT_GLOBAL_LIMITS = BudgetLimits(maxCallsPerDay = DEFAULT_MAX_CALLS_PER_DAY)
    }
}

interface BudgetSettingsStore {
    fun observe(): StateFlow<BudgetSettings>
    suspend fun setGlobalLimits(limits: BudgetLimits): Boolean       // false su valori negativi
    suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean
    suspend fun setSoftThresholdPct(pct: Int): Boolean               // valido solo 1..100
}

/**
 * Persistenza SharedPreferences, stesso pattern di [dev.argus.automation.AndroidAppPreferencesStore]:
 * MutableStateFlow + Mutex + commit() su Dispatchers.IO. Nessun segreto: solo tetti numerici.
 *
 * Default (0.7.2): finché l'utente non salva nulla vale [BudgetSettings.DEFAULT_GLOBAL_LIMITS]. Ogni
 * scrittura registra [KEY_SAVED]; per chi ha salvato con una versione precedente fa fede qualunque
 * chiave presente oppure, se ha salvato solo «illimitato» (nessuna chiave), il file stesso, che
 * `commit()` crea anche quando tutte le chiavi sono rimosse.
 */
class AndroidBudgetSettingsStore(context: Context) : BudgetSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val savedBefore: Boolean = preferences.all.isNotEmpty() ||
        File(File(context.applicationContext.dataDir, "shared_prefs"), "$PREFERENCES_NAME.xml").exists()
    private val state = MutableStateFlow(read())
    private val writeMutex = Mutex()

    override fun observe(): StateFlow<BudgetSettings> = state.asStateFlow()

    override suspend fun setGlobalLimits(limits: BudgetLimits): Boolean =
        writeLimits(GLOBAL_PREFIX, limits)

    override suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean =
        writeLimits(providerPrefix(id.wireName), limits)

    override suspend fun setSoftThresholdPct(pct: Int): Boolean = writeMutex.withLock {
        if (pct !in 1..100) return@withLock false
        withContext(Dispatchers.IO) {
            savingEditor(touchesGlobal = false).putInt(KEY_SOFT_THRESHOLD, pct).commit().also { saved ->
                if (saved) state.value = read()
            }
        }
    }

    private suspend fun writeLimits(prefix: String, limits: BudgetLimits): Boolean = writeMutex.withLock {
        val hour = limits.maxCallsPerHour
        val day = limits.maxCallsPerDay
        val cost = limits.maxCostPerMonthMicros
        val tokens = limits.maxTokensPerMonth
        if ((hour != null && hour < 0) || (day != null && day < 0) ||
            (cost != null && cost < 0) || (tokens != null && tokens < 0)
        ) {
            return@withLock false
        }
        withContext(Dispatchers.IO) {
            val editor = savingEditor(touchesGlobal = prefix == GLOBAL_PREFIX)
            putLimits(editor, prefix, limits)
            editor.commit().also { saved -> if (saved) state.value = read() }
        }
    }

    /**
     * Editor che marca le impostazioni come salvate. Alla prima scrittura che non riguarda i limiti
     * globali, il tetto di default diventa una scelta salvata: cambiare la soglia o un provider non
     * deve togliere il limite giornaliero.
     */
    private fun savingEditor(touchesGlobal: Boolean): SharedPreferences.Editor {
        val editor = preferences.edit().putBoolean(KEY_SAVED, true)
        if (!touchesGlobal && !hasSaved()) putLimits(editor, GLOBAL_PREFIX, BudgetSettings.DEFAULT_GLOBAL_LIMITS)
        return editor
    }

    private fun putLimits(editor: SharedPreferences.Editor, prefix: String, limits: BudgetLimits) {
        putOrRemoveInt(editor, "$prefix$SUFFIX_HOUR", limits.maxCallsPerHour)
        putOrRemoveInt(editor, "$prefix$SUFFIX_DAY", limits.maxCallsPerDay)
        putOrRemoveLong(editor, "$prefix$SUFFIX_COST", limits.maxCostPerMonthMicros)
        putOrRemoveLong(editor, "$prefix$SUFFIX_TOKENS", limits.maxTokensPerMonth)
    }

    private fun hasSaved(): Boolean = savedBefore || preferences.all.isNotEmpty()

    private fun putOrRemoveInt(editor: SharedPreferences.Editor, key: String, value: Int?) {
        // Convenzione 0 = illimitato: normalizzato a "chiave assente".
        if (value != null && value > 0) editor.putInt(key, value) else editor.remove(key)
    }

    private fun putOrRemoveLong(editor: SharedPreferences.Editor, key: String, value: Long?) {
        if (value != null && value > 0) editor.putLong(key, value) else editor.remove(key)
    }

    private fun read(): BudgetSettings {
        if (!hasSaved()) return BudgetSettings(global = BudgetSettings.DEFAULT_GLOBAL_LIMITS, usingDefaults = true)
        val perProvider = buildMap {
            ProviderId.entries.forEach { id ->
                val limits = readLimits(providerPrefix(id.wireName))
                if (limits != BudgetLimits()) put(id.wireName, limits)
            }
        }
        val soft = preferences.getInt(KEY_SOFT_THRESHOLD, BudgetSettings.DEFAULT_SOFT_THRESHOLD_PCT)
            .takeIf { it in 1..100 } ?: BudgetSettings.DEFAULT_SOFT_THRESHOLD_PCT
        return BudgetSettings(
            global = readLimits(GLOBAL_PREFIX),
            perProvider = perProvider,
            softThresholdPct = soft,
        )
    }

    private fun readLimits(prefix: String): BudgetLimits = BudgetLimits(
        maxCallsPerHour = preferences.getInt("$prefix$SUFFIX_HOUR", 0).takeIf { it > 0 },
        maxCallsPerDay = preferences.getInt("$prefix$SUFFIX_DAY", 0).takeIf { it > 0 },
        maxCostPerMonthMicros = preferences.getLong("$prefix$SUFFIX_COST", 0L).takeIf { it > 0 },
        maxTokensPerMonth = preferences.getLong("$prefix$SUFFIX_TOKENS", 0L).takeIf { it > 0 },
    )

    private fun providerPrefix(wireName: String): String = "provider.$wireName."

    private companion object {
        const val PREFERENCES_NAME = "argus_budget_private"
        const val GLOBAL_PREFIX = "global."
        const val SUFFIX_HOUR = "maxCallsPerHour"
        const val SUFFIX_DAY = "maxCallsPerDay"
        const val SUFFIX_COST = "maxCostPerMonthMicros"
        const val SUFFIX_TOKENS = "maxTokensPerMonth"
        const val KEY_SOFT_THRESHOLD = "softThresholdPct"
        const val KEY_SAVED = "saved"
    }
}
