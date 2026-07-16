package dev.argus.brain

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CliBridgeParser
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.StateContextClassification
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Salute del transport Anthropic: metadati minimi, nessun segreto. */
data class AnthropicMessagesHealth(override val model: String) : TransportHealth

/**
 * Transport per l'API Anthropic Messages (`POST {baseUrl}/v1/messages`). Adapter dedicato (Finding 4
 * del design §2): il wire non è OpenAI-compat — `system` è separato dai `messages`, `max_tokens` è
 * OBBLIGATORIO, i tool usano `input_schema` e restituiscono blocchi `tool_use` con `input` già
 * oggetto (non stringa), e l'auth è `x-api-key` + header `anthropic-version`.
 *
 * `act`/`actV2` sono SINGLE-TURN come [CliBridgeTransport]/[OpenAICompatTransport]: una sola chiamata
 * genera la reply WhatsApp (nessun loop computer-use, fuori scope P3). `compile` riusa il prompt
 * Hermes e la riga sentinel `@@META@@` via [CliBridgeParser], senza tool.
 *
 * Redazione: la chiave viaggia solo nell'header `x-api-key`; non entra mai in eccezioni, messaggi,
 * `toString` o [TurnUsage], e i body remoti non vengono ripubblicati negli errori.
 *
 * Il consumo token di ogni turno viaggia dentro [ActResult.usage] (S12), non più in un campo laterale.
 */
