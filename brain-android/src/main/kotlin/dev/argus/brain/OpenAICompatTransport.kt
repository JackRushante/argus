package dev.argus.brain

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CliBridgeParser
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.StateContextClassification
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
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
import okhttp3.HttpUrl
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
 *
 * Il consumo token di ogni turno viaggia dentro [ActResult.usage] (S12), non più in un campo laterale.
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

    private val base = config.baseUrl.trimEnd('/').toHttpUrl()
    private val chatUrl = base.newBuilder()
        .addPathSegment(CHAT_SEGMENT)
        .addPathSegment(COMPLETIONS_SEGMENT)
        .build()

    /** Endpoint OpenAI Responses API (`{base}/responses`): usato solo dal path web [WebSearchMechanism.OPENAI_RESPONSES]. */
    private val responsesUrl = base.newBuilder()
        .addPathSegment(RESPONSES_SEGMENT)
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
        val webRequested = GenerativeContract.TOOL_WEB_SEARCH in allowedTools
        return generate(goal = cleanGoal, notification = notification, stateLines = stateLines, webRequested = webRequested)
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult {
        val cleanGoal = AgentMessageSupport.requireGoal(action.goal)
        if (!GenerativeContract.isAllowedToolset(action.allowedTools) || !action.replyTargetSender) {
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
        val webRequested = GenerativeContract.TOOL_WEB_SEARCH in action.allowedTools
        return generate(goal = cleanGoal, notification = notification, stateLines = stateLines, webRequested = webRequested)
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
        webRequested: Boolean,
    ): ActResult {
        val token = requireKey()
        val baseModel = resolveModel()
        // Web search server-side, single-turn: applica il meccanismo del provider SOLO se il web è
        // richiesto E supportato. Se richiesto ma NONE → degradazione graziosa (genera normalmente).
        val applyWeb = webRequested && spec.quirks.webSearch != WebSearchMechanism.NONE
        // OpenAI e Gemini fanno web con endpoint/codec DIVERSI da Chat Completions: il provider esegue il
        // loop web internamente e genera il testo direttamente (nessun reply tool). Deviamo prima di
        // costruire la ChatRequest.
        if (applyWeb) {
            when (spec.quirks.webSearch) {
                WebSearchMechanism.OPENAI_RESPONSES ->
                    return generateViaResponses(goal, notification, stateLines, token, baseModel)
                WebSearchMechanism.GEMINI_NATIVE ->
                    return generateViaGeminiNative(goal, notification, stateLines, token, baseModel)
                else -> Unit // OPENROUTER_ONLINE resta su Chat Completions (slug `:online`).
            }
        }
        // OpenRouter attiva il loop web con lo slug `<model>:online`; gli altri lasciano il modello com'è.
        val model = if (applyWeb && spec.quirks.webSearch == WebSearchMechanism.OPENROUTER_ONLINE) {
            "$baseModel:online"
        } else {
            baseModel
        }
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", AgentMessageSupport.actSystemText(goal)),
                ChatMessage("user", AgentMessageSupport.actUserText(notification, stateLines)),
            ),
            tools = listOf(replyToolDef()),
            // Con il web attivo il reply NON va forzato: forzarlo impedirebbe la ricerca. `replyText()`
            // fa `fromTool ?: content`, quindi il testo finale post-web (content) arriva comunque.
            toolChoice = if (applyWeb || spec.quirks.forceToolChoiceAuto) JsonPrimitive("auto") else FORCED_REPLY_CHOICE,
            maxTokens = if (spec.quirks.outputCapParam == OutputCapParam.MAX_TOKENS) MAX_OUTPUT_TOKENS else null,
            maxCompletionTokens = if (spec.quirks.outputCapParam == OutputCapParam.MAX_COMPLETION_TOKENS) MAX_OUTPUT_TOKENS else null,
        )
        val payload = json.encodeToString(request)
        val response = parseChatResponse(execute(buildRequest(token, payload)))
        val usage = response.toTurnUsage(fallbackModel = baseModel)
        val text = response.replyText()
            ?: return ActResult(text = null, metaError = "empty_response", usage = usage)
        return ActResult(text = text, metaError = null, usage = usage)
    }

    /**
     * Web OpenAI via Responses API (`POST {base}/responses`, es. `https://api.openai.com/v1/responses`),
     * auth Bearer come Chat Completions. Body: `{"model","input":[{role,content}...],"tools":[{"type":
     * "web_search"}]}` — nessun reply tool, il modello genera il testo direttamente. La risposta ha
     * `output` come array di item eterogenei: prendiamo l'item `type=message` e concateniamo i suoi
     * `content[].text` con `type=output_text` (gli altri, es. `web_search_call`, si ignorano). Usage da
     * `usage.input_tokens`/`usage.output_tokens`. Single-turn: il loop web è interno lato OpenAI.
     */
    private suspend fun generateViaResponses(
        goal: String,
        notification: TriggerEvent.NotificationPosted,
        stateLines: List<String>,
        token: String,
        baseModel: String,
    ): ActResult {
        val request = ResponsesRequest(
            model = baseModel,
            input = listOf(
                ResponsesInputMessage("system", AgentMessageSupport.actSystemTextPlain(goal)),
                ResponsesInputMessage("user", AgentMessageSupport.actUserText(notification, stateLines)),
            ),
            tools = listOf(WEB_SEARCH_TOOL),
        )
        val payload = json.encodeToString(request)
        val httpRequest = buildBearerRequest(responsesUrl, token, payload)
        val response = parseJson(execute(httpRequest), ResponsesResponse.serializer())
        val usage = response.toTurnUsage(fallbackModel = baseModel)
        val text = response.replyText()
            ?: return ActResult(text = null, metaError = "empty_response", usage = usage)
        return ActResult(text = text, metaError = null, usage = usage)
    }

    /**
     * Web Gemini via API nativa `generateContent` (`POST {geminiHost}/v1beta/models/{model}:
     * generateContent`, header `x-goog-api-key`), derivata dal baseUrl compat togliendo `/openai`. Body:
     * `{"contents":[{"role":"user","parts":[{text}]}],"systemInstruction":{"parts":[{text}]},"tools":
     * [{"google_search":{}}]}` — nessun reply tool, il modello genera il testo direttamente. La risposta
     * ha il testo in `candidates[0].content.parts[].text` (concatenati); usage da
     * `usageMetadata.promptTokenCount`/`candidatesTokenCount`. Single-turn: il grounding è interno lato Google.
     */
    private suspend fun generateViaGeminiNative(
        goal: String,
        notification: TriggerEvent.NotificationPosted,
        stateLines: List<String>,
        token: String,
        baseModel: String,
    ): ActResult {
        val request = GeminiNativeRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(AgentMessageSupport.actUserText(notification, stateLines))),
                ),
            ),
            systemInstruction = GeminiSystemInstruction(
                parts = listOf(GeminiPart(AgentMessageSupport.actSystemTextPlain(goal))),
            ),
            tools = listOf(GOOGLE_SEARCH_TOOL),
        )
        val payload = json.encodeToString(request)
        val httpRequest = buildGoogleApiKeyRequest(geminiNativeUrl(baseModel), token, payload)
        val response = parseJson(execute(httpRequest), GeminiNativeResponse.serializer())
        val usage = response.toTurnUsage(fallbackModel = baseModel)
        val text = response.replyText()
            ?: return ActResult(text = null, metaError = "empty_response", usage = usage)
        return ActResult(text = text, metaError = null, usage = usage)
    }

    /**
     * URL nativo Gemini: parte dal baseUrl compat (`.../v1beta/openai`), toglie l'ultimo segmento
     * `openai` per arrivare a `.../v1beta`, poi aggiunge `models/{model}:generateContent`. Il `:` resta
     * letterale nel segmento di path (pchar valido, okhttp non lo percent-encoda).
     */
    private fun geminiNativeUrl(model: String): HttpUrl {
        val builder = base.newBuilder()
        val segments = base.pathSegments
        if (segments.isNotEmpty() && segments.last() == OPENAI_COMPAT_SEGMENT) {
            builder.removePathSegment(segments.size - 1)
        }
        return builder
            .addPathSegment(MODELS_SEGMENT)
            .addPathSegment("$model:$GENERATE_CONTENT_ACTION")
            .build()
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

    /** POST con auth Bearer verso [url] (Responses API): la chiave viaggia solo nell'header, mai altrove. */
    private fun buildBearerRequest(url: HttpUrl, token: String, payload: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

    /** POST con auth `x-goog-api-key` verso [url] (Gemini nativo): NON Bearer, la chiave solo nell'header. */
    private fun buildGoogleApiKeyRequest(url: HttpUrl, token: String, payload: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header(GOOG_API_KEY_HEADER, token)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

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

    private fun parseChatResponse(body: String): ChatResponse = parseJson(body, ChatResponse.serializer())

    /** Deserializza in [T] mappando ogni fallita in un errore PROTOCOL tipizzato, senza ripubblicare il body. */
    private fun <T> parseJson(body: String, deserializer: DeserializationStrategy<T>): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (error: SerializationException) {
            throw TransportException(TransportErrorKind.PROTOCOL, "risposta provider non valida", cause = error)
        } catch (error: IllegalArgumentException) {
            throw TransportException(TransportErrorKind.PROTOCOL, "risposta provider non valida", cause = error)
        }

    /** Testo Responses: item `type=message` → `content[]` `type=output_text` concatenati (il resto si ignora). */
    private fun ResponsesResponse.replyText(): String? {
        val raw = output.asSequence()
            .filter { it.type == "message" }
            .flatMap { (it.content ?: emptyList()).asSequence() }
            .filter { it.type == "output_text" }
            .mapNotNull { it.text }
            .joinToString(separator = "")
            .takeIf { it.isNotEmpty() }
        return AgentMessageSupport.cleanReply(raw)
    }

    private fun ResponsesResponse.toTurnUsage(fallbackModel: String): TurnUsage? {
        val u = usage ?: return null
        val input = u.inputTokens ?: return null
        val output = u.outputTokens ?: return null
        if (input < 0 || output < 0) return null
        return TurnUsage(
            inputTokens = input,
            outputTokens = output,
            model = model?.takeIf { it.isNotBlank() } ?: fallbackModel,
        )
    }

    /** Testo Gemini nativo: `candidates[0].content.parts[].text` concatenati. */
    private fun GeminiNativeResponse.replyText(): String? {
        val raw = candidates.firstOrNull()?.content?.parts.orEmpty()
            .asSequence()
            .mapNotNull { it.text }
            .joinToString(separator = "")
            .takeIf { it.isNotEmpty() }
        return AgentMessageSupport.cleanReply(raw)
    }

    private fun GeminiNativeResponse.toTurnUsage(fallbackModel: String): TurnUsage? {
        val u = usageMetadata ?: return null
        val input = u.promptTokenCount ?: return null
        val output = u.candidatesTokenCount ?: return null
        if (input < 0 || output < 0) return null
        return TurnUsage(
            inputTokens = input,
            outputTokens = output,
            model = modelVersion?.takeIf { it.isNotBlank() } ?: fallbackModel,
        )
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

    // --- Responses API (OpenAI web, #57) ---

    @Serializable
    private data class ResponsesRequest(
        val model: String,
        val input: List<ResponsesInputMessage>,
        val tools: List<JsonObject>,
    )

    @Serializable
    private data class ResponsesInputMessage(val role: String, val content: String)

    @Serializable
    private data class ResponsesResponse(
        val output: List<ResponsesOutputItem> = emptyList(),
        val usage: ResponsesUsage? = null,
        val model: String? = null,
    )

    @Serializable
    private data class ResponsesOutputItem(
        val type: String? = null,
        val content: List<ResponsesContentPart>? = null,
    )

    @Serializable
    private data class ResponsesContentPart(
        val type: String? = null,
        val text: String? = null,
    )

    @Serializable
    private data class ResponsesUsage(
        @SerialName("input_tokens") val inputTokens: Long? = null,
        @SerialName("output_tokens") val outputTokens: Long? = null,
    )

    // --- API nativa Gemini generateContent (Gemini web, #57) ---

    @Serializable
    private data class GeminiNativeRequest(
        val contents: List<GeminiContent>,
        val systemInstruction: GeminiSystemInstruction? = null,
        val tools: List<JsonObject>,
    )

    @Serializable
    private data class GeminiContent(val role: String, val parts: List<GeminiPart>)

    @Serializable
    private data class GeminiPart(val text: String)

    @Serializable
    private data class GeminiSystemInstruction(val parts: List<GeminiPart>)

    @Serializable
    private data class GeminiNativeResponse(
        val candidates: List<GeminiCandidate> = emptyList(),
        val usageMetadata: GeminiUsageMetadata? = null,
        val modelVersion: String? = null,
    )

    @Serializable
    private data class GeminiCandidate(val content: GeminiResponseContent? = null)

    @Serializable
    private data class GeminiResponseContent(val parts: List<GeminiResponsePart> = emptyList())

    @Serializable
    private data class GeminiResponsePart(val text: String? = null)

    @Serializable
    private data class GeminiUsageMetadata(
        val promptTokenCount: Long? = null,
        val candidatesTokenCount: Long? = null,
    )

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
        const val RESPONSES_SEGMENT = "responses"
        const val MODELS_SEGMENT = "models"
        const val OPENAI_COMPAT_SEGMENT = "openai"
        const val GENERATE_CONTENT_ACTION = "generateContent"
        const val GOOG_API_KEY_HEADER = "x-goog-api-key"
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

        /** Tool web della Responses API OpenAI: `{"type":"web_search"}`. Loop di ricerca interno lato OpenAI. */
        val WEB_SEARCH_TOOL: JsonObject = buildJsonObject { put("type", "web_search") }

        /** Tool grounding dell'API nativa Gemini: `{"google_search":{}}`. Loop di ricerca interno lato Google. */
        val GOOGLE_SEARCH_TOOL: JsonObject = buildJsonObject { putJsonObject("google_search") {} }
    }
}
