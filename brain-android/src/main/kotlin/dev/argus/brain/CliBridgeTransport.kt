package dev.argus.brain

import dev.argus.engine.brain.CliBridgeParser
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Categoria di errore di trasporto, così il ViewModel può distinguere (retry vs. onboarding vs. bug server). */
enum class BridgeErrorKind { TIMEOUT, NETWORK, HTTP }

/**
 * Errore tipizzato del trasporto verso il bridge Hermes. È una [IOException] così che si distingua
 * naturalmente da [kotlinx.coroutines.CancellationException] (che NON va mai inghiottita).
 */
class BridgeException(
    val kind: BridgeErrorKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Turno opzionale di history per /compile. Non usato in P0-B (compile one-shot); presente per il protocollo. */
data class ChatTurn(val role: String, val content: String)

/**
 * Transport HTTP verso il bridge Hermes (spec §2 rev 3, in ascolto sull'interfaccia Tailscale, porta :8090).
 *
 * Due modalità, selezionabili con [useCompileEndpoint]:
 *  - `true`  → POST `/compile`, body `{message, manifest, history?}` → `{reply, meta:{draft…}, schema_version}`.
 *              Modalità PREFERITA: draft strutturato prodotto lato server.
 *  - `false` → POST `/chat` (ciò che il bridge LIVE espone oggi): legge il `reply` grezzo e lo passa a
 *              [CliBridgeParser.parseCompile], che estrae il draft dal sentinel `@@META@@`.
 *
 * Default = `/chat`: il bridge attuale non ha ancora `/compile` (Task 0). Il flip a `true` avverrà via DI
 * (Task 9) quando l'endpoint atterra, senza toccare i chiamanti.
 *
 * @throws BridgeException su timeout / rete / HTTP non-2xx. HermesBrain la mappa a un [CompileResult] con
 *         `metaError` valorizzato, così l'app non crasha mai per un bridge irraggiungibile.
 */
class CliBridgeTransport(
    baseUrl: String,
    private val useCompileEndpoint: Boolean = false,
    private val client: OkHttpClient = defaultClient(),
    private val parser: CliBridgeParser = CliBridgeParser(),
) {
    private val base = baseUrl.trimEnd('/')

    // Deriva da ArgusJson (classDiscriminator "type" per Trigger/Action) + tollerante come il parser CliBridge.
    private val json = Json(ArgusJson) { isLenient = true; ignoreUnknownKeys = true }

    suspend fun compile(
        message: String,
        manifest: String,
        history: List<ChatTurn> = emptyList(),
    ): CompileResult {
        val path = if (useCompileEndpoint) COMPILE_PATH else CHAT_PATH
        val payload = buildJsonObject {
            put("message", message)
            put("manifest", manifest)
            if (history.isNotEmpty()) putJsonArray("history") {
                history.forEach { addJsonObject { put("role", it.role); put("content", it.content) } }
            }
        }.toString()
        val request = Request.Builder()
            .url(base + path)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val body = execute(request)
        return if (useCompileEndpoint) parseCompileResponse(body)
        else parser.parseCompile(extractReply(body))
    }

    /** Esegue la chiamata OkHttp in modo coroutine-friendly: cancellabile, timeout→TIMEOUT, il resto→NETWORK/HTTP. */
    private suspend fun execute(request: Request): String {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    // SocketTimeoutException estende InterruptedIOException; callTimeout lancia InterruptedIOException.
                    val kind = if (e is InterruptedIOException) BridgeErrorKind.TIMEOUT else BridgeErrorKind.NETWORK
                    cont.resumeWithException(
                        BridgeException(kind, "Bridge ${request.url.encodedPath} non raggiungibile: ${e.message}", e)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            cont.resumeWithException(BridgeException(BridgeErrorKind.HTTP, "Bridge HTTP ${resp.code}"))
                            return
                        }
                        cont.resume(resp.body?.string().orEmpty())
                    }
                }
            })
        }
    }

    /**
     * `/compile`: parse a due stadi, fail-soft. Preserva sempre `reply` anche quando il draft è assente o
     * malformato, e mai lancia (mirror della filosofia di [CliBridgeParser]).
     */
    private fun parseCompileResponse(body: String): CompileResult {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return CompileResult(body.trim(), null, "risposta /compile non JSON: ${e.message}")
        }
        val reply = root["reply"]?.jsonPrimitive?.contentOrNull ?: ""
        val draftEl = (root["meta"] as? JsonObject)?.get("draft")
        if (draftEl == null || draftEl is JsonNull) {
            return CompileResult(reply, null, "risposta /compile senza campo 'draft'")
        }
        return try {
            CompileResult(reply, json.decodeFromJsonElement(AutomationDraft.serializer(), draftEl), null)
        } catch (e: Exception) {
            CompileResult(reply, null, e.message ?: "draft non valido")
        }
    }

    /** `/chat`: estrae il campo `reply` (o usa il body grezzo se non è un oggetto JSON con `reply`). */
    private fun extractReply(body: String): String = try {
        json.parseToJsonElement(body).jsonObject["reply"]?.jsonPrimitive?.contentOrNull ?: body
    } catch (e: Exception) {
        body
    }

    companion object {
        const val COMPILE_PATH = "/compile"
        const val CHAT_PATH = "/chat"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Timeout 60 s di default: la latenza Hermes attesa è 10-30 s (spec §2). */
        fun defaultClient(timeoutSeconds: Long = 60): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
    }
}
