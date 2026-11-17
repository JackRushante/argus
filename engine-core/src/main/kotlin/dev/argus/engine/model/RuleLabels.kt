package dev.argus.engine.model

import java.util.Locale

/**
 * Etichette delle regole (0.7.2): ORGANIZZAZIONE, non comportamento. Non fanno parte di [Automation] né della
 * sua forma serializzata, quindi non entrano nell'impronta di approvazione: aggiungerne o toglierne una non
 * chiede mai una nuova approvazione. Qui stanno solo le regole di forma, condivise da database, backup e UI.
 *
 * - da 1 a [MAX_CHARS] caratteri (code point: un'emoji conta uno) dopo il trim, spazi interni compattati;
 * - niente caratteri di controllo, a capo o invisibili (direzione del testo, spazi a larghezza zero), che
 *   farebbero sembrare uguali due etichette diverse;
 * - confronto senza maiuscole ([key]); si conserva la prima grafia;
 * - al più [MAX_PER_RULE] per regola e [MAX_DISTINCT] distinte in tutto.
 */
object RuleLabels {
    const val MAX_PER_RULE = 5
    const val MAX_CHARS = 24
    const val MAX_DISTINCT = 20

    // Unicode White_Space written out: the Unicode-classes regex flag does not exist on Android (ICU) and crashes at class init.
    private val WHITESPACE_RUN = Regex("[\\s\\p{Z}\\u0085]+")
    private const val ZERO_WIDTH_JOINER = 0x200D
    private val FORBIDDEN_TYPES = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
        Character.PRIVATE_USE.toInt(),
    )

    /** L'etichetta pulita, o `null` se non è valida. */
    fun clean(raw: String): String? {
        val collapsed = raw.replace(WHITESPACE_RUN, " ").trim()
        if (collapsed.isEmpty()) return null
        // Le emoji composte usano lo ZWJ: è l'unico carattere di formato ammesso.
        val forbidden = collapsed.codePoints().anyMatch { cp ->
            cp != ZERO_WIDTH_JOINER && Character.getType(cp) in FORBIDDEN_TYPES
        }
        if (forbidden) return null
        val length = collapsed.codePointCount(0, collapsed.length)
        return collapsed.takeIf { length in 1..MAX_CHARS }
    }

    /** Chiave di confronto: due etichette sono la stessa se hanno la stessa chiave. */
    fun key(label: String): String = label.replace(WHITESPACE_RUN, " ").trim().lowercase(Locale.ROOT)

    /**
     * Le etichette valide di una regola, nell'ordine dato: scarta le non valide e i doppioni (si tiene la prima
     * grafia) e si ferma a [MAX_PER_RULE].
     */
    fun sanitize(raw: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>(MAX_PER_RULE)
        for (candidate in raw) {
            if (result.size == MAX_PER_RULE) break
            val label = clean(candidate) ?: continue
            if (seen.add(key(label))) result += label
        }
        return result
    }

    /**
     * Ammette [requested] (già pulite) dato l'insieme [inUse] delle chiavi già usate: un'etichetta già in uso
     * passa sempre, una nuova finché le distinte restano al più [MAX_DISTINCT]. Doppioni scartati.
     */
    fun admit(requested: List<String>, inUse: Set<String>): List<String> {
        val keys = HashSet(inUse)
        val seen = HashSet<String>()
        return requested.filter { label ->
            val key = key(label)
            when {
                !seen.add(key) -> false
                key in keys -> true
                keys.size < MAX_DISTINCT -> keys.add(key)
                else -> false
            }
        }
    }
}
