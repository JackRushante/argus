package dev.argus.automation.budget

import android.content.Context
import dev.argus.brain.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** null = illimitato. La UI usa 0 come "illimitato": lo store normalizza 0 -> null in scrittura. */
data class BudgetLimits(
    val maxCallsPerHour: Int? = null,
    val maxCallsPerDay: Int? = null,
    val maxCostPerMonthMicros: Long? = null,
)

data class BudgetSettings(
    val global: BudgetLimits = BudgetLimits(),
    val perProvider: Map<String, BudgetLimits> = emptyMap(), // chiave = ProviderId.wireName
    val softThresholdPct: Int = DEFAULT_SOFT_THRESHOLD_PCT,
) {
    companion object {
        const val DEFAULT_SOFT_THRESHOLD_PCT = 80
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
 */
class AndroidBudgetSettingsStore(context: Context) : BudgetSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
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
            preferences.edit().putInt(KEY_SOFT_THRESHOLD, pct).commit().also { saved ->
                if (saved) state.value = read()
            }
        }
    }

    private suspend fun writeLimits(prefix: String, limits: BudgetLimits): Boolean = writeMutex.withLock {
        val hour = limits.maxCallsPerHour
        val day = limits.maxCallsPerDay
        val cost = limits.maxCostPerMonthMicros
        if ((hour != null && hour < 0) || (day != null && day < 0) || (cost != null && cost < 0)) {
            return@withLock false
        }
        withContext(Dispatchers.IO) {
            val editor = preferences.edit()
            putOrRemoveInt(editor, "$prefix$SUFFIX_HOUR", hour)
            putOrRemoveInt(editor, "$prefix$SUFFIX_DAY", day)
            putOrRemoveLong(editor, "$prefix$SUFFIX_COST", cost)
            editor.commit().also { saved -> if (saved) state.value = read() }
        }
    }

    private fun putOrRemoveInt(editor: android.content.SharedPreferences.Editor, key: String, value: Int?) {
        // Convenzione 0 = illimitato: normalizzato a "chiave assente".
        if (value != null && value > 0) editor.putInt(key, value) else editor.remove(key)
    }

    private fun putOrRemoveLong(editor: android.content.SharedPreferences.Editor, key: String, value: Long?) {
        if (value != null && value > 0) editor.putLong(key, value) else editor.remove(key)
    }

    private fun read(): BudgetSettings {
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
    )

    private fun providerPrefix(wireName: String): String = "provider.$wireName."

    private companion object {
        const val PREFERENCES_NAME = "argus_budget_private"
        const val GLOBAL_PREFIX = "global."
        const val SUFFIX_HOUR = "maxCallsPerHour"
        const val SUFFIX_DAY = "maxCallsPerDay"
        const val SUFFIX_COST = "maxCostPerMonthMicros"
        const val KEY_SOFT_THRESHOLD = "softThresholdPct"
    }
}
