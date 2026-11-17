package dev.argus.automation.http

import dev.argus.brain.CleartextGuard
import dev.argus.engine.model.Action
import dev.argus.engine.model.HttpCapturePolicy
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.HttpRequestPolicy
import dev.argus.engine.model.SecretPolicy
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.secrets.SecretValueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Esito di una richiesta: il risultato per il journal e, solo con `captureAs` e un 2xx, il testo catturato.
 * Il testo non si stampa mai.
 */
class HttpRequestOutcome(val result: ActionResult, val captured: String? = null) {
    override fun toString(): String =
        "HttpRequestOutcome(result=$result, captured=${if (captured == null) "<absent>" else "<redacted>"})"
}

/**
 * Esegue [Action.HttpRequest] (0.4.1, 0.8). Ultima difesa dopo validatore e interpolatore: ripete le regole
 * di [HttpRequestPolicy] sull'azione che sta per partire, poi:
 *  - non segue i redirect da solo: li segue lui, al massimo [HttpRequestPolicy.MAX_REDIRECTS], solo
 *    verso lo STESSO host, mai verso uno schema meno protetto ([HttpRequestPolicy.isRedirectAllowed]);
 *  - tutta la richiesta, redirect inclusi, ha [timeoutMs] (15 s);
 *  - `http://` solo verso questo telefono o la rete dell'utente: la guardia [CleartextGuard.LOCAL_NETWORK]
 *    lo ripete su ogni salto, prima che parta un byte;
 *  - 0.8, segreti: i valori si leggono da [secrets] solo qui, allo scatto, e vanno solo nell'intestazione
 *    o nel parametro approvati. Le intestazioni segrete si rimettono solo sui salti verso la STESSA origine
 *    (schema, host, porta: altrimenti il redirect è bloccato prima di inviarle); il parametro di query
 *    segreto va solo sulla prima richiesta. Un segreto non viaggia mai in chiaro fuori dal loopback;
 *  - la risposta si legge al massimo per [HttpRequestPolicy.MAX_RESPONSE_BYTES]: si butta, o con
 *    `captureAs` diventa il testo catturato ([HttpCapturePolicy]). L'esito è riuscito se il codice è 2xx.
 *
 * Nel journal finiscono solo codici: `http_status_<n>`, `http_timeout`, `http_network_error`,
 * `http_tls_error`, `http_redirect_blocked`, `http_too_many_redirects`, `http_request_invalid`,
 * `http_cleartext_secret`, `http_secret_missing`, `http_secret_invalid`, `http_capture_*`.
 * Mai l'URL, le intestazioni, un segreto, il body della richiesta o della risposta.
 */