class AnthropicMessagesTransport internal constructor(
    private val spec: ProviderSpec,
    private val config: ProviderConfig,
    private val apiKey: suspend () -> String?,
    private val client: OkHttpClient,
    private val allowCleartextForTests: Boolean,
) : AgentTransport {

    constructor(
        spec: ProviderSpec,
        config: ProviderConfig,
        apiKey: suspend () -> String?,
        client: OkHttpClient = CliBridgeTransport.defaultClient(),
    ) : this(spec, config, apiKey, client, allowCleartextForTests = false)

    override val providerId: ProviderId = ProviderId.ANTHROPIC

    private val base = config.baseUrl.trimEnd('/').toHttpUrl()
    private val messagesUrl = base.newBuilder()
        .addPathSegment(API_VERSION_SEGMENT)
        .addPathSegment(MESSAGES_SEGMENT)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = false
        explicitNulls = false
    }

    /** Riusa il parser sentinel dell'app (@@META@@ {json}): fail-soft, mai crash, stesso formato di Hermes. */
    private val compileParser = CliBridgeParser()

    init {
        require(base.username.isEmpty() && base.password.isEmpty()) { "baseUrl non deve contenere credenziali" }
        require(base.query == null && base.fragment == null) { "baseUrl non deve contenere query o fragment" }
        require(base.isHttps || allowCleartextForTests && base.host in TEST_HTTP_HOSTS) {
            "Il provider Anthropic richiede HTTPS"
        }
    }

    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        val cleanMessage = AgentMessageSupport.requireMessage(message)
        val token = requireKey()
        val model = resolveModel()
        val request = MessagesRequest(
            model = model,
            maxTokens = COMPILE_MAX_TOKENS,
            system = AgentMessageSupport.compileSystemText(),
            messages = listOf(InputMessage("user", AgentMessageSupport.compileUserText(cleanMessage, manifest, state))),
        )
        val response = parseResponse(execute(buildRequest(token, json.encodeToString(request))))
        val text = response.textContent().trim()
        if (text.isEmpty()) return CompileResult("", null, "empty_response")
        val parsed = compileParser.parseCompile(text)
        return if (parsed.draft == null && parsed.metaError == null) {
            parsed.copy(metaError = "draft_missing")
        } else {
            parsed
        }
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult {
        val cleanGoal = AgentMessageSupport.requireGoal(goal)
        AgentMessageSupport.requireActContextSources(contextSources)
        AgentMessageSupport.requireReplyTool(allowedTools)
        val notification = AgentMessageSupport.requireWhatsAppNotification(context)
        val stateLines = if ("state" in contextSources) AgentMessageSupport.safeStateLines(context.state) else emptyList()
        return generate(goal = cleanGoal, notification = notification, stateLines = stateLines)
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult {
        val cleanGoal = AgentMessageSupport.requireGoal(action.goal)
        if (action.allowedTools != listOf(AgentMessageSupport.REPLY_TOOL) || !action.replyTargetSender) {
            throw config("contratto act v2 non supportato")
        }
        if (action.timeoutMs !in AgentMessageSupport.MIN_ACT_TIMEOUT_MILLIS..AgentMessageSupport.MAX_ACT_TIMEOUT_MILLIS) {
            throw config("timeout act v2 non valido")
        }
        if (action.stateContext.isEmpty() ||
            action.stateContext.size > StateContextClassification.MAX_QUERIES ||
            action.stateContext.map { it.query.canonicalId }.distinct().size != action.stateContext.size
        ) {
            throw config("state context act v2 non valido")
        }
        val notification = AgentMessageSupport.requireWhatsAppNotification(context)
        val stateLines = action.stateContext.map { approved -> AgentMessageSupport.toStateLine(approved, context.state) }
        return generate(goal = cleanGoal, notification = notification, stateLines = stateLines)
    }

    override suspend fun health(): TransportHealth {
        val token = requireKey()
        val model = resolveModel()
        val request = MessagesRequest(
            model = model,
            maxTokens = HEALTH_MAX_TOKENS,
            messages = listOf(InputMessage("user", HEALTH_PING)),
        )
        val response = parseResponse(execute(buildRequest(token, json.encodeToString(request))))
        return AnthropicMessagesHealth(model = response.model?.takeIf { it.isNotBlank() } ?: model)
    }

    private suspend fun generate(
        goal: String,
        notification: TriggerEvent.NotificationPosted,
        stateLines: List<String>,
    ): ActResult {
        val token = requireKey()
        val model = resolveModel()
        val request = MessagesRequest(
            model = model,
            maxTokens = MAX_OUTPUT_TOKENS,
            system = AgentMessageSupport.actSystemText(goal),
            messages = listOf(InputMessage("user", AgentMessageSupport.actUserText(notification, stateLines))),
            tools = listOf(replyToolDef()),
            toolChoice = if (spec.quirks.forceToolChoiceAuto) AUTO_TOOL_CHOICE else FORCED_REPLY_CHOICE,
        )
        val response = parseResponse(execute(buildRequest(token, json.encodeToString(request))))
        val usage = response.toTurnUsage(fallbackModel = model)
        val text = response.replyText()
            ?: return ActResult(text = null, metaError = "empty_response", usage = usage)
        return ActResult(text = text, metaError = null, usage = usage)
    }

    private fun buildRequest(token: String, payload: String): Request {
        val builder = Request.Builder()
            .url(messagesUrl)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
        when (spec.authStyle) {
            AuthStyle.X_API_KEY -> builder.header("x-api-key", token)
            AuthStyle.BEARER -> builder.header("Authorization", "Bearer $token")
        }
        spec.quirks.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private suspend fun requireKey(): String = AgentMessageSupport.requireKey(apiKey())

    private fun resolveModel(): String =
        config.model?.trim()?.takeIf { it.isNotEmpty() }
            ?: spec.defaultModels.firstOrNull()
            ?: throw config("modello non configurato")

    private fun replyToolDef(): JsonObject = buildJsonObject {
        put("name", AgentMessageSupport.REPLY_TOOL)
        put("description", "Invia la risposta WhatsApp all'utente. Fornisci solo il testo.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Il testo della risposta da inviare.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("text")) }
            put("additionalProperties", false)
        }
    }

    private fun MessagesResponse.replyText(): String? {
        val fromTool = content
            .firstOrNull { it.type == "tool_use" && it.name == AgentMessageSupport.REPLY_TOOL }
            ?.input?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
        val raw = fromTool ?: textContent().takeIf { it.isNotEmpty() }
        return AgentMessageSupport.cleanReply(raw)
    }

    /** Concatena tutti i blocchi `text` della risposta: la strada testuale (compile e fallback act). */
    private fun MessagesResponse.textContent(): String =
        content.asSequence()
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString(separator = "")

    private fun MessagesResponse.toTurnUsage(fallbackModel: String): TurnUsage? {
        val u = usage ?: return null
        val input = u.inputTokens ?: return null
        val output = u.outputTokens ?: return null
        if (input < 0 || output < 0) return null
        return TurnUsage(
            inputTokens = input,
            outputTokens = output,
            cachedInputTokens = u.cacheReadInputTokens?.takeIf { it >= 0 },
            model = model?.takeIf { it.isNotBlank() } ?: fallbackModel,
        )
    }

    private fun parseResponse(body: String): MessagesResponse =
        try {
            json.decodeFromString(MessagesResponse.serializer(), body)
        } catch (error: SerializationException) {
            throw TransportException(TransportErrorKind.PROTOCOL, "risposta provider non valida", cause = error)
        } catch (error: IllegalArgumentException) {
            throw TransportException(TransportErrorKind.PROTOCOL, "risposta provider non valida", cause = error)
        }

    private suspend fun execute(request: Request): String {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    val kind = if (error is InterruptedIOException) {
                        TransportErrorKind.TIMEOUT
                    } else {
                        TransportErrorKind.NETWORK
                    }
                    continuation.resumeWithException(
                        TransportException(kind, "chiamata provider fallita", cause = error),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(::readSuccessfulJson) }
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { error ->
                            val mapped = when (error) {
                                is TransportException -> error
                                is InterruptedIOException -> TransportException(
                                    TransportErrorKind.TIMEOUT, "lettura provider in timeout", cause = error,
                                )
                                is IOException -> TransportException(
                                    TransportErrorKind.NETWORK, "lettura provider fallita", cause = error,
                                )
                                else -> TransportException(
                                    TransportErrorKind.PROTOCOL, "risposta provider non valida", cause = error,
                                )
                            }
                            continuation.resumeWithException(mapped)
                        },
                    )
                }
            })
        }
    }

    private fun readSuccessfulJson(response: Response): String {
        if (!response.isSuccessful) throw mapHttpError(response)
        val body = response.body
            ?: throw TransportException(TransportErrorKind.PROTOCOL, "risposta senza body")
        val mediaType = body.contentType()
        if (mediaType == null || mediaType.type != "application" ||
            mediaType.subtype != "json" && !mediaType.subtype.endsWith("+json")
        ) {
            throw TransportException(TransportErrorKind.PROTOCOL, "content-type non JSON")
        }
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw TransportException(TransportErrorKind.PROTOCOL, "risposta troppo grande")
        }
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1L)) {
            throw TransportException(TransportErrorKind.PROTOCOL, "risposta troppo grande")
        }
        return source.readUtf8()
    }

    /**
     * Mappa gli status Anthropic senza mai ripubblicare il body remoto: 401/403->AUTH, 402->BUDGET,
     * 400 con saldo insufficiente->BUDGET (altrimenti HTTP), 429->RATE_LIMIT, resto->HTTP. Il body
     * dell'errore viene letto SOLO per classificare il 400 di credito e non finisce nel messaggio.
     */
    private fun mapHttpError(response: Response): TransportException {
        val code = response.code
        val kind = when (code) {
            401, 403 -> TransportErrorKind.AUTH
            402 -> TransportErrorKind.BUDGET
            429 -> TransportErrorKind.RATE_LIMIT
            400 -> if (indicatesInsufficientCredit(response)) TransportErrorKind.BUDGET else TransportErrorKind.HTTP
            else -> TransportErrorKind.HTTP
        }
        return TransportException(kind, "Provider HTTP $code", statusCode = code)
    }

    private fun indicatesInsufficientCredit(response: Response): Boolean {
        val body = runCatching { response.peekBody(MAX_RESPONSE_BYTES).string() }.getOrNull() ?: return false
        val parsed = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()
        val haystack = buildString {
            parsed?.error?.type?.let { append(it).append(' ') }
            parsed?.error?.message?.let { append(it) }
        }.lowercase()
        return CREDIT_HINTS.any { it in haystack }
    }

    private fun config(message: String) = TransportException(TransportErrorKind.CONFIGURATION, message)

    @Serializable
    private data class MessagesRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String? = null,
        val messages: List<InputMessage>,
        val tools: List<JsonObject>? = null,
        @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    )

    @Serializable
    private data class InputMessage(val role: String, val content: String)

    @Serializable
    private data class MessagesResponse(
        val content: List<ContentBlock> = emptyList(),
        val model: String? = null,
        val usage: Usage? = null,
        @SerialName("stop_reason") val stopReason: String? = null,
    )

    @Serializable
    private data class ContentBlock(
        val type: String? = null,
        val text: String? = null,
        val name: String? = null,
        val input: JsonObject? = null,
    )

    @Serializable
    private data class Usage(
        @SerialName("input_tokens") val inputTokens: Long? = null,
        @SerialName("output_tokens") val outputTokens: Long? = null,
        @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long? = null,
    )

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val type: String? = null, val message: String? = null)

    private companion object {
        const val API_VERSION_SEGMENT = "v1"
        const val MESSAGES_SEGMENT = "messages"
        const val HEALTH_PING = "ping"
        const val HEALTH_MAX_TOKENS = 1
        const val MAX_OUTPUT_TOKENS = 1_024
        const val COMPILE_MAX_TOKENS = 4_096
        const val MAX_RESPONSE_BYTES = 512L * 1024L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val TEST_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        val CREDIT_HINTS = listOf("credit", "billing", "insufficient")
        val FORCED_REPLY_CHOICE: JsonElement = buildJsonObject {
            put("type", "tool")
            put("name", AgentMessageSupport.REPLY_TOOL)
        }
        val AUTO_TOOL_CHOICE: JsonElement = buildJsonObject { put("type", "auto") }
    }
}
