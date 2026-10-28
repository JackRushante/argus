package dev.argus.automation.screen

import java.util.Locale

/**
 * Confronto dei criteri di una macro con l'albero dello schermo (0.5.1). Puro: nessuna dipendenza da
 * Android. Il confronto è deterministico, mai approssimato:
 *
 *  - un nodo vale solo se è visibile all'utente (un elemento fuori schermo non si tocca);
 *  - un criterio vale ESATTO se il testo coincide (ignorando maiuscole e spazi ai bordi) e vale LARGO se
 *    lo contiene; l'id coincide per intero o per la parte dopo `:id/`;
 *  - se qualche nodo corrisponde in modo esatto a TUTTI i criteri, i candidati sono solo quelli;
 *    altrimenti sono i nodi che corrispondono in modo largo. Così «Invia» sceglie il pulsante «Invia»
 *    e non «Invia a tutti», ma «Inv» (se non c'è altro) trova comunque «Invia».
 *
 * L'ordine dei candidati è quello del documento (profondità, poi ordine dei figli).
 */
internal object ScreenMatcher {
    private const val MAX_NODES = 5_000
    private const val MAX_DEPTH = 64

    private enum class Level { NONE, LOOSE, EXACT }

    fun find(root: ScreenNode, query: ScreenQuery): List<ScreenNode> {
        val exact = mutableListOf<ScreenNode>()
        val loose = mutableListOf<ScreenNode>()
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            when (levelOf(node, query)) {
                Level.EXACT -> exact += node
                Level.LOOSE -> loose += node
                Level.NONE -> Unit
            }
        }
        return exact.ifEmpty { loose }
    }

    /** Il primo nodo scorrevole e visibile, in ordine di documento. */
    fun firstScrollable(root: ScreenNode): ScreenNode? {
        var found: ScreenNode? = null
        walk(root) { node ->
            if (found == null && node.isVisibleToUser && node.isScrollable) found = node
        }
        return found
    }

    /** Il nodo stesso se si può toccare, altrimenti l'antenato più vicino che si può toccare. */
    fun clickableFor(node: ScreenNode): ScreenNode? {
        var current: ScreenNode? = node
        var hops = 0
        while (current != null && hops <= MAX_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private const val MAX_ANCESTOR_HOPS = 6

    private fun levelOf(node: ScreenNode, query: ScreenQuery): Level {
        var lowest = Level.EXACT
        var anyCriterion = false

        fun apply(level: Level) {
            anyCriterion = true
            if (level < lowest) lowest = level
        }

        query.text?.let { wanted ->
            val onText = textLevel(node.text, wanted)
            val onDescription = if (query.textAlsoDescription) textLevel(node.contentDescription, wanted) else Level.NONE
            apply(maxOf(onText, onDescription))
        }
        query.desc?.let { apply(textLevel(node.contentDescription, it)) }
        query.id?.let { apply(idLevel(node.viewId, it)) }
        return if (anyCriterion) lowest else Level.NONE
    }

    private fun textLevel(actual: String?, wanted: String): Level {
        val have = actual?.trim().orEmpty()
        val want = wanted.trim()
        if (have.isEmpty() || want.isEmpty()) return Level.NONE
        return when {
            have.equals(want, ignoreCase = true) -> Level.EXACT
            have.lowercase(Locale.ROOT).contains(want.lowercase(Locale.ROOT)) -> Level.LOOSE
            else -> Level.NONE
        }
    }

    private fun idLevel(actual: String?, wanted: String): Level {
        val have = actual?.trim().orEmpty()
        val want = wanted.trim()
        if (have.isEmpty() || want.isEmpty()) return Level.NONE
        return if (have == want || have.substringAfter(":id/") == want) Level.EXACT else Level.NONE
    }

    /** Visita iterativa in pre-ordine (l'ordine del documento), con tetto di nodi e profondità. */
    private fun walk(root: ScreenNode, visit: (ScreenNode) -> Unit) {
        data class Frame(val node: ScreenNode, val depth: Int)
        val pending = ArrayDeque<Frame>()
        pending.addLast(Frame(root, 0))
        var seen = 0
        while (pending.isNotEmpty() && seen < MAX_NODES) {
            val (node, depth) = pending.removeLast()
            seen++
            visit(node)
            if (depth < MAX_DEPTH) {
                // In coda al contrario: il primo figlio esce per primo.
                node.children.asReversed().forEach { pending.addLast(Frame(it, depth + 1)) }
            }
        }
    }
}
