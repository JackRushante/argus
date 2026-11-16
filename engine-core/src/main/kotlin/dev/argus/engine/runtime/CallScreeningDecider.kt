package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.FeatureGates
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.TelephonyActionPolicy
import dev.argus.engine.model.Trigger
import java.time.Clock

/**
 * Decisione SINCRONA di `screen_call` su una chiamata in arrivo, prima che il telefono squilli (0.8). Gira nel
 * servizio di filtro chiamate di Android, che deve rispondere entro pochi secondi: per questo legge solo ciò
 * che è già in memoria (regole, capacità disponibili, pause) e le condizioni ammesse dal validator, che si
 * calcolano con l'orologio ([TelephonyActionPolicy.isScreeningCondition]).
 *
 * Una regola conta se è armata, accesa, non in pausa, ancora quella approvata (impronta e requisiti), con tutte
 * le capacità richieste disponibili, con il trigger «chiamata in arrivo» che combacia col numero e le condizioni
 * soddisfatte. Con `onlyUnknownCallers` conta solo per chi non è nei contatti: un numero nascosto è sconosciuto,
 * un contatto che non si riesce a verificare (permesso mancante, errore) NON lo è (nel dubbio la chiamata
 * squilla). Fra più regole vince REJECT su SILENCE. Nessuna regola = nessuna decisione: la chiamata squilla.
 *
 * Il numero non esce mai da qui: la decisione porta solo gli id delle regole e l'operazione.
 */
class CallScreeningDecider(
    clock: Clock,
    private val matcher: TriggerMatcher = TriggerMatcher(),
) {
    private val evaluator = ConditionEvaluator(clock)

    /** Una regola che ha deciso: la sua operazione. */
    data class RuleDecision(val automationId: AutomationId, val op: ScreenCallOp)

    /** [op] è la risposta da dare alla chiamata (null = lasciala squillare); [rules] le regole che hanno deciso. */
    data class Decision(val op: ScreenCallOp?, val rules: List<RuleDecision>) {
        override fun toString(): String = "Decision(op=$op, rules=${rules.size})"
    }

    /**
     * @param number il numero di chi chiama, null o vuoto se nascosto.
     * @param isContact true se [number] è nei contatti, false se non lo è, null se non si può sapere. Chiamato
     *   al più una volta e solo se una regola lo chiede.
     */
    fun decide(
        rules: List<Automation>,
        number: String?,
        availableCapabilities: Set<String>,
        isPaused: (Automation) -> Boolean,
        isContact: (String) -> Boolean?,
    ): Decision {
        val event = TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, number?.takeIf { it.isNotBlank() })
        val contact by lazy(LazyThreadSafetyMode.NONE) { event.number?.let(isContact) }
        val decided = rules.mapNotNull { rule ->
            val screen = screenCallOf(rule) ?: return@mapNotNull null
            if (!eligible(rule, availableCapabilities) || isPaused(rule)) return@mapNotNull null
            if (!matcher.matches(rule.trigger, event)) return@mapNotNull null
            if (evaluator.result(rule.conditions, DeviceState()) != ConditionEvaluator.Result.MET) return@mapNotNull null
            if (screen.onlyUnknownCallers && event.number != null && contact != false) return@mapNotNull null
            RuleDecision(rule.id, screen.op)
        }
        val op = when {
            decided.any { it.op == ScreenCallOp.REJECT } -> ScreenCallOp.REJECT
            decided.isNotEmpty() -> ScreenCallOp.SILENCE
            else -> null
        }
        return Decision(op, decided)
    }

    /** Le regole armate e accese che filtrano le chiamate (anche quelle che ora non decidono). */
    fun screeningRules(rules: List<Automation>): List<Automation> = rules.filter { screenCallOf(it) != null }

    private fun screenCallOf(rule: Automation): Action.ScreenCall? {
        if (rule.status != AutomationStatus.ARMED || !rule.enabled) return null
        val trigger = rule.trigger as? Trigger.PhoneState ?: return null
        if (trigger.event != PhoneEvent.INCOMING_CALL || trigger.sim != null) return null
        val screen = rule.actions.filterIsInstance<Action.ScreenCall>().singleOrNull() ?: return null
        return screen.takeUnless { FeatureGates.blocks(it) }
    }

    private fun eligible(rule: Automation, availableCapabilities: Set<String>): Boolean {
        val approved = rule.approvalFingerprint ?: return false
        if (rule.confirm != null || rule.exit != null) return false
        if (rule.conditions?.let(TelephonyActionPolicy::isScreeningCondition) == false) return false
        if (rule.requiredCapabilities != CapabilityRequirements.derive(rule)) return false
        if (!availableCapabilities.containsAll(rule.requiredCapabilities)) return false
        return ApprovalFingerprints.of(rule) == approved
    }
}
