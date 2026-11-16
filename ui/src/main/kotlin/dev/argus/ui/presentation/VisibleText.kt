package dev.argus.ui.presentation

import java.util.Locale

/**
 * S8 (0.7.2): testo eseguibile reso in approvazione SENZA caratteri nascosti.
 *
 * Un comando shell, un URL, una chiave d'impostazione, un package o un testo da digitare possono
 * contenere caratteri che a schermo non si vedono (U+200B, U+FEFF, controlli) o che riordinano il
 * testo (U+202E, U+2066…): l'utente leggerebbe una cosa e approverebbe un'altra. Questi caratteri
 * — categorie Unicode Cf, Zl, Zp e Cc tranne a capo e tabulazione, più i surrogati spaiati — si
 * mostrano come un marcatore visibile `⟨U+202E⟩` che il composable disegna in un tono a parte. Il
 * marcatore sostituisce il carattere: niente resta invisibile e niente può riordinare il testo.
 *
 * Funzione pura (JVM, nessun `android.*`): la usano i composable che rendono il testo della regola.
 */
object VisibleText {

    /** Un frammento: testo così com'è, oppure il marcatore di un carattere nascosto ([isMarker]). */
    data class Part(val text: String, val isMarker: Boolean = false)

    /** Segno che sostituisce un a capo DENTRO un dato (testo digitato, body HTTP, letterale). */
    const val NEWLINE_MARK = "⏎"

    /** Vero se il code point non si vede o cambia la direzione del testo. A capo e tab restano. */
    fun isHidden(codePoint: Int): Boolean {
        if (codePoint == '\n'.code || codePoint == '\t'.code) return false
        return when (Character.getType(codePoint).toByte()) {
            Character.FORMAT,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            Character.CONTROL,
            Character.SURROGATE,
            -> true
            else -> false
        }
    }

    /** Il marcatore di [codePoint]: `⟨U+202E⟩` (almeno 4 cifre esadecimali, maiuscole). */
    fun marker(codePoint: Int): String = String.format(Locale.ROOT, "⟨U+%04X⟩", codePoint)

    /** [text] a frammenti; i caratteri nascosti consecutivi formano frammenti marcatore separati. */
    fun parts(text: String): List<Part> {
        if (text.none { isHidden(it.code) }) return listOf(Part(text))
        val out = mutableListOf<Part>()
        val plain = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isHidden(cp)) {
                if (plain.isNotEmpty()) {
                    out += Part(plain.toString())
                    plain.setLength(0)
                }
                out += Part(marker(cp), isMarker = true)
            } else {
                plain.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        if (plain.isNotEmpty()) out += Part(plain.toString())
        return out
    }

    /** [text] con ogni carattere nascosto sostituito dal suo marcatore (stessa resa di [parts], senza tono). */
    fun reveal(text: String): String = parts(text).joinToString("") { it.text }

    /**
     * Un dato su una riga sola: ogni a capo (`\r\n`, `\n`, `\r`) diventa [NEWLINE_MARK], così un testo
     * digitato o un body non possono imitare una riga in più del dettaglio.
     */
    fun singleLine(text: String): String =
        text.replace("\r\n", NEWLINE_MARK).replace('\n', '⏎').replace('\r', '⏎')
}
