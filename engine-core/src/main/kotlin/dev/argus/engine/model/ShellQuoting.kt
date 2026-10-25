package dev.argus.engine.model

/**
 * Quoting POSIX sh per i dati ESTERNI (TAINTED) interpolati in un comando shell (D1).
 *
 * Il template di `run_shell.cmd` è fingerprintato e resta letterale; solo il VALORE di una
 * variabile TAINTED viene reso "inerte" al momento dell'interpolazione, così un SMS/notifica con
 * `; rm -rf /`, `$(…)` o apici non può cambiare la struttura del comando approvato. L'impronta non
 * cambia perché il template non cambia: il quoting avviene solo a runtime.
 *
 * Il quoting dipende da DOVE cade il segnaposto nel template, perché lo stesso testo è pericoloso in
 * modo diverso a seconda del contesto:
 *  - [Context.WORD] (fuori da apici): il valore è avvolto in `'…'` con `'` → `'\''` ([quote]);
 *  - [Context.DOUBLE_QUOTED] (`"…${x}…"`): dentro i doppi apici `$(…)`, `` ` `` e `$VAR` restano
 *    ATTIVI, quindi avvolgere in apici singoli non basta: si fa escape di `\`, `"`, `$`, `` ` ``;
 *  - [Context.SINGLE_QUOTED] (`'…${x}…'`): si chiude, si inserisce `\'` e si riapre (`'` → `'\''`);
 *  - [Context.UNSAFE]: contesto che non sappiamo rendere sicuro (sostituzione di comando aperta,
 *    backtick, here-doc, commento, `$'…'`, backslash o `$` subito prima del segnaposto). Il valore
 *    TAINTED NON viene inserito: l'azione è bloccata (fail-closed).
 *
 * Lo scanner legge il template da sinistra a destra: il contesto di un segnaposto dipende solo dal
 * testo che lo precede.
 */
object ShellQuoting {
    /** Contesto lessicale di un segnaposto `${nome}` nel template del comando. */
    enum class Context { WORD, SINGLE_QUOTED, DOUBLE_QUOTED, UNSAFE }

    /** `'` + testo con `'` sostituito da `'\''` + `'`: sempre un solo token, mai espanso dalla shell. */
    fun quote(text: String): String = "'" + escapeForSingleQuotes(text) + "'"

    /** Per un segnaposto già dentro apici singoli: chiude, aggiunge un `'` letterale, riapre. */
    fun escapeForSingleQuotes(text: String): String = text.replace("'", "'\\''")

    /** Per un segnaposto dentro doppi apici: neutralizza i soli caratteri che restano attivi. */
    fun escapeForDoubleQuotes(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            if (c == '\\' || c == '"' || c == '$' || c == '`') append('\\')
            append(c)
        }
    }

    /** Resa del valore per il contesto; null se [Context.UNSAFE] (il chiamante deve bloccare). */
    fun render(text: String, context: Context): String? = when (context) {
        Context.WORD -> quote(text)
        Context.SINGLE_QUOTED -> escapeForSingleQuotes(text)
        Context.DOUBLE_QUOTED -> escapeForDoubleQuotes(text)
        Context.UNSAFE -> null
    }

    /**
     * Contesto di ciascun segnaposto, nell'ordine in cui compaiono. [placeholders] sono gli
     * intervalli (ordinati, non sovrapposti) di ogni `${nome}` valido nel [template].
     *
     * Fail-closed: una volta incontrata una costruzione che lo scanner non modella (`$(`, backtick,
     * `$'`, `<<`, commento), ogni segnaposto successivo è [Context.UNSAFE].
     */
    fun contexts(template: String, placeholders: List<IntRange>): List<Context> {
        val result = ArrayList<Context>(placeholders.size)
        var next = 0
        var state = Context.WORD
        var opaque = false
        var i = 0
        val n = template.length

        fun placeholderAt(index: Int): Boolean =
            next < placeholders.size && placeholders[next].first == index

        fun consumePlaceholder(context: Context) {
            result += context
            i = placeholders[next].last + 1
            next++
        }

        while (i < n) {
            if (placeholderAt(i)) {
                val glued = i > 0 && template[i - 1] == '$' && state != Context.SINGLE_QUOTED
                // Un `$` subito prima del valore lo trasformerebbe in `$'…'`, `$(…)` o `$NOME`.
                consumePlaceholder(if (opaque || glued) Context.UNSAFE else state)
                continue
            }
            val c = template[i]
            val following = template.getOrNull(i + 1)
            when (state) {
                Context.SINGLE_QUOTED -> if (c == '\'') state = Context.WORD
                Context.WORD, Context.DOUBLE_QUOTED -> {
                    val inDouble = state == Context.DOUBLE_QUOTED
                    when {
                        c == '\\' -> {
                            // Il backslash escapa il carattere successivo: se è il segnaposto, il
                            // valore verrebbe attaccato a un escape e cambierebbe la struttura.
                            if (placeholderAt(i + 1)) {
                                i++
                                consumePlaceholder(Context.UNSAFE)
                            } else {
                                i += 2
                            }
                            continue
                        }
                        c == '`' -> opaque = true
                        c == '$' && (following == '(' || following == '{') -> opaque = true
                        inDouble && c == '"' -> state = Context.WORD
                        !inDouble && c == '"' -> state = Context.DOUBLE_QUOTED
                        !inDouble && c == '\'' -> state = Context.SINGLE_QUOTED
                        !inDouble && c == '$' && following == '\'' -> opaque = true
                        !inDouble && c == '#' && startsWord(template, i) -> opaque = true
                        !inDouble && c == '<' && following == '<' -> opaque = true
                    }
                }
                Context.UNSAFE -> error("lo stato dello scanner non è mai UNSAFE")
            }
            i++
        }
        // Segnaposto non raggiunti (intervalli incoerenti col template): mai un contesto ottimista.
        while (result.size < placeholders.size) result += Context.UNSAFE
        return result
    }

    private fun startsWord(template: String, index: Int): Boolean =
        index == 0 || template[index - 1].isWhitespace() || template[index - 1] in ";&|()<>"
}
