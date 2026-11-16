package dev.argus.automation.labels

import dev.argus.data.LabelAddResult
import dev.argus.data.RoomRuleLabelStore
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.RuleLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Etichette e gruppi in pausa, come li leggono la lista e il dettaglio (0.7.2).
 *
 * [byRule]: etichette per id di regola (regole salvate e bozze importate), nell'ordine di aggiunta.
 * [pausedKeys]: chiavi dei gruppi in pausa.
 */
data class LabelsSnapshot(
    val byRule: Map<String, List<String>> = emptyMap(),
    val pausedKeys: Set<String> = emptySet(),
) {
    fun isPaused(label: String): Boolean = RuleLabels.key(label) in pausedKeys

    /** La regola è sospesa da un suo gruppo: basta UNA etichetta in pausa. */
    fun isRulePaused(ruleId: String): Boolean = byRule[ruleId].orEmpty().any(::isPaused)

    /** Tutte le etichette in uso, una per chiave, nell'ordine in cui compaiono. */
    val allLabels: List<String>
        get() = byRule.values.flatten().distinctBy(RuleLabels::key)
}

/** Il confine usato dai ViewModel: leggere, aggiungere, togliere e mettere in pausa un gruppo. */
interface RuleLabelDirectory {
    val snapshot: Flow<LabelsSnapshot>

    suspend fun add(automationId: AutomationId, label: String): LabelAddResult

    suspend fun remove(automationId: AutomationId, label: String): Boolean

    fun setGroupPaused(label: String, paused: Boolean)
}

/** Nessuna etichetta: il default dei ViewModel nei test che non le provano. */
object NoRuleLabelDirectory : RuleLabelDirectory {
    override val snapshot: Flow<LabelsSnapshot> = flowOf(LabelsSnapshot())
    override suspend fun add(automationId: AutomationId, label: String): LabelAddResult = LabelAddResult.MissingRule
    override suspend fun remove(automationId: AutomationId, label: String): Boolean = false
    override fun setGroupPaused(label: String, paused: Boolean) = Unit
}

class RoomRuleLabelDirectory(
    private val store: RoomRuleLabelStore,
    private val pauses: LabelPauseStore,
) : RuleLabelDirectory {
    override val snapshot: Flow<LabelsSnapshot> =
        combine(store.observe(), pauses.pausedKeys) { byRule, paused ->
            val inUse = byRule.values.flatten().mapTo(HashSet(), RuleLabels::key)
            LabelsSnapshot(byRule, paused intersect inUse)
        }

    override suspend fun add(automationId: AutomationId, label: String): LabelAddResult =
        store.add(automationId, label).also { result ->
            // Un'etichetta nuova (o tornata dopo aver perso tutte le regole) parte sempre non in pausa.
            if (result is LabelAddResult.Added && result.isNewLabel) pauses.setPaused(result.label, false)
        }

    override suspend fun remove(automationId: AutomationId, label: String): Boolean = store.remove(automationId, label)

    override fun setGroupPaused(label: String, paused: Boolean) = pauses.setPaused(label, paused)

    /**
     * Per tutta la vita del processo: quando un'etichetta perde l'ultima regola (eliminata, o etichetta tolta)
     * la sua pausa si dimentica, così non sospende in silenzio la prossima regola che la riceve (per esempio da
     * un import). Un errore di lettura non dimentica nulla.
     */
    fun startPruning(scope: CoroutineScope) {
        scope.launch {
            store.observe()
                .catch { error -> if (error is CancellationException) throw error }
                .collect { byRule -> pauses.retainOnly(byRule.values.flatten().mapTo(HashSet(), RuleLabels::key)) }
        }
    }
}
