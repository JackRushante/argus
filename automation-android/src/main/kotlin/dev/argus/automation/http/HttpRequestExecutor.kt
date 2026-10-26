package dev.argus.automation.http

import dev.argus.engine.model.Action
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.HttpRequestPolicy
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Esegue [Action.HttpRequest] (0.4.1). Ultima difesa dopo validatore e interpolatore: ripete le regole
 * di [HttpRequestPolicy] sull'azione che sta per partire, poi:
 *  - non segue i redirect da solo: li segue lui, al massimo [HttpRequestPolicy.MAX_REDIRECTS], solo
 *    verso lo STESSO host e solo se l'URL di arrivo rispetta ancora la policy (niente downgrade a
 *    http, tranne loopback);
 *  - tutta la richiesta, redirect inclusi, ha [timeoutMs] (15 s);
 *  - la risposta si legge al massimo per [HttpRequestPolicy.MAX_RESPONSE_BYTES] e si butta: serve a
 *    chiudere lo scambio, mai a conservare testo. L'esito è riuscito se il codice è 2xx.
 *
 * Nel journal finiscono solo codici: `http_status_<n>`, `http_timeout`, `http_network_error`,
 * `http_tls_error`, `http_redirect_blocked`, `http_too_many_redirects`, `http_request_invalid`.
 * Mai l'URL, le intestazioni, il body della richiesta o della risposta.
 */
class HttpRequestExecutor(
    client: OkHttpClient = OkHttpClient(),
    private val timeoutMs: Long = HttpRequestPolicy.TIMEOUT_MS,
) {
    // Nessun redirect automatico: la regola «stesso host» non si delega alla libreria.
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun execute(action: Action.HttpRequest): ActionResult {
        if (!HttpRequestPolicy.isResolvedShapeValid(action)) {
            return ActionResult.Failure(REQUEST_INVALID)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var url = action.url
        var method = action.method
        var body: ByteArray? = action.body?.toByteArray(StandardCharsets.UTF_8)

        for (attempt in 0..HttpRequestPolicy.MAX_REDIRECTS) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) return ActionResult.Failure(TIMEOUT)
            val request = try {
                buildRequest(action, url, method, body)
            } catch (_: IllegalArgumentException) {
                return ActionResult.Failure(REQUEST_INVALID)
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
                return ActionResult.Failure(codeOf(error))
            }
            response.use {
                val status = it.code
                if (status in REDIRECT_STATUSES) {
                    val location = it.header("Location") ?: return ActionResult.Failure("http_status_$status")
                    // Un Location che non è un URL web (file:, javascript:, ftp:) non si risolve: bloccato.
                    val next = it.request.url.resolve(location) ?: return ActionResult.Failure(REDIRECT_BLOCKED)
                    val target = next.toString()
                    if (!HttpRequestPolicy.isUrlAllowed(target) || !HttpRequestPolicy.sameHost(url, target)) {
                        return ActionResult.Failure(REDIRECT_BLOCKED)
                    }
                    // Come i browser: 301/302/303 diventano GET senza body; 307/308 ripetono la richiesta.
                    if (status != 307 && status != 308) {
                        method = HttpMethod.GET
                        body = null
                    }
                    url = target
                } else {
                    discard(it)
                    return if (status in 200..299) ActionResult.Success else ActionResult.Failure("http_status_$status")
                }
            }
        }
        return ActionResult.Failure(TOO_MANY_REDIRECTS)
    }

    private fun buildRequest(
        action: Action.HttpRequest,
        url: String,
        method: HttpMethod,
        body: ByteArray?,
    ): Request {
        val builder = Request.Builder().url(url)
        action.headers.orEmpty().forEach { (name, value) ->
            // Il content type lo decide il body (campo `contentType` o intestazione): una sola volta.
            if (name.lowercase(Locale.ROOT) != HttpRequestPolicy.CONTENT_TYPE_HEADER) {
                builder.addHeader(name, value)
            }
        }
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

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private const val READ_CHUNK_BYTES = 8_192L
    }
}
