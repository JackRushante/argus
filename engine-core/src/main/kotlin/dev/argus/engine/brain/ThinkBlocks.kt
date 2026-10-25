package dev.argus.engine.brain

/**
 * Rimozione del ragionamento in chiaro che alcuni modelli (DeepSeek, Qwen, gpt-oss tramite server
 * locali) lasciano nel testo di risposta come `<think>…</think>`. Un sentinel o un testo di risposta
 * citato nel ragionamento NON deve mai essere preso per quello vero.
 */
object ThinkBlocks {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"
    private const val OPEN_PREFIX = "<think"

    /**
     * Toglie i blocchi completi. Una chiusura orfana (`</think>` senza apertura: il template del
     * modello ha aperto il ragionamento fuori dall'output) scarta tutto ciò che la precede. Un blocco
     * mai chiuso (output troncato) viene scartato fino alla fine se [dropUnclosed], altrimenti resta
     * nel testo così il chiamante può rifiutare la risposta.
     */
    fun strip(raw: String, dropUnclosed: Boolean = true): String {
        var text = raw
        while (true) {
            val open = text.indexOf(OPEN, ignoreCase = true)
            if (open < 0) break
            val close = text.indexOf(CLOSE, startIndex = open + OPEN.length, ignoreCase = true)
            if (close < 0) {
                if (dropUnclosed) text = text.substring(0, open)
                break
            }
            text = text.removeRange(open, close + CLOSE.length)
        }
        val orphan = text.lastIndexOf(CLOSE, ignoreCase = true)
        if (orphan >= 0) text = text.substring(orphan + CLOSE.length)
        return text
    }

    /** true se nel testo (già passato da [strip]) resta un'apertura di ragionamento. */
    fun hasOpenBlock(stripped: String): Boolean = stripped.contains(OPEN_PREFIX, ignoreCase = true)
}
