package dev.argus.automation.health

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Stato degli avvisi di salute (0.7.2): l'interruttore di Sistema (acceso di default) e i segni che
 * limitano le notifiche. Solo id di regola, capacità e giorni (epoch day locale): mai nomi o contenuti.
 */
interface HealthAlertStore {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean): Boolean

    /** La regola ha già avuto il suo avviso e non è ancora tornata a riuscire. */
    fun ruleAlerted(ruleId: String): Boolean
    /** Giorno dell'ultimo avviso della regola, o null. */
    fun ruleAlertDay(ruleId: String): Long?
    fun markRuleAlerted(ruleId: String, day: Long)
    /** La regola è tornata a riuscire: il prossimo filotto di fallimenti può avvisare di nuovo (non oggi). */
    fun clearRuleAlerted(ruleId: String)

    /** La perdita di [capability] è già stata registrata (avvisata o taciuta perché già avvisata oggi). */
    fun capabilityLost(capability: HealthCapability): Boolean
    fun capabilityAlertDay(capability: HealthCapability): Long?
    fun markCapabilityLost(capability: HealthCapability, alertedDay: Long?)
    fun markCapabilityRestored(capability: HealthCapability)
}

class AndroidHealthAlertStore(context: Context) : HealthAlertStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val enabledState = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))

    override val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit().also { saved ->
            if (saved) enabledState.value = enabled
        }
    }

    override fun ruleAlerted(ruleId: String): Boolean = preferences.getBoolean(ruleKey(ruleId, ALERTED), false)

    override fun ruleAlertDay(ruleId: String): Long? = day(ruleKey(ruleId, DAY))

    override fun markRuleAlerted(ruleId: String, day: Long) {
        preferences.edit()
            .putBoolean(ruleKey(ruleId, ALERTED), true)
            .putLong(ruleKey(ruleId, DAY), day)
            .apply()
    }

    override fun clearRuleAlerted(ruleId: String) {
        if (!ruleAlerted(ruleId)) return
        preferences.edit().remove(ruleKey(ruleId, ALERTED)).apply()
    }

    override fun capabilityLost(capability: HealthCapability): Boolean =
        preferences.getBoolean(capabilityKey(capability, LOST), false)

    override fun capabilityAlertDay(capability: HealthCapability): Long? = day(capabilityKey(capability, DAY))

    override fun markCapabilityLost(capability: HealthCapability, alertedDay: Long?) {
        val editor = preferences.edit().putBoolean(capabilityKey(capability, LOST), true)
        if (alertedDay != null) editor.putLong(capabilityKey(capability, DAY), alertedDay)
        editor.apply()
    }

    override fun markCapabilityRestored(capability: HealthCapability) {
        if (!capabilityLost(capability)) return
        preferences.edit().remove(capabilityKey(capability, LOST)).apply()
    }

    private fun day(key: String): Long? = if (preferences.contains(key)) preferences.getLong(key, 0L) else null

    private fun ruleKey(ruleId: String, suffix: String) = "rule.$ruleId.$suffix"

    private fun capabilityKey(capability: HealthCapability, suffix: String) = "capability.${capability.key}.$suffix"

    private companion object {
        const val PREFERENCES_NAME = "argus_health_alerts"
        const val KEY_ENABLED = "enabled"
        const val ALERTED = "alerted"
        const val LOST = "lost"
        const val DAY = "day"
    }
}
