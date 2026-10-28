package dev.argus.ui.presentation

/**
 * Disposizione delle tessere della salute in Sistema (tavola «Sistema», 0.7.0): una griglia a due colonne in cui i
 * problemi da correggere e i testi lunghi occupano tutta la larghezza e il resto va in coppia. Pura e senza Compose,
 * cosi' le regole si provano a host.
 */
object SystemHealthLayout {
    /** Oltre questa lunghezza un sottotitolo non sta in mezza larghezza senza diventare una colonna di parole. */
    const val WIDE_SUBTITLE_CHARS = 56

    /** Vero se la tessera deve occupare tutta la larghezza: un problema da correggere, o un testo lungo. */
    fun isWide(needsFix: Boolean, subtitle: String): Boolean = needsFix || subtitle.length > WIDE_SUBTITLE_CHARS

    /**
     * Righe della griglia nell'ordine di [items]: ogni elemento largo sta da solo; gli altri vanno a due a due.
     * Un elemento stretto rimasto solo (prima di uno largo o in fondo) sta da solo e si disegna a tutta larghezza.
     */
    fun <T> rows(items: List<T>, wide: (T) -> Boolean): List<List<T>> {
        val result = mutableListOf<List<T>>()
        val buffer = mutableListOf<T>()
        fun flush() {
            if (buffer.isNotEmpty()) {
                result += buffer.toList()
                buffer.clear()
            }
        }
        for (item in items) {
            if (wide(item)) {
                flush()
                result += listOf(item)
            } else {
                buffer += item
                if (buffer.size == 2) flush()
            }
        }
        flush()
        return result
    }
}
