package dev.argus.ui.presentation

import dev.argus.engine.model.RuleLabels
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.LabelChipUi
import dev.argus.ui.model.StatusBadge
import java.text.Normalizer
import java.util.Locale

/**
 * Ricerca nella lista Automazioni (0.7.2): tutta in memoria, sulle righe già caricate. Cerca nel nome della
 * regola, nei riassunti di trigger e azioni mostrati nella riga e nei nomi delle app che la regola nomina.
 * Senza maiuscole né accenti («perche» trova «Perché»); con più parole, la riga deve contenerle tutte.
 */
object RuleSearch {
    private val MARKS = Regex("\\p{Mn}+")
    // Unicode White_Space written out: the `(?U)` flag does not exist on Android (ICU) and crashes at class init.
    private val SPACES = Regex("[\\s\\p{Z}\\u0085]+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(SPACES, " ")
            .trim()

    fun matches(row: AutomationRow, query: String): Boolean {
        val words = normalize(query).split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return true
        val corpus = normalize(
            buildString {
                append(row.name).append('\n')
                append(row.triggerSummary).append('\n')
                row.actionSummary?.let { append(it).append('\n') }
                row.appNames.forEach { append(it).append('\n') }
            },
        )
        return words.all(corpus::contains)
    }

    fun filter(rows: List<AutomationRow>, query: String): List<AutomationRow> =
        if (query.isBlank()) rows else rows.filter { matches(it, query) }
}

/**
 * Chip delle etichette (0.7.2) dall'elenco COMPLETO delle righe, come i conteggi del riepilogo: una per
 * etichetta, nell'ordine in cui compaiono, con quante regole la portano, quante sono attive e quante
 * aspettano l'approvazione. Un'etichetta senza regole non c'è.
 */
fun labelChipsOf(rows: List<AutomationRow>, pausedKeys: Set<String>): List<LabelChipUi> {
    val byKey = LinkedHashMap<String, MutableList<AutomationRow>>()
    val spelling = HashMap<String, String>()
    rows.forEach { row ->
        row.labels.forEach { label ->
            val key = RuleLabels.key(label)
            spelling.putIfAbsent(key, label)
            byKey.getOrPut(key) { mutableListOf() } += row
        }
    }
    return byKey.map { (key, labelled) ->
        LabelChipUi(
            label = spelling.getValue(key),
            count = labelled.size,
            activeCount = labelled.count { it.status == StatusBadge.ARMED && it.enabled },
            waitingCount = labelled.count { it.status == StatusBadge.PENDING_APPROVAL || it.pendingEditDraftId != null },
            paused = key in pausedKeys,
        )
    }
}

/** La riga porta l'etichetta [label] (senza maiuscole). */
fun AutomationRow.hasLabel(label: String): Boolean {
    val key = RuleLabels.key(label)
    return labels.any { RuleLabels.key(it) == key }
}

/** Perché un'etichetta scritta nel dettaglio non si può aggiungere. Vocabolario chiuso: il testo lo scrive la UI. */
enum class LabelInputProblem { INVALID, ALREADY_ON_RULE, RULE_FULL, TOO_MANY_LABELS }

/**
 * Controllo dell'etichetta scritta, prima di mandarla (la stessa regola la ricontrolla il database): `null` se
 * va bene o se il campo è ancora vuoto. [onRule] le etichette della regola, [existing] tutte quelle in uso.
 */
fun labelInputProblem(text: String, onRule: List<String>, existing: List<String>): LabelInputProblem? {
    if (text.isBlank()) return null
    val label = RuleLabels.clean(text) ?: return LabelInputProblem.INVALID
    val key = RuleLabels.key(label)
    return when {
        onRule.any { RuleLabels.key(it) == key } -> LabelInputProblem.ALREADY_ON_RULE
        onRule.size >= RuleLabels.MAX_PER_RULE -> LabelInputProblem.RULE_FULL
        existing.none { RuleLabels.key(it) == key } &&
            existing.distinctBy(RuleLabels::key).size >= RuleLabels.MAX_DISTINCT -> LabelInputProblem.TOO_MANY_LABELS
        else -> null
    }
}

/** Le etichette in uso da proporre per prime: quelle che la regola non ha già. */
fun labelSuggestions(onRule: List<String>, existing: List<String>): List<String> {
    val taken = onRule.mapTo(HashSet(), RuleLabels::key)
    return existing.distinctBy(RuleLabels::key).filterNot { RuleLabels.key(it) in taken }
}
