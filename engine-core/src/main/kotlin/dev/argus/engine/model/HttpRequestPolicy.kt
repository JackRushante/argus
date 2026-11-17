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
 *  - `https://` obbligatorio; `http://` solo verso questo telefono o la rete dell'utente
 *    ([LocalNetworkPolicy], 0.8: mai un host pubblico, mai DNS); mai userinfo;
 *  - i valori della cassaforte ([SecretRef]) vanno solo all'host approvato, mai in chiaro fuori dal loopback;
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

    /** Gli host loopback esatti che le regole usavano prima della 0.8 (oggi vale tutto [LocalNetworkPolicy]). */
    val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "::1")

    /** Intestazioni e parametri segreti per richiesta (0.8): le intestazioni contano anche in [MAX_HEADERS]. */
    const val MAX_SECRET_QUERY = 10
    const val MAX_SECRET_PREFIX_CHARS = 64
    const val MAX_QUERY_NAME_CHARS = 64

    /** Nome di un parametro di query segreto: caratteri non riservati (RFC 3986) più le quadre. */
    private val QUERY_NAME = Regex("^[A-Za-z0-9._~\\-\\[\\]]{1,$MAX_QUERY_NAME_CHARS}$")

    /**
     * Frammenti del nome (minuscolo) che marcano un'intestazione come segreta nella review. Meglio
     * mascherare un'intestazione innocua in più che mostrare un segreto.
     */
    private val SENSITIVE_NAME_PARTS = listOf(
        "authorization", "token", "key", "secret", "cookie", "auth", "pass", "pwd", "session", "signature",
        "credential", "bearer",
    )

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
     * true se [url] è ammesso: https con host, oppure http verso questo telefono o la rete dell'utente
     * ([LocalNetworkPolicy.scopeOf], solo dal testo dell'host); mai userinfo, mai spazi o caratteri di
     * controllo, lunghezza limitata, porta valida. Un `${…}` non è un URL valido: l'interpolazione è
     * vietata qui e il chiamante la segnala con un codice suo.
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
                "http" -> LocalNetworkPolicy.scopeOf(host) != LocalNetworkPolicy.HostScope.PUBLIC
                else -> false
            }
        }.getOrDefault(false)
    }

    /** Due URL hanno lo stesso host (per i redirect). Confronto esatto, senza badare alle maiuscole. */
    fun sameHost(a: String, b: String): Boolean {
        val first = hostOf(a) ?: return false
        return first == hostOf(b)
    }

    private fun schemeOf(url: String): String? =
        runCatching { URI(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()

    private fun effectivePort(url: String): Int? = runCatching {
        val uri = URI(url)
        when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> null
        }
    }.getOrNull()

    /**
     * Dove va in chiaro una richiesta `http://` (0.8): [LocalNetworkPolicy.HostScope.LOOPBACK] o
     * [LocalNetworkPolicy.HostScope.LOCAL_NETWORK]; null se l'URL non è `http://` (https, o non analizzabile).
     */
    fun cleartextScope(url: String): LocalNetworkPolicy.HostScope? {
        if (schemeOf(url) != "http") return null
        val host = hostOf(url) ?: return null
        return LocalNetworkPolicy.scopeOf(host)
    }

    /** `http://` verso la rete dell'utente (non loopback): la richiesta viaggia senza cifratura. */
    fun isCleartextOnLocalNetwork(url: String): Boolean =
        cleartextScope(url) == LocalNetworkPolicy.HostScope.LOCAL_NETWORK

    /** Un valore della cassaforte può viaggiare verso [url] solo cifrato, o in chiaro solo su questo telefono. */
    fun secretsAllowedTo(url: String): Boolean = when (schemeOf(url)) {
        "https" -> true
        "http" -> cleartextScope(url) == LocalNetworkPolicy.HostScope.LOOPBACK
        else -> false
    }

    /**
     * Un redirect da [from] a [to] si segue solo se [to] è ammesso, sullo STESSO host, e mai verso uno
     * schema meno protetto (https → http solo sul loopback, come prima della 0.8). Con valori della
     * cassaforte ([carriesSecrets]) il salto deve restare sulla stessa origine (schema, host, porta): un
     * segreto non cambia mai porta né passa in chiaro.
     */
    fun isRedirectAllowed(from: String, to: String, carriesSecrets: Boolean): Boolean {
        if (!isUrlAllowed(to) || !sameHost(from, to)) return false
        val fromScheme = schemeOf(from) ?: return false
        val toScheme = schemeOf(to) ?: return false
        val loopback = hostOf(to)?.let(LocalNetworkPolicy::isLoopback) == true
        if (fromScheme == "https" && toScheme == "http" && !loopback) return false
        if (carriesSecrets) {
            if (fromScheme != toScheme || effectivePort(from) != effectivePort(to)) return false
            if (!secretsAllowedTo(to)) return false
        }
        return true
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

    /** Carattere ammesso nel valore di un'intestazione: ASCII visibile, spazio o tab (come il client HTTP). */
    private fun isHeaderValueChar(c: Char): Boolean = c == '\t' || c in ' '..'~'

    /**
     * Valore di intestazione: non vuoto, limitato, solo ASCII visibile più spazio e tab. Niente CR/LF né
     * altri caratteri di controllo; niente lettere accentate, virgolette tipografiche o emoji, che il
     * client HTTP rifiuta a ogni scatto (0.7.2).
     */
    fun isHeaderValueValid(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_HEADER_VALUE_CHARS && value.all(::isHeaderValueChar)

    /** true se [value] contiene caratteri oltre l'ASCII: il validatore lo dice con un codice suo. */
    fun hasNonAsciiChars(value: String): Boolean = value.any { it.code > '~'.code }

    /** Forma di un content type `tipo/sottotipo` con parametri opzionali; niente CR/LF né caratteri non ASCII. */
    fun isContentTypeValid(contentType: String): Boolean {
        if (contentType.isBlank() || contentType.length > MAX_CONTENT_TYPE_CHARS) return false
        if (!contentType.all(::isHeaderValueChar) || contentType.contains('\t')) return false
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
    // Valori della cassaforte (0.8)
    // ---------------------------------------------------------------------------------------------

    /** Prefisso letterale (`Bearer `): può essere vuoto; caratteri da intestazione, niente `${'$'}{…}`. */
    fun isSecretPrefixValid(prefix: String): Boolean =
        prefix.length <= MAX_SECRET_PREFIX_CHARS && prefix.all(::isHeaderValueChar) &&
            !InterpolationPolicy.containsInterpolation(prefix)

    /**
     * Un'intestazione segreta: nome del segreto valido, nome dell'intestazione con le stesse regole di
     * quelle letterali (e mai `Content-Type`, che decide il body), prefisso letterale valido.
     */
    fun isSecretHeaderValid(ref: SecretRef): Boolean =
        SecretPolicy.isNameValid(ref.secret) && isHeaderNameValid(ref.name) && !isHeaderNameForbidden(ref.name) &&
            ref.name.lowercase(Locale.ROOT) != CONTENT_TYPE_HEADER && isSecretPrefixValid(ref.prefix)

    /** Un parametro di query segreto: nome del segreto valido, nome del parametro non riservato, prefisso valido. */
    fun isSecretQueryValid(ref: SecretRef): Boolean =
        SecretPolicy.isNameValid(ref.secret) && QUERY_NAME.matches(ref.name) && isSecretPrefixValid(ref.prefix)

    /**
     * true se un'intestazione segreta ha lo stesso nome di un'intestazione letterale o di un'altra segreta
     * (senza badare alle maiuscole): chi vince non deve deciderlo il client HTTP.
     */
    fun secretHeaderConflicts(action: Action.HttpRequest): Boolean {
        val secretNames = action.secretHeaders.orEmpty().map { it.name.lowercase(Locale.ROOT) }
        if (secretNames.size != secretNames.toSet().size) return true
        val literal = action.headers.orEmpty().keys.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return secretNames.any { it in literal }
    }

    /** Lo stesso parametro di query segreto due volte. */
    fun secretQueryDuplicates(action: Action.HttpRequest): Boolean {
        val names = action.secretQuery.orEmpty().map { it.name }
        return names.size != names.toSet().size
    }

    /** Forma dei riferimenti alla cassaforte, senza messaggi (validatore, interpolatore, esecutore). */
    fun isSecretShapeValid(action: Action.HttpRequest): Boolean {
        val secretHeaders = action.secretHeaders.orEmpty()
        val secretQuery = action.secretQuery.orEmpty()
        if (action.headers.orEmpty().size + secretHeaders.size > MAX_HEADERS) return false
        if (secretQuery.size > MAX_SECRET_QUERY) return false
        if (!secretHeaders.all(::isSecretHeaderValid) || !secretQuery.all(::isSecretQueryValid)) return false
        if (secretHeaderConflicts(action) || secretQueryDuplicates(action)) return false
        if (SecretPolicy.carriesSecrets(action) && !secretsAllowedTo(action.url)) return false
        return true
    }

    /** `captureAs` ed `extract`: nome di variabile valido; il puntatore solo con la cattura, e ben formato. */
    fun isCaptureShapeValid(action: Action.HttpRequest): Boolean {
        action.captureAs?.let { if (!VarBinding.NAME_REGEX.matches(it)) return false }
        val extract = action.extract ?: return true
        return action.captureAs != null && HttpCapturePolicy.isPointerValid(extract)
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

    /** true se il NOME dell'intestazione contiene uno dei frammenti sensibili (`authorization`, `token`, `cookie`, …). */
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
        return isSecretShapeValid(action) && isCaptureShapeValid(action)
    }
}