class HttpRequestExecutor(
    client: OkHttpClient = OkHttpClient(),
    private val timeoutMs: Long = HttpRequestPolicy.TIMEOUT_MS,
    /** 0.8: la cassaforte dei segreti. Default: nessun segreto, una regola che ne usa uno fallisce chiusa. */
    private val secrets: SecretValueSource = SecretValueSource { null },
) {
    // Nessun redirect automatico: la regola «stesso host» non si delega alla libreria.
    // 0.8: `http://` verso questo telefono o la rete dell'utente, la stessa regola del provider Custom.
    private val client: OkHttpClient = CleartextGuard.install(
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false),
        CleartextGuard.LOCAL_NETWORK,
    ).build()

    /** Per i test: la guardia del client lascia passare `http://` verso [host]. */
    internal fun permitsCleartext(host: String): Boolean = CleartextGuard.of(client)?.permits(host) == true

    suspend fun execute(action: Action.HttpRequest): ActionResult = run(action).result

    suspend fun run(action: Action.HttpRequest): HttpRequestOutcome {
        val carriesSecrets = SecretPolicy.carriesSecrets(action)
        if (carriesSecrets && !HttpRequestPolicy.secretsAllowedTo(action.url)) {
            return failure(CLEARTEXT_SECRET)
        }
        if (!HttpRequestPolicy.isResolvedShapeValid(action)) return failure(REQUEST_INVALID)
        // L'approvazione e la policy leggono l'URL con java.net.URI, la richiesta parte con l'HttpUrl di
        // OkHttp: con un segreto i due devono vedere la stessa origine, altrimenti non si legge la cassaforte.
        if (carriesSecrets && !parsersAgree(action.url)) return failure(REQUEST_INVALID)

        val secretHeaders = mutableListOf<Pair<String, String>>()
        for (ref in action.secretHeaders.orEmpty()) {
            val value = secrets.valueFor(ref.secret) ?: return failure(SECRET_MISSING)
            val headerValue = ref.prefix + value
            if (!HttpRequestPolicy.isHeaderValueValid(headerValue)) return failure(SECRET_INVALID)
            secretHeaders += ref.name to headerValue
        }
        var url = action.url
        if (!action.secretQuery.isNullOrEmpty()) {
            val builder = action.url.toHttpUrlOrNull()?.newBuilder() ?: return failure(REQUEST_INVALID)
            for (ref in action.secretQuery.orEmpty()) {
                val value = secrets.valueFor(ref.secret) ?: return failure(SECRET_MISSING)
                builder.addQueryParameter(ref.name, ref.prefix + value)
            }
            url = builder.build().toString()
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var method = action.method
        var body: ByteArray? = action.body?.toByteArray(StandardCharsets.UTF_8)

        for (attempt in 0..HttpRequestPolicy.MAX_REDIRECTS) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) return failure(TIMEOUT)
            val request = try {
                buildRequest(action, url, method, body, secretHeaders)
            } catch (_: IllegalArgumentException) {
                return failure(REQUEST_INVALID)
            }
            val call = client.newBuilder()
                .callTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .connectTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .readTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .writeTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            val response = try {
                call.await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                return failure(codeOf(error))
            }
            response.use {
                val status = it.code
                if (status in REDIRECT_STATUSES) {
                    val location = it.header("Location") ?: return failure("http_status_$status")
                    // Un Location che non è un URL web (file:, javascript:, ftp:) non si risolve: bloccato.
                    val next = it.request.url.resolve(location) ?: return failure(REDIRECT_BLOCKED)
                    val target = next.toString()
                    // Il controllo avviene PRIMA di costruire il salto: un segreto non parte mai verso un
                    // altro host, un'altra porta o uno schema meno protetto.
                    if (!HttpRequestPolicy.isRedirectAllowed(url, target, carriesSecrets)) {
                        return failure(REDIRECT_BLOCKED)
                    }
                    // Come i browser: 301/302/303 diventano GET senza body; 307/308 ripetono la richiesta.
                    if (status != 307 && status != 308) {
                        method = HttpMethod.GET
                        body = null
                    }
                    url = target
                } else if (status !in 200..299) {
                    discard(it)
                    return failure("http_status_$status")
                } else if (action.captureAs == null) {
                    discard(it)
                    return HttpRequestOutcome(ActionResult.Success)
                } else {
                    return capture(it, action.extract)
                }
            }
        }
        return failure(TOO_MANY_REDIRECTS)
    }

    private fun failure(code: String) = HttpRequestOutcome(ActionResult.Failure(code))

    private fun buildRequest(
        action: Action.HttpRequest,
        url: String,
        method: HttpMethod,
        body: ByteArray?,
        secretHeaders: List<Pair<String, String>>,
    ): Request {
        val builder = Request.Builder().url(url)
        action.headers.orEmpty().forEach { (name, value) ->
            // Il content type lo decide il body (campo `contentType` o intestazione): una sola volta.
            if (name.lowercase(Locale.ROOT) != HttpRequestPolicy.CONTENT_TYPE_HEADER) {
                builder.addHeader(name, value)
            }
        }
        secretHeaders.forEach { (name, value) -> builder.header(name, value) }
        val requestBody: RequestBody? = when (method) {
            HttpMethod.GET -> null
            HttpMethod.POST, HttpMethod.PUT -> (body ?: ByteArray(0)).toRequestBody(mediaTypeOf(action))
            HttpMethod.DELETE -> body?.toRequestBody(mediaTypeOf(action))
        }
        return builder.method(method.name, requestBody).build()
    }

    private fun mediaTypeOf(action: Action.HttpRequest) =
        (HttpRequestPolicy.effectiveContentType(action) ?: HttpRequestPolicy.DEFAULT_CONTENT_TYPE)
            .toMediaTypeOrNull()

    /**
     * 0.8: legge il body (al massimo [HttpRequestPolicy.MAX_RESPONSE_BYTES]; oltre fallisce, mai tagliato) e
     * ne ricava il valore catturato. Un errore di lettura è un errore di rete, mai un valore vuoto.
     */
    private fun capture(response: Response, extract: String?): HttpRequestOutcome {
        val bytes = try {
            readBounded(response) ?: return failure(HttpCapturePolicy.RESPONSE_TOO_LARGE)
        } catch (_: IOException) {
            return failure(NETWORK_ERROR)
        }
        val charset = response.body?.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
        return when (val outcome = HttpCapturePolicy.capture(String(bytes, charset), extract)) {
            is HttpCapturePolicy.Outcome.Captured -> HttpRequestOutcome(ActionResult.Success, outcome.text)
            is HttpCapturePolicy.Outcome.Failed -> failure(outcome.code)
        }
    }

    /** Il body intero se sta nel limite, altrimenti null. */
    private fun readBounded(response: Response): ByteArray? {
        val source = response.body?.source() ?: return ByteArray(0)
        val limit = HttpRequestPolicy.MAX_RESPONSE_BYTES.toLong()
        val sink = Buffer()
        while (sink.size <= limit) {
            val read = source.read(sink, minOf(limit + 1 - sink.size, READ_CHUNK_BYTES))
            if (read < 0) return sink.readByteArray()
        }
        return null
    }

    /** Legge e butta fino a 64 KB: basta a chiudere lo scambio. Un errore qui non cambia l'esito. */
    private fun discard(response: Response) {
        runCatching {
            val source = response.body?.source() ?: return@runCatching
            val sink = Buffer()
            var left = HttpRequestPolicy.MAX_RESPONSE_BYTES.toLong()
            while (left > 0) {
                val read = source.read(sink, minOf(left, READ_CHUNK_BYTES))
                if (read < 0) break
                left -= read
                sink.clear()
            }
        }
    }

    private fun codeOf(error: IOException): String = when (error) {
        is InterruptedIOException -> TIMEOUT
        is SSLException -> TLS_ERROR
        else -> NETWORK_ERROR
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            },
        )
    }

    companion object {
        const val REQUEST_INVALID = "http_request_invalid"
        const val TIMEOUT = "http_timeout"
        const val NETWORK_ERROR = "http_network_error"
        const val TLS_ERROR = "http_tls_error"
        const val REDIRECT_BLOCKED = "http_redirect_blocked"
        const val TOO_MANY_REDIRECTS = "http_too_many_redirects"

        /** 0.8: un segreto verso `http://` fuori dal loopback. */
        const val CLEARTEXT_SECRET = "http_cleartext_secret"

        /** 0.8: il segreto nominato dalla regola non è (più) nella cassaforte. */
        const val SECRET_MISSING = "http_secret_missing"

        /** 0.8: il valore del segreto non sta in un'intestazione (per esempio lettere accentate). */
        const val SECRET_INVALID = "http_secret_invalid"

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private const val READ_CHUNK_BYTES = 8_192L

        /**
         * true se OkHttp e [java.net.URI] leggono in [url] lo stesso schema, host e porta: l'origine
         * approvata (e mostrata) è quella a cui partirebbe la richiesta. Un dubbio vale «non d'accordo».
         */
        internal fun parsersAgree(url: String): Boolean {
            val okhttp = url.toHttpUrlOrNull() ?: return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            val port = when {
                uri.port != -1 -> uri.port
                scheme == "https" -> 443
                scheme == "http" -> 80
                else -> return false
            }
            return okhttp.scheme == scheme && okhttp.host == HttpRequestPolicy.hostOf(url) && okhttp.port == port
        }
    }
}
