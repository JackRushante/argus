package dev.argus.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Estrae dal body d'errore di un provider il solo messaggio utile all'utente, per il test di
 * connessione (`health()`) e per i rifiuti 4xx di compile e act: è l'unico punto in cui un testo
 * remoto entra in un'eccezione, e solo nel campo dedicato [TransportException.providerMessage].
 * Il risultato è ripulito: niente caratteri di
 * controllo, chiave API configurata e token `sk-…` / `Bearer …` mascherati, al massimo
 * [MAX_CHARS] caratteri. Non va mai nei log né persistito.
 */
internal object ProviderErrorMessage {
    const val MAX_CHARS = 160
    private const val MASK = "***"
    private const val ELLIPSIS = "…"

    private val SK_TOKEN = Regex("""\bsk-[A-Za-z0-9_\-]+""")
    private val GOOGLE_KEY = Regex("""\bAIza[0-9A-Za-z_\-]{16,}""")
    private val BEARER = Regex("""(?i)\bBearer\s+\S+""")
    private val WHITESPACE = Regex("""\s+""")

    /** Messaggio ripulito del JSON d'errore [body], o null se non c'è un messaggio utilizzabile. */
    fun extract(body: String?, secrets: List<String?>): String? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        val raw = messageOf(root) ?: return null
        return sanitize(raw, secrets)
    }

    /**
     * `error.message`, oppure `message`; per i server che rispondono `{"error":"testo"}` (Ollama) anche
     * la stringa. Gemini (shim OpenAI) risponde con un array `[{"error":{…}}]`: vale il primo elemento.
     */
    private fun messageOf(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstOrNull()?.let(::messageOf)
        is JsonObject -> {
            val fromError = when (val error = element["error"]) {
                is JsonObject -> text(error["message"])
                is JsonPrimitive -> text(error)
                else -> null
            }
            fromError ?: text(element["message"])
        }
        else -> null
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun sanitize(raw: String, secrets: List<String?>): String? {
        // Prima si normalizza (controlli e a-capo diventano spazi), poi si maschera sul testo intero e
        // solo alla fine si taglia: una chiave a cavallo del limite non può lasciare un prefisso in chiaro.
        var text = WHITESPACE.replace(
            buildString(raw.length) { raw.forEach { append(if (it.isISOControl()) ' ' else it) } },
            " ",
        ).trim()
        secrets.asSequence()
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sortedByDescending(String::length)
            .forEach { secret -> text = text.replace(secret, MASK) }
        text = BEARER.replace(text, "Bearer $MASK")
        text = SK_TOKEN.replace(text, MASK)
        text = GOOGLE_KEY.replace(text, MASK)
        text = text.trim()
        if (text.isEmpty()) return null
        return cap(text)
    }

    private fun cap(text: String): String {
        if (text.length <= MAX_CHARS) return text
        var end = MAX_CHARS - ELLIPSIS.length
        // Non spezzare una coppia surrogata.
        if (Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end).trimEnd() + ELLIPSIS
    }
}
