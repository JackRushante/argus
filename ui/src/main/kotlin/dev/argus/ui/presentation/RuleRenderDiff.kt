package dev.argus.ui.presentation

import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.VarRow

// =============================================================================
// «Cosa cambia» (0.7.2): confronto tra la regola approvata e la bozza che la sostituirebbe, fatto
// sulle RIGHE RESE dai tipi (`RuleRender`, invariante 4), mai sulla prosa del modello. Funzione pura:
// stesse due rese, stesso risultato.
// =============================================================================

/** Parte della regola a cui appartiene una riga. L'ordine è quello di lettura della regola. */
enum class RuleSection { TRIGGER, COOLDOWN, CONDITIONS, VARIABLES, ACTIONS }

enum class RuleChangeKind { ADDED, REMOVED, CHANGED }

/**
 * Una riga resa della regola. [shellCommand] è il comando integrale di un'azione shell, che la UI mostra
 * in monospace senza troncarlo (invariante 6); [depth] è la profondità nell'albero del programma.
 */
data class RenderLine(val text: String, val shellCommand: String? = null, val depth: Int = 0)

/** Una differenza: [old] è la riga della regola approvata, [new] quella della bozza (null se manca). */
data class RuleChangeRow(
    val section: RuleSection,
    val kind: RuleChangeKind,
    val old: RenderLine?,
    val new: RenderLine?,
)

object RuleRenderDiff {
    /**
     * Righe aggiunte, tolte o cambiate passando da [approved] a [draft], sezione per sezione, nell'ordine
     * della regola. Lista vuota = nulla di eseguibile cambia (al più il nome o la descrizione).
     *
     * Dentro una sezione le righe si allineano con la più lunga sottosequenza comune; in un tratto senza
     * righe comuni le tolte e le aggiunte si accoppiano in ordine come «cambiate», le restanti restano
     * tolte o aggiunte.
     */
    fun diff(approved: RuleRender, draft: RuleRender): List<RuleChangeRow> {
        val before = lines(approved)
        val after = lines(draft)
        return RuleSection.entries.flatMap { section ->
            diffSection(section, before[section].orEmpty(), after[section].orEmpty())
        }
    }

    /** La regola come righe per sezione: le stesse che la linea del tempo dell'approvazione mostra. */
    internal fun lines(rule: RuleRender): Map<RuleSection, List<RenderLine>> = mapOf(
        RuleSection.TRIGGER to listOf(RenderLine(rule.triggerLine)),
        RuleSection.COOLDOWN to listOfNotNull(rule.cooldownLine?.let(::RenderLine)),
        RuleSection.CONDITIONS to rule.conditionLines.map(::RenderLine),
        RuleSection.VARIABLES to rule.vars.map { RenderLine(varText(it)) },
        RuleSection.ACTIONS to programLines(rule.program.ifEmpty { rule.actions.map { ProgramNode.Leaf(it) } }, 0),
    )

    private fun varText(row: VarRow): String =
        listOf(
            row.name,
            row.typeLabel,
            row.integrityLabel,
            row.confidentialityLabel,
            row.provenanceLabel,
            row.sourceText,
            row.valueText.orEmpty(),
        ).filter(String::isNotBlank).joinToString(" · ")

    private fun programLines(nodes: List<ProgramNode>, depth: Int): List<RenderLine> = nodes.flatMap { node ->
        when (node) {
            is ProgramNode.Leaf -> listOf(actionLine(node.row, depth))
            is ProgramNode.IfNode -> buildList {
                add(RenderLine(header(node.title, node.conditionLines), depth = depth))
                addAll(programLines(node.then, depth + 1))
                node.elseTitle?.let { elseTitle ->
                    add(RenderLine(elseTitle, depth = depth))
                    addAll(programLines(node.orElse, depth + 1))
                }
            }
            is ProgramNode.WhileNode -> buildList {
                add(RenderLine(header(node.title, node.conditionLines), depth = depth))
                addAll(programLines(node.body, depth + 1))
            }
        }
    }

    private fun header(title: String, conditionLines: List<String>): String =
        (listOf(title) + conditionLines.map(String::trim)).filter(String::isNotBlank).joinToString(" ")

    private fun actionLine(row: ActionRow, depth: Int): RenderLine {
        val text = listOfNotNull(row.label, row.detail?.takeIf(String::isNotBlank)).joinToString(" · ")
        return RenderLine(text, shellCommand = row.shellCommand?.takeIf { row.isShell }, depth = depth)
    }

    private fun diffSection(section: RuleSection, old: List<RenderLine>, new: List<RenderLine>): List<RuleChangeRow> {
        // lcs[i][j] = lunghezza della più lunga sottosequenza comune di old[i..] e new[j..].
        val lcs = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val rows = mutableListOf<RuleChangeRow>()
        val removed = mutableListOf<RenderLine>()
        val added = mutableListOf<RenderLine>()
        fun flush() {
            val paired = minOf(removed.size, added.size)
            for (k in 0 until paired) rows += RuleChangeRow(section, RuleChangeKind.CHANGED, removed[k], added[k])
            removed.drop(paired).forEach { rows += RuleChangeRow(section, RuleChangeKind.REMOVED, it, null) }
            added.drop(paired).forEach { rows += RuleChangeRow(section, RuleChangeKind.ADDED, null, it) }
            removed.clear()
            added.clear()
        }
        var i = 0
        var j = 0
        while (i < old.size || j < new.size) {
            when {
                i < old.size && j < new.size && old[i] == new[j] -> {
                    flush()
                    i++
                    j++
                }
                j >= new.size || (i < old.size && lcs[i + 1][j] >= lcs[i][j + 1]) -> removed += old[i++]
                else -> added += new[j++]
            }
        }
        flush()
        return rows
    }
}
