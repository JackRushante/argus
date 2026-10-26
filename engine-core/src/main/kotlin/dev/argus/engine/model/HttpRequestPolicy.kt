package dev.argus.engine.model

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Policy pura dell'azione [Action.HttpRequest] (0.4.1): limiti, forma di URL e intestazioni, codifica
 * dei dati esterni nel body e mascheratura dei segreti nella review. Nessuna dipendenza Android né di
 * rete: la usano il validatore (a monte), l'interpolatore (dopo la risoluzione) e l'esecutore
 * (ultima difesa), così le tre barriere non possono divergere.
 *
 * Regole di sicurezza, in sintesi:
 *  - URL e intestazioni sono LETTERALI e fingerprintati: nessun `${…}` (solo il body interpola);
 *  - `https://` obbligatorio; `http://` solo verso un host loopback esatto; mai userinfo;
 *  - i dati esterni (anche TAINTED) entrano solo nel body, in JSON come contenuto di una stringa
 *    con escape, in `application/x-www-form-urlencoded` percent-encoded;
 *  - body ≤ 16 KB, ≤ 10 intestazioni con nomi validi, vietate `Host`, `Content-Length`,
 *    `Transfer-Encoding`, `Connection`.
 */
object HttpRequestPolicy {
    const val MAX_URL_CHARS = 2_048
    const val MAX_HEADERS = 10
    const val MAX_HEADER_NAME_CHARS = 64
    const val MAX_HEADER_VALUE_CHARS = 2_048
    const val MAX_CONTENT_TYPE_CHARS = 128
    const val MAX_BODY_BYTES = 16 * 1024

    /** Tempo massimo dell'intera richiesta, redirect inclusi. */
    const val TIMEOUT_MS = 15_000L

    /** Quanto della risposta si legge (e si butta): serve a chiudere lo scambio, mai a tenerne il testo. */
    const val MAX_RESPONSE_BYTES = 64 * 1024

    /** Salti di redirect ammessi, tutti verso lo stesso host. */
    const val MAX_REDIRECTS = 5

    const val CONTENT_TYPE_HEADER = "content-type"

    /** Content type usato quando c'è un body e la regola non ne dichiara uno. */
    const val DEFAULT_CONTENT_TYPE = "text/plain; charset=utf-8"

    /** Intestazioni che decide il client HTTP: l'utente non può impostarle. */
    val FORBIDDEN_HEADER_NAMES: Set<String> =
        setOf("host", "content-length", "transfer-encoding", "connection")

