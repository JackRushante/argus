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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** Salute di un transport OpenAI-compat: metadati minimi, nessun segreto. */
data class OpenAiCompatHealth(override val model: String) : TransportHealth

/**
 * Transport per i provider che espongono l'API OpenAI Chat Completions
 * (OPENAI / GEMINI shim / OPENROUTER / CUSTOM_OPENAI_COMPAT). Un solo adapter, parametrizzato dal
 * [ProviderCatalog] via [ProviderSpec.quirks]: MAI `if (provider == ...)` nel corpo.
 *
 * `act`/`actV2` sono SINGLE-TURN come [CliBridgeTransport]: una sola chiamata al provider genera la
 * reply WhatsApp (nessun loop multi-turno computer-use, fuori scope P3). `compile` resta non
 * implementata fino a S6.
 *
 * Redazione: la chiave viaggia solo nell'header `Authorization`/`x-api-key`; non entra mai in
 * eccezioni, messaggi, `toString` o `TurnUsage`. I body remoti non vengono ripubblicati negli errori.
 */
class OpenAICompatTransport internal constructor(
    override val providerId: ProviderId,
    private val spec: ProviderSpec,
    private val config: ProviderConfig,
    private val apiKey: suspend () -> String?,
    private val client: OkHttpClient,
    private val allowCleartextForTests: Boolean,
) : AgentTransport {

    constructor(
        providerId: ProviderId,
        spec: ProviderSpec,
        config: ProviderConfig,
        apiKey: suspend () -> String?,
        client: OkHttpClient = CliBridgeTransport.defaultClient(),
    ) : this(providerId, spec, config, apiKey, client, allowCleartextForTests = false)

    /**
     * Ultimo consumo token osservato (S12 lo instraderà in [ActResult.usage]; il contratto
     * [AgentTransport] non lo trasporta ancora). Popolato dopo ogni `act`/`actV2` riuscito, `null`
     * se il provider non ha restituito `usage`. Mai un segreto: solo conteggi e nome modello.
     */
    @Volatile
    var lastUsage: TurnUsage? = null
        private set

    private val base = config.baseUrl.trimEnd('/').toHttpUrl()
    private val chatUrl = base.newBuilder()
        .addPathSegment(CHAT_SEGMENT)
        .addPathSegment(COMPLETIONS_SEGMENT)
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
            "Il provider OpenAI-compat richiede HTTPS"
        }
    }

    /**
     * Compile client-side NL → [dev.argus.engine.model.AutomationDraft]. Riproduce il prompt di
     * Hermes (regole vincolanti + schema draft + contesto manifest/state), fa una POST
     * `chat/completions` senza tool, poi parsa la riga finale `@@META@@ {json}` con il parser
     * sentinel dell'app: la strada primaria è la riga sentinel nel testo, indipendente dal
     * tool-calling. Fail-soft: qualsiasi output non conforme diventa un `metaError` tipizzato,
     * mai un'eccezione oltre agli errori di trasporto/configurazione.
     */
    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        val cleanMessage = AgentMessageSupport.requireMessage(message)
        val token = requireKey()
        val model = resolveModel()
        val request = ChatRequest(
            model = model,
            messages = compileMessages(cleanMessage, manifest, state),
            maxTokens = if (spec.quirks.outputCapParam == OutputCapParam.MAX_TOKENS) COMPILE_MAX_TOKENS else null,
            maxCompletionTokens = if (spec.quirks.outputCapParam == OutputCapParam.MAX_COMPLETION_TOKENS) COMPILE_MAX_TOKENS else null,
        )
        val payload = json.encodeToString(request)
        val response = parseChatResponse(execute(buildRequest(token, payload)))
        lastUsage = response.toTurnUsage(fallbackModel = model)
        val text = response.choices.firstOrNull()?.message?.content?.trim()
        if (text.isNullOrEmpty()) return CompileResult("", null, "empty_response")
        val parsed = compileParser.parseCompile(text)
        // "nessun sentinel": il parser restituisce reply-only senza metaError; qui diventa un
        // codice tipizzato coerente col wire Hermes ("draft_missing"), così il chiamante distingue
        // sempre un draft assente da un successo.
        return if (parsed.draft == null && parsed.metaError == null) {
            parsed.copy(metaError = "draft_missing")
        } else {
            parsed
        }
    }

    private fun compileMessages(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): List<ChatMessage> = listOf(
        ChatMessage("system", AgentMessageSupport.compileSystemText()),
        ChatMessage("user", AgentMessageSupport.compileUserText(message, manifest, state)),
    )

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
        val body = json.encodeToString(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage("user", HEALTH_PING)),
                maxTokens = if (spec.quirks.outputCapParam == OutputCapParam.MAX_TOKENS) HEALTH_MAX_TOKENS else null,
                maxCompletionTokens = if (spec.quirks.outputCapParam == OutputCapParam.MAX_COMPLETION_TOKENS) HEALTH_MAX_TOKENS else null,
            ),
        )
        val response = parseChatResponse(execute(buildRequest(token, body)))
        return OpenAiCompatHealth(model = response.model?.takeIf { it.isNotBlank() } ?: model)
    }

    private suspend fun generate(
        goal: String,
        notification: TriggerEvent.NotificationPosted,
        stateLines: List<String>,
    ): ActResult {
        val token = requireKey()
        val model = resolveModel()
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", AgentMessageSupport.actSystemText(goal)),
                ChatMessage("user", AgentMessageSupport.actUserText(notification, stateLines)),
            ),
            tools = listOf(replyToolDef()),
            toolChoice = if (spec.quirks.forceToolChoiceAuto) JsonPrimitive("auto") else FORCED_REPLY_CHOICE,
            maxTokens = if (spec.quirks.outputCapParam == OutputCapParam.MAX_TOKENS) MAX_OUTPUT_TOKENS else null,
            maxCompletionTokens = if (spec.quirks.outputCapParam == OutputCapParam.MAX_COMPLETION_TOKENS) MAX_OUTPUT_TOKENS else null,
        )
        val payload = json.encodeToString(request)
        val response = parseChatResponse(execute(buildRequest(token, payload)))
        lastUsage = response.toTurnUsage(fallbackModel = model)
        val text = response.replyText()
            ?: return ActResult(text = null, metaError = "empty_response")
        return ActResult(text = text, metaError = null)
    }

    private fun buildRequest(token: String, payload: String): Request {
        val builder = Request.Builder()
            .url(chatUrl)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
        when (spec.authStyle) {
            AuthStyle.BEARER -> builder.header("Authorization", "Bearer $token")
            AuthStyle.X_API_KEY -> builder.header("x-api-key", token)
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
        put("type", "function")
        putJsonObject("function") {
            put("name", AgentMessageSupport.REPLY_TOOL)
            put("description", "Invia la risposta WhatsApp all'utente. Fornisci solo il testo.")
            putJsonObject("parameters") {
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
    }

    private fun ChatResponse.replyText(): String? {
        val message = choices.firstOrNull()?.message ?: return null
        val fromTool = message.toolCalls
            ?.firstOrNull { it.function?.name == AgentMessageSupport.REPLY_TOOL }
            ?.function?.arguments
            ?.let { args ->
                runCatching { json.parseToJsonElement(args).jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull()
            }
        val raw = fromTool ?: message.content
        return AgentMessageSupport.cleanReply(raw)
    }

    private fun ChatResponse.toTurnUsage(fallbackModel: String): TurnUsage? {
        val u = usage ?: return null
        val input = u.promptTokens ?: return null
        val output = u.completionTokens ?: return null
        if (input < 0 || output < 0) return null
        return TurnUsage(
            inputTokens = input,
            outputTokens = output,
            cachedInputTokens = u.promptTokensDetails?.cachedTokens?.takeIf { it >= 0 },
            model = model?.takeIf { it.isNotBlank() } ?: fallbackModel,
        )
    }

    private fun parseChatResponse(body: String): ChatResponse =
        try {
            json.decodeFromString(ChatResponse.serializer(), body)
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
        if (!response.isSuccessful) {
            val kind = when (response.code) {
                401, 403 -> TransportErrorKind.AUTH
                402 -> TransportErrorKind.BUDGET
                429 -> TransportErrorKind.RATE_LIMIT
                else -> TransportErrorKind.HTTP
            }
            throw TransportException(kind, "Provider HTTP ${response.code}", statusCode = response.code)
        }
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

    private fun config(message: String) = TransportException(TransportErrorKind.CONFIGURATION, message)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val tools: List<JsonObject>? = null,
        @SerialName("tool_choice") val toolChoice: JsonElement? = null,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    )

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null,
        val model: String? = null,
    )

    @Serializable
    private data class Choice(
        val message: ResponseMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    private data class ResponseMessage(
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    )

    @Serializable
    private data class ToolCall(
        val id: String? = null,
        val type: String? = null,
        val function: FunctionCall? = null,
    )

    @Serializable
    private data class FunctionCall(val name: String? = null, val arguments: String? = null)

    @Serializable
    private data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Long? = null,
        @SerialName("completion_tokens") val completionTokens: Long? = null,
        @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
    )

    @Serializable
    private data class PromptTokensDetails(
        @SerialName("cached_tokens") val cachedTokens: Long? = null,
    )

    private companion object {
        const val CHAT_SEGMENT = "chat"
        const val COMPLETIONS_SEGMENT = "completions"
        const val HEALTH_PING = "ping"
        const val HEALTH_MAX_TOKENS = 1
        const val MAX_OUTPUT_TOKENS = 1_024
        const val COMPILE_MAX_TOKENS = 4_096
        const val MAX_RESPONSE_BYTES = 512L * 1024L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val TEST_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        val FORCED_REPLY_CHOICE: JsonElement = buildJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", AgentMessageSupport.REPLY_TOOL) }
        }
    }
}
