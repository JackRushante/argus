package dev.argus.importing

/** Le parti di un indirizzo che contano per decidere se leggerlo. */
data class UriParts(val scheme: String?, val authority: String?)

/** Cosa fare con un Intent ricevuto da un'altra app. */
sealed interface ReceivedDecision {
    data class Text(val text: String) : ReceivedDecision

    /** Leggere il documento di `EXTRA_STREAM` (condivisione) o dei dati dell'Intent (apertura). */
    data class Document(val source: DocumentSource) : ReceivedDecision

    data object Unsupported : ReceivedDecision
}

enum class DocumentSource { STREAM, DATA }

/**
 * 0.7.2: decide cosa leggere da un Intent che arriva da fuori ad [ImportRuleActivity]. Funzione pura (testata):
 *  - `SEND` con testo: il testo; senza testo, il documento allegato;
 *  - `VIEW`: il documento aperto (un file manager che apre un file di backup);
 *  - solo indirizzi `content://` di ALTRE app: niente `file://` (leggerebbe con i permessi di Argus anche i suoi
 *    file privati) e niente provider di Argus stesso;
 *  - qualunque altra cosa: niente.
 */
object ReceivedIntent {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    fun decide(
        action: String?,
        text: CharSequence?,
        stream: UriParts?,
        data: UriParts?,
        ownPackage: String,
    ): ReceivedDecision = when (action) {
        ACTION_SEND -> when {
            !text.isNullOrEmpty() -> ReceivedDecision.Text(text.toString())
            stream != null && readable(stream, ownPackage) -> ReceivedDecision.Document(DocumentSource.STREAM)
            else -> ReceivedDecision.Unsupported
        }
        ACTION_VIEW ->
            if (data != null && readable(data, ownPackage)) ReceivedDecision.Document(DocumentSource.DATA)
            else ReceivedDecision.Unsupported
        else -> ReceivedDecision.Unsupported
    }

    private fun readable(uri: UriParts, ownPackage: String): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        val authority = uri.authority?.takeIf(String::isNotBlank) ?: return false
        val own = authority.substringAfterLast('@').lowercase()
        return own != ownPackage.lowercase() && !own.startsWith(ownPackage.lowercase() + ".")
    }
}