    /** Host per cui il cleartext è ammesso (lo stesso insieme dei provider e della network security config). */
    val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "::1")

    /** Frammenti del nome (minuscolo) che marcano un'intestazione come segreta nella review. */
    private val SENSITIVE_NAME_PARTS = listOf("authorization", "token", "key", "secret")

    /** Cifre mostrate in coda a un segreto mascherato, solo se il segreto è abbastanza lungo. */
    private const val MASK_TAIL_CHARS = 4
    private const val MASK_MIN_LENGTH_FOR_TAIL = 8
    const val MASK = "••••"

    // ---------------------------------------------------------------------------------------------
    // URL
    // ---------------------------------------------------------------------------------------------

    /** Host di [url] senza parentesi quadre (IPv6) e in minuscolo, o null se l'URL non è analizzabile. */
    fun hostOf(url: String): String? = runCatching {
        URI(url).host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun isLoopbackHost(host: String): Boolean = host.lowercase(Locale.ROOT) in LOOPBACK_HOSTS

    /**
     * true se [url] è ammesso: https con host, oppure http verso un loopback esatto; mai userinfo,
     * mai spazi o caratteri di controllo, lunghezza limitata, porta valida. Un `${…}` non è un URL
     * valido: l'interpolazione è vietata qui e il chiamante la segnala con un codice suo.
     */
    fun isUrlAllowed(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_CHARS) return false
        if (url.any { it.isISOControl() || it.isWhitespace() }) return false
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT)
            if (uri.rawUserInfo != null || host.isNullOrEmpty()) return@runCatching false
            if (uri.port != -1 && uri.port !in 1..65_535) return@runCatching false
            when (scheme) {
                "https" -> true
                "http" -> isLoopbackHost(host)
                else -> false
            }
        }.getOrDefault(false)
    }

    /** Due URL hanno lo stesso host (per i redirect). Confronto esatto, senza badare alle maiuscole. */
    fun sameHost(a: String, b: String): Boolean {
        val first = hostOf(a) ?: return false
        return first == hostOf(b)
    }

    // ---------------------------------------------------------------------------------------------
    // Intestazioni e content type
    // ---------------------------------------------------------------------------------------------

    private const val TOKEN_SPECIALS = "!#$%&'*+-.^_`|~"

    private fun isTokenChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in TOKEN_SPECIALS

    /** Nome di intestazione valido (token RFC 7230), non vietato. */
    fun isHeaderNameValid(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_HEADER_NAME_CHARS && name.all(::isTokenChar)

    fun isHeaderNameForbidden(name: String): Boolean =
        name.lowercase(Locale.ROOT) in FORBIDDEN_HEADER_NAMES

    /** Valore di intestazione: non vuoto, senza CR/LF né caratteri di controllo (tab ammesso), limitato. */
    fun isHeaderValueValid(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_HEADER_VALUE_CHARS &&
            value.none { it != '\t' && it.isISOControl() }

    /** Forma di un content type `tipo/sottotipo` con parametri opzionali; niente CR/LF. */
    fun isContentTypeValid(contentType: String): Boolean {
        if (contentType.isBlank() || contentType.length > MAX_CONTENT_TYPE_CHARS) return false
        if (contentType.any { it.isISOControl() }) return false
        val mediaType = contentType.substringBefore(';').trim()
        val parts = mediaType.split('/')
        return parts.size == 2 && parts.all { it.isNotEmpty() && it.all(::isTokenChar) }
    }

    /** Il content type che la richiesta usa davvero: il campo, o l'intestazione `Content-Type`. */
    fun effectiveContentType(action: Action.HttpRequest): String? =
        action.contentType
            ?: action.headers?.entries
                ?.firstOrNull { it.key.lowercase(Locale.ROOT) == CONTENT_TYPE_HEADER }?.value

    /** true se il campo e l'intestazione `Content-Type` dicono cose diverse. */
    fun contentTypeConflicts(action: Action.HttpRequest): Boolean {
        val field = action.contentType ?: return false
        val header = action.headers?.entries
            ?.firstOrNull { it.key.lowercase(Locale.ROOT) == CONTENT_TYPE_HEADER }?.value ?: return false
        return field.trim().lowercase(Locale.ROOT) != header.trim().lowercase(Locale.ROOT)
    }

    private fun mediaTypeOf(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    fun isJsonContentType(contentType: String?): Boolean {
        val mediaType = mediaTypeOf(contentType) ?: return false
        return mediaType == "application/json" || mediaType.endsWith("+json")
    }

    fun isFormContentType(contentType: String?): Boolean =
        mediaTypeOf(contentType) == "application/x-www-form-urlencoded"

    /** Come i valori interpolati entrano nel body, in base al content type effettivo. */
    fun bodyEncoding(action: Action.HttpRequest): InterpolationPolicy.ValueEncoding {
        val contentType = effectiveContentType(action)
        return when {
            isJsonContentType(contentType) -> InterpolationPolicy.ValueEncoding.JSON_STRING
            isFormContentType(contentType) -> InterpolationPolicy.ValueEncoding.FORM
            else -> InterpolationPolicy.ValueEncoding.RAW
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Body
    // ---------------------------------------------------------------------------------------------

    fun bodyBytes(body: String): Int = body.toByteArray(StandardCharsets.UTF_8).size

    /**
     * Contenuto di una stringa JSON per [value]: backslash, virgolette e caratteri di controllo con
     * escape, senza le virgolette esterne. Inserito dentro `"…"` non può chiudere la stringa né
     * aggiungere chiavi o valori.
     */
    fun jsonStringContent(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c < ' ' -> append("\\u").append("%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    /** Percent-encoding UTF-8 per un valore di `application/x-www-form-urlencoded`. */
    fun formEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /**
     * Per ogni riferimento [ranges] di [template]: true se cade DENTRO una stringa JSON (fra
     * virgolette non precedute da backslash). Serve a rifiutare un dato esterno fuori da una stringa,
     * dove l'escape non basterebbe a impedirgli di aggiungere struttura.
     */
    fun jsonStringContexts(template: String, ranges: List<IntRange>): List<Boolean> {
        val result = ArrayList<Boolean>(ranges.size)
        var inString = false
        var index = 0
        var next = 0
        while (index < template.length && next < ranges.size) {
            if (index == ranges[next].first) {
                result += inString
                index = ranges[next].last + 1
                next++
                continue
            }
            val c = template[index]
            if (inString && c == '\\') {
                // Il carattere dopo il backslash è escapato; ma se è l'inizio di un riferimento, il
                // riferimento resta tale (il validatore lo segnala, a runtime ha il contesto «dentro»).
                index += if (index + 1 == ranges[next].first) 1 else 2
                continue
            }
            if (c == '"') inString = !inString
            index++
        }
        while (result.size < ranges.size) result += inString
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Review: mascheratura
    // ---------------------------------------------------------------------------------------------

    /** true se il NOME dell'intestazione contiene `authorization`, `token`, `key` o `secret`. */
    fun isSensitiveHeader(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return SENSITIVE_NAME_PARTS.any { it in lower }
    }

    /**
     * Valore da mostrare in approvazione e nel dettaglio. Un'intestazione sensibile mostra l'eventuale
     * schema e gli ultimi 4 caratteri (`Bearer ••••abcd`); un segreto corto non ne rivela nessuno.
     */
    fun displayValue(name: String, value: String): String {
        if (!isSensitiveHeader(name)) return value
        val trimmed = value.trim()
        val space = trimmed.indexOf(' ')
        val scheme = if (space > 0) trimmed.substring(0, space + 1) else ""
        val secret = trimmed.substring(scheme.length).trim()
        val tail = if (secret.length > MASK_MIN_LENGTH_FOR_TAIL) secret.takeLast(MASK_TAIL_CHARS) else ""
        return scheme + MASK + tail
    }

    // ---------------------------------------------------------------------------------------------
    // Forma dopo la risoluzione
    // ---------------------------------------------------------------------------------------------

    /**
     * Controllo di forma dell'azione già risolta (e dell'azione piatta all'esecuzione): le stesse
     * regole del validatore, senza messaggi. URL e intestazioni non devono contenere `${`: a runtime
     * non vengono mai interpolati.
     */
    fun isResolvedShapeValid(action: Action.HttpRequest): Boolean {
        if (InterpolationPolicy.containsInterpolation(action.url)) return false
        if (!isUrlAllowed(action.url)) return false
        val headers = action.headers.orEmpty()
        if (headers.size > MAX_HEADERS) return false
        val seen = HashSet<String>()
        for ((name, value) in headers) {
            if (InterpolationPolicy.containsInterpolation(name) ||
                InterpolationPolicy.containsInterpolation(value)
            ) return false
            if (!isHeaderNameValid(name) || isHeaderNameForbidden(name) || !isHeaderValueValid(value)) return false
            if (!seen.add(name.lowercase(Locale.ROOT))) return false
        }
        action.contentType?.let { if (!isContentTypeValid(it)) return false }
        if (contentTypeConflicts(action)) return false
        val body = action.body
        if (body != null) {
            if (action.method == HttpMethod.GET) return false
            if (bodyBytes(body) > MAX_BODY_BYTES) return false
        }
        return true
    }
}
