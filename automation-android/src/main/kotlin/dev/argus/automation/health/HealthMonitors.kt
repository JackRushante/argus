package dev.argus.automation.health

import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ExecutionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Le notifiche degli avvisi di salute. Solo nomi di regola e conteggi, mai contenuti di messaggi. */
interface HealthNotifier {
    fun ruleFailing(ruleId: String, ruleName: String)
    fun clearRuleFailing(ruleId: String)
    fun capabilityLost(capability: HealthCapability, affectedRules: Int)
    fun clearCapabilityLost(capability: HealthCapability)
}

/** Esito di una regola dopo l'ultima esecuzione conclusa. */
enum class RuleHealthDecision { ALERT, RECOVERED, NONE }

object RuleHealthPolicy {
    const val FAILURES_IN_A_ROW = 3

    private val FAILURES = setOf(ExecutionStatus.FAILED, ExecutionStatus.PARTIAL, ExecutionStatus.INTERRUPTED)

    /**
     * [recentOutcomes] = esiti delle ultime esecuzioni concluse, dalla più recente (soppressioni e
     * scatti saltati non ci sono). Avvisa quando le ultime tre sono fallite, una volta finché la regola
     * non torna a riuscire e mai due volte nello stesso giorno ([today] e [lastAlertDay] in epoch day).
     */
    fun decide(
        recentOutcomes: List<ExecutionStatus>,
        alreadyAlerted: Boolean,
        lastAlertDay: Long?,
        today: Long,
    ): RuleHealthDecision {
        if (recentOutcomes.firstOrNull() == ExecutionStatus.SUCCEEDED) {
            return if (alreadyAlerted) RuleHealthDecision.RECOVERED else RuleHealthDecision.NONE
        }
        val streak = recentOutcomes.size >= FAILURES_IN_A_ROW &&
            recentOutcomes.take(FAILURES_IN_A_ROW).all { it in FAILURES }
        return if (streak && !alreadyAlerted && lastAlertDay != today) RuleHealthDecision.ALERT else RuleHealthDecision.NONE
    }
}

/** «La regola X è fallita 3 volte di fila»: si valuta a ogni esecuzione conclusa, dal journal. */
class RuleFailureMonitor(
    private val recentOutcomes: suspend (AutomationId, Int) -> List<ExecutionStatus>,
    private val ruleName: suspend (AutomationId) -> String?,
    private val store: HealthAlertStore,
    private val notifier: HealthNotifier,
    private val today: () -> Long,
) {
    private val mutex = Mutex()

    suspend fun onExecutionConcluded(id: AutomationId) {
        mutex.withLock { evaluate(id) }
    }

    private suspend fun evaluate(id: AutomationId) {
        val outcomes = recentOutcomes(id, RuleHealthPolicy.FAILURES_IN_A_ROW)
        val day = today()
        when (RuleHealthPolicy.decide(outcomes, store.ruleAlerted(id.value), store.ruleAlertDay(id.value), day)) {
            RuleHealthDecision.RECOVERED -> {
                store.clearRuleAlerted(id.value)
                notifier.clearRuleFailing(id.value)
            }
            RuleHealthDecision.ALERT -> {
                if (!store.enabled.value) return
                val name = ruleName(id) ?: return
                store.markRuleAlerted(id.value, day)
                notifier.ruleFailing(id.value, name)
            }
            RuleHealthDecision.NONE -> Unit
        }
    }
}

/**
 * «Una regola armata ha perso qualcosa che le serve»: si valuta nel reconcile del runtime (avvio, ritorno
 * in primo piano, cambio di Shizuku/root, consenso). Un avviso per capacità quando la perdita è nuova, al
 * massimo uno al giorno; quando torna disponibile l'avviso sparisce e una perdita successiva conta di nuovo.
 *
 * Una perdita deve durare almeno [graceMillis]: dopo un riavvio Shizuku o Sui possono arrivare qualche
 * secondo dopo Argus. Alla prima osservazione si chiede UN ricontrollo con [scheduleRecheck].
 */
class CapabilityHealthMonitor(
    private val armedRules: suspend () -> List<Automation>,
    private val snapshot: suspend (Set<String>) -> CapabilityHealthSnapshot,
    private val store: HealthAlertStore,
    private val notifier: HealthNotifier,
    private val nowMillis: () -> Long,
    private val today: () -> Long,
    private val scheduleRecheck: (delayMillis: Long) -> Unit,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {
    private val mutex = Mutex()
    private val firstSeenLost = HashMap<HealthCapability, Long>()
    private var pendingRecheckAt: Long? = null

    suspend fun check() {
        mutex.withLock { evaluate() }
    }

    private suspend fun evaluate() {
        val rules = armedRules().filter { it.enabled }
        val counts = CapabilityHealth.affectedCounts(
            rules,
            snapshot(rules.flatMapTo(linkedSetOf()) { it.requiredCapabilities }),
        )
        val now = nowMillis()
        var nextRecheck: Long? = null
        HealthCapability.entries.forEach { capability ->
            val affected = counts[capability] ?: 0
            if (affected == 0) {
                firstSeenLost.remove(capability)
                if (store.capabilityLost(capability)) {
                    store.markCapabilityRestored(capability)
                    notifier.clearCapabilityLost(capability)
                }
                return@forEach
            }
            if (store.capabilityLost(capability) || !store.enabled.value) return@forEach
            val since = firstSeenLost.getOrPut(capability) { now }
            val waited = now - since
            if (waited in 0 until graceMillis) {
                val remaining = graceMillis - waited
                nextRecheck = nextRecheck?.coerceAtMost(remaining) ?: remaining
                return@forEach
            }
            firstSeenLost.remove(capability)
            val day = today()
            if (store.capabilityAlertDay(capability) == day) {
                // Già avvisata oggi (ha fatto su e giù): si registra la perdita senza una seconda notifica.
                store.markCapabilityLost(capability, alertedDay = null)
            } else {
                store.markCapabilityLost(capability, alertedDay = day)
                notifier.capabilityLost(capability, affected)
            }
        }
        nextRecheck?.let { delay ->
            // Un solo ricontrollo in attesa: i reconcile che arrivano nel frattempo non ne aggiungono.
            val pending = pendingRecheckAt
            if (pending == null || pending < now) {
                pendingRecheckAt = now + delay
                scheduleRecheck(delay)
            }
        }
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 3L * 60 * 1_000
    }
}
