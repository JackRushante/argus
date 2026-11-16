package dev.argus.automation.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Store in memoria degli avvisi di salute, per i test. */
internal class FakeHealthAlertStore(enabled: Boolean = true) : HealthAlertStore {
    private val enabledState = MutableStateFlow(enabled)
    private val ruleAlerted = mutableSetOf<String>()
    private val ruleDays = mutableMapOf<String, Long>()
    private val capabilityLost = mutableSetOf<HealthCapability>()
    private val capabilityDays = mutableMapOf<HealthCapability, Long>()

    override val enabled: StateFlow<Boolean> = enabledState

    override suspend fun setEnabled(enabled: Boolean): Boolean {
        enabledState.value = enabled
        return true
    }

    override fun ruleAlerted(ruleId: String) = ruleId in ruleAlerted
    override fun ruleAlertDay(ruleId: String) = ruleDays[ruleId]
    override fun markRuleAlerted(ruleId: String, day: Long) {
        ruleAlerted += ruleId
        ruleDays[ruleId] = day
    }
    override fun clearRuleAlerted(ruleId: String) {
        ruleAlerted -= ruleId
    }

    override fun capabilityLost(capability: HealthCapability) = capability in capabilityLost
    override fun capabilityAlertDay(capability: HealthCapability) = capabilityDays[capability]
    override fun markCapabilityLost(capability: HealthCapability, alertedDay: Long?) {
        capabilityLost += capability
        alertedDay?.let { capabilityDays[capability] = it }
    }
    override fun markCapabilityRestored(capability: HealthCapability) {
        capabilityLost -= capability
    }
}

/** Registra le notifiche invece di mostrarle. */
internal class RecordingHealthNotifier : HealthNotifier {
    val failing = mutableListOf<Pair<String, String>>()
    val clearedRules = mutableListOf<String>()
    val lost = mutableListOf<Pair<HealthCapability, Int>>()
    val clearedCapabilities = mutableListOf<HealthCapability>()

    override fun ruleFailing(ruleId: String, ruleName: String) {
        failing += ruleId to ruleName
    }
    override fun clearRuleFailing(ruleId: String) {
        clearedRules += ruleId
    }
    override fun capabilityLost(capability: HealthCapability, affectedRules: Int) {
        lost += capability to affectedRules
    }
    override fun clearCapabilityLost(capability: HealthCapability) {
        clearedCapabilities += capability
    }
}
