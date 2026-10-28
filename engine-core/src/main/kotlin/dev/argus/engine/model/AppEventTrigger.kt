package dev.argus.engine.model

/**
 * Trigger «app aperta/chiusa» (0.5.1, F15). OPENED = l'app passa in primo piano; CLOSED = lascia il
 * primo piano (passa a un'altra app o alla Home). Una sola volta per transizione.
 */
enum class AppEventKind { OPENED, CLOSED }

/**
 * Forma ammessa del package di un'app Android per il trigger `app_event`: stessa regola dei package
 * delle altre parti del validatore (segmenti separati da punti, il primo comincia con una lettera),
 * con un tetto di lunghezza perché il valore finisce nell'impronta e nel render per intero.
 */
object AppPackagePolicy {
    const val MAX_LENGTH = 256

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun valid(value: String): Boolean = value.length <= MAX_LENGTH && PACKAGE_NAME.matches(value)
}
