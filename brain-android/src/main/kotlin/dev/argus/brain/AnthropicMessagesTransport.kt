package dev.argus.brain

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CliBridgeParser
import dev.argus.engine.brain.CompileMetaError
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.StateContextClassification
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding
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

    /**
     * true dopo che un compile con `output_config` è stato rifiutato e quello senza ha risposto: questo
     * modello non lo supporta e non si riprova finché il transport vive (cioè finché la configurazione non cambia).
     */
    @Volatile
    private var structuredRejected = false

    init {
        require(base.username.isEmpty() && base.password.isEmpty()) { "baseUrl non deve contenere credenziali" }
        require(base.query == null && base.fragment == null) { "baseUrl non deve contenere query o fragment" }
        require(base.isHttps || allowCleartextForTests && base.host in TEST_HTTP_HOSTS) {
            "Il provider Anthropic richiede HTTPS"
        }
    }

    /**
     * Compile: prompt Hermes + riga sentinel `@@META@@`, oppure (0.5.1, `structuredOutput` del catalogo)
     * output strutturato con `output_config.format` `json_schema`: la risposta è UN oggetto
     * `{reply, draft, error_code}` in cui `draft` è l'oggetto scritto come stringa (lo schema di Anthropic
     * è chiuso e non ricorsivo). Il ragionamento resta libero: la grammatica vale solo per l'output finale.
     * Un 4xx (modello che non lo supporta) fa ripiegare sul protocollo base, senza token spesi; un modello
     * che risponde comunque con la riga sentinel si legge lo stesso.
     */
    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        val cleanMessage = AgentMessageSupport.requireMessage(message)
        val token = requireKey()
        val model = resolveModel()
        val structured = spec.quirks.structuredOutput == StructuredOutputMode.OUTPUT_CONFIG && !structuredRejected
        return compileAt(structured, false, cleanMessage, token, model, manifest, state)
    }

    private suspend fun compileAt(
        structured: Boolean,
        fellBack: Boolean,
        message: String,
        token: String,
        model: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        val request = MessagesRequest(
            model = model,
            maxTokens = COMPILE_MAX_TOKENS,
            system = AgentMessageSupport.compileSystemText(
                structuredRule = AgentMessageSupport.STRUCTURED_JSON_STRING_DRAFT_RULE.takeIf { structured },
            ),
            messages = listOf(InputMessage("user", AgentMessageSupport.compileUserText(message, manifest, state))),
            outputConfig = StructuredOutput.ANTHROPIC_OUTPUT_CONFIG.takeIf { structured },
        )
        val response = try {
            parseResponse(
                execute(buildRequest(token, json.encodeToString(request)), ErrorDiagnostics(listOf(token), clientErrorsOnly = true)),
            )
        } catch (error: TransportException) {
            if (!structured || !StructuredOutput.isRejection(error)) throw error
            return compileAt(false, true, message, token, model, manifest, state)
        }
        // Il protocollo base ha risposto dopo un rifiuto: da ora questo modello parte da lì.
        if (fellBack) structuredRejected = true
        // B15: l'usage viaggia su TUTTI i rami (anche errori di formato e troncamenti): il turno è costato.
        val usage = response.toTurnUsage(fallbackModel = model)
        val fullText = response.textContent()
        val text = fullText.trim()
        // F5: la risposta grezza resta in memoria per le segnalazioni (mai nei log né nel DB).
        val rawText = CompileResult.capRaw(fullText)
        val envelope = if (structured && text.isNotEmpty()) compileParser.parseStructuredCompile(text) else null
        val parsed = envelope ?: if (text.isEmpty()) {
            null
        } else {
            val raw = compileParser.parseCompile(text)
            if (raw.draft == null && raw.metaError == null) raw.copy(metaError = "draft_missing") else raw
        }
        val viaEnvelope = envelope != null
        if (parsed?.draft != null) return parsed.copy(usage = usage, raw = rawText, structured = viaEnvelope)
        // B6: `max_tokens` = tetto di output esaurito. Anthropic non taglia il contesto in silenzio
        // (rifiuta le richieste troppo lunghe): niente controllo `context_truncated`.
        if (response.stopReason == "max_tokens") {
            return CompileResult(
                parsed?.reply.orEmpty(), null, CompileMetaError.OUTPUT_TRUNCATED, usage,
                raw = rawText, structured = viaEnvelope,
            )
        }
        if (parsed == null) return CompileResult("", null, "empty_response", usage, structured = false)
        return parsed.copy(usage = usage, raw = rawText, structured = viaEnvelope)
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult {
        val cleanGoal = AgentMessageSupport.requireGoal(goal)
        val useReplyTool = AgentMessageSupport.requireGenerativeToolset(allowedTools)
        AgentMessageSupport.requireGenerativeContextSources(contextSources, useReplyTool)
        // Reply: esige una notifica WhatsApp 1:1 in arrivo. Sink NOTIFICA #59 (da timer/immediate):
        // nessuna notifica, il testo nasce dal solo goal (+ web/state).
        val notification =
            if (useReplyTool) AgentMessageSupport.requireWhatsAppNotification(context) else null
        val stateLines = if ("state" in contextSources) AgentMessageSupport.safeStateLines(context.state) else emptyList()
        val webRequested = GenerativeContract.TOOL_WEB_SEARCH in allowedTools
        return generate(
            goal = cleanGoal,
            notification = notification,
            stateLines = stateLines,
            webRequested = webRequested,
            useReplyTool = useReplyTool,
        )
    }

    override suspend fun actResolved(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
        runtimeData: List<RuntimeDataBinding>,
    ): ActResult {
        // Stessa validazione di act(): il goal risolto porta solo i marker opachi. Il dato runtime
        // TAINTED viaggia in un messaggio DATA separato (mai nel campo top-level `system`).
        val cleanGoal = AgentMessageSupport.requireGoal(goal)
        val useReplyTool = AgentMessageSupport.requireGenerativeToolset(allowedTools)
        AgentMessageSupport.requireGenerativeContextSources(contextSources, useReplyTool)
        val notification =
            if (useReplyTool) AgentMessageSupport.requireWhatsAppNotification(context) else null
        val stateLines = if ("state" in contextSources) AgentMessageSupport.safeStateLines(context.state) else emptyList()
        val webRequested = GenerativeContract.TOOL_WEB_SEARCH in allowedTools
        return generate(
            goal = cleanGoal,
            notification = notification,
            stateLines = stateLines,
            webRequested = webRequested,
            useReplyTool = useReplyTool,
            runtimeData = runtimeData,
        )
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
        // act v2 è sempre un reply (allowedTools include whatsapp_reply): reply tool sempre attivo.
        return generate(
            goal = cleanGoal,
            notification = notification,
            stateLines = stateLines,
            webRequested = webRequested,
            useReplyTool = true,
        )
    }

    override suspend fun health(): TransportHealth {
        val token = requireKey()
        val model = resolveModel()
        val request = MessagesRequest(
            model = model,
            maxTokens = HEALTH_MAX_TOKENS,
            messages = listOf(InputMessage("user", HEALTH_PING)),
        )
        // Solo il test di connessione chiede il messaggio del provider (e maschera la chiave configurata).
        val response = parseResponse(
            execute(buildRequest(token, json.encodeToString(request)), ErrorDiagnostics(listOf(token))),
        )
        return AnthropicMessagesHealth(model = response.model?.takeIf { it.isNotBlank() } ?: model)
    }

    private suspend fun generate(
        goal: String,
        notification: TriggerEvent.NotificationPosted?,
        stateLines: List<String>,
        webRequested: Boolean,
        useReplyTool: Boolean,
        runtimeData: List<RuntimeDataBinding> = emptyList(),
    ): ActResult {
        val token = requireKey()
        val model = resolveModel()
        // Web search server-side, single-turn: se richiesto e supportato, il server tool web_search
        // affianca gli altri tool e Anthropic esegue il loop di ricerca internamente. Con NONE il web
        // richiesto viene ignorato (degradazione graziosa).
        val applyWeb = webRequested && spec.quirks.webSearch == WebSearchMechanism.ANTHROPIC_TOOL
        // Sink NOTIFICA #59 (useReplyTool=false): NIENTE reply tool (solo eventuale web_search), NESSUN
        // tool_choice forzato, system della NOTIFICA (nessun framing WhatsApp), testo dal blocco `text`
        // via replyText(). Reply (useReplyTool=true): invariato (reply tool + eventuale web_search).
        val tools = when {
            useReplyTool && applyWeb -> listOf(replyToolDef(), webSearchToolDef())
            useReplyTool -> listOf(replyToolDef())
            applyWeb -> listOf(webSearchToolDef())
            else -> null
        }
        val request = MessagesRequest(
            model = model,
            maxTokens = MAX_OUTPUT_TOKENS,
            system = if (useReplyTool) {
                AgentMessageSupport.actSystemText(goal)
            } else {
                AgentMessageSupport.actSystemTextNotification(goal, webAllowed = applyWeb)
            },
            messages = buildList {
                add(
                    InputMessage(
                        "user",
                        if (notification != null) AgentMessageSupport.actUserText(notification, stateLines)
                        else AgentMessageSupport.actUserTextNotification(goal, stateLines),
                    ),
                )
                // Dato runtime TAINTED: messaggio DATA separato, MAI nel campo top-level `system`.
                AgentMessageSupport.actRuntimeDataText(runtimeData)?.let { add(InputMessage("user", it)) }
            },
            tools = tools,
            // Con il web attivo (o senza reply tool) il reply NON va forzato: il testo finale (blocco
            // text dopo i risultati web) arriva via textContent(); forzare il reply tool impedirebbe la
            // ricerca e, nel sink notifica, non esiste alcun reply tool da forzare.
            toolChoice = when {
                !useReplyTool -> null
                applyWeb || spec.quirks.forceToolChoiceAuto -> AUTO_TOOL_CHOICE
                else -> FORCED_REPLY_CHOICE
            },
        )
        val response = parseResponse(
            execute(buildRequest(token, json.encodeToString(request)), ErrorDiagnostics(listOf(token), clientErrorsOnly = true)),
        )
        val usage = response.toTurnUsage(fallbackModel = model)
        return when (val reply = response.replyText(useReplyTool)) {
            is AgentMessageSupport.ReplyExtraction.Text ->
                ActResult(text = reply.value, metaError = null, usage = usage)
            AgentMessageSupport.ReplyExtraction.ToolMissing ->
                ActResult(text = null, metaError = REPLY_TOOL_MISSING, usage = usage)
            AgentMessageSupport.ReplyExtraction.Empty ->
                ActResult(text = null, metaError = "empty_response", usage = usage)
        }
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

    /**
     * Server tool web search di Anthropic (Messages API): `{"type":"web_search_20250305","name":
     * "web_search"}`. Il loop di ricerca è interno lato Anthropic — nessun turno client-side.
     */
    private fun webSearchToolDef(): JsonObject = buildJsonObject {
        put("type", "web_search_20250305")
        put("name", "web_search")
        // S6: tetto di ricerche per richiesta (ognuna è a pagamento).
        put("max_uses", WEB_SEARCH_MAX_USES)
    }

    private fun MessagesResponse.replyText(useReplyTool: Boolean): AgentMessageSupport.ReplyExtraction {
        val fromTool = content
            .firstOrNull { it.type == "tool_use" && it.name == AgentMessageSupport.REPLY_TOOL }
            ?.input?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
        return AgentMessageSupport.extractReply(
            toolText = fromTool,
            // S5: con il web, il testo vero è quello DOPO l'ultimo risultato di ricerca; i blocchi
            // prima sono il preambolo ("cerco...") del modello.
            content = textAfterLastSearchResult().takeIf { it.isNotEmpty() },
            useReplyTool = useReplyTool,
            finishedNormally = stopReason == "end_turn",
        )
    }

    /** Concatena tutti i blocchi `text` della risposta: la strada testuale del compile. */
    private fun MessagesResponse.textContent(): String =
        content.asSequence()
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString(separator = "")

    /** Blocchi `text` successivi all'ultimo `web_search_tool_result` (tutti, se non ce n'è). */
    private fun MessagesResponse.textAfterLastSearchResult(): String {
        val lastResult = content.indexOfLast { it.type == "web_search_tool_result" }
        return content.asSequence()
            .drop(lastResult + 1)
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString(separator = "")
    }

    private fun MessagesResponse.toTurnUsage(fallbackModel: String): TurnUsage? {
        val u = usage ?: return null
        val input = u.inputTokens ?: return null
        val output = u.outputTokens ?: return null
        if (input < 0 || output < 0) return null
        return runCatching {
            TurnUsage(
                inputTokens = input,
                outputTokens = output,
                cachedInputTokens = u.cacheReadInputTokens?.takeIf { it >= 0 },
                model = model?.takeIf { it.isNotBlank() } ?: fallbackModel,
                finishReason = stopReason?.takeIf(FINISH_REASON::matches),
                webSearches = u.serverToolUse?.webSearchRequests
                    ?.takeIf { it in 1..TurnUsage.MAX_WEB_SEARCHES }
                    ?.toInt(),
            )
        }.getOrNull()
    }

    private fun parseResponse(body: String): MessagesResponse =
        try {
            json.decodeFromString(MessagesResponse.serializer(), body)
        } catch (error: SerializationException) {
            throw TransportException(TransportErrorKind.PROTOCOL, "risposta provider non valida", cause = error)
        } catch (error: IllegalArgumentException) {
            throw TransportException(TransportErrorKind.PROTOCOL, "risposta provider non valida", cause = error)
        }

    /**
     * Con [diagnostics] un errore HTTP porta anche il messaggio ripulito del provider
     * ([TransportException.providerMessage]): su ogni errore per `health()`, sui soli 4xx per compile e act.
     */
    private suspend fun execute(request: Request, diagnostics: ErrorDiagnostics? = null): String {
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
                    val result = runCatching { response.use { readSuccessfulJson(it, diagnostics) } }
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

    private fun readSuccessfulJson(response: Response, diagnostics: ErrorDiagnostics?): String {
        if (!response.isSuccessful) throw mapHttpError(response, diagnostics)
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
     * dell'errore viene letto per classificare il 400 di credito e non finisce nel messaggio; solo con
     * [diagnostics] (test di connessione, e 4xx di compile e act) il suo messaggio ripulito va nel campo
     * dedicato [TransportException.providerMessage].
     */
    private fun mapHttpError(response: Response, diagnostics: ErrorDiagnostics?): TransportException {
        val code = response.code
        val kind = when (code) {
            401, 403 -> TransportErrorKind.AUTH
            402 -> TransportErrorKind.BUDGET
            429 -> TransportErrorKind.RATE_LIMIT
            400 -> if (indicatesInsufficientCredit(response)) TransportErrorKind.BUDGET else TransportErrorKind.HTTP
            else -> TransportErrorKind.HTTP
        }
        val providerMessage = diagnostics?.takeIf { it.covers(code) }?.let {
            val body = runCatching { response.peekBody(MAX_ERROR_BODY_BYTES).string() }.getOrNull()
            ProviderErrorMessage.extract(body, it.secrets)
        }
        return TransportException(kind, "Provider HTTP $code", statusCode = code, providerMessage = providerMessage)
    }

    /**
     * Richiesta di diagnostica dell'errore HTTP: [secrets] sono i valori da mascherare nel messaggio.
     * Con [clientErrorsOnly] (compile e act) il messaggio si legge solo sui 4xx: un 5xx è spesso una
     * pagina del gateway, non un testo utile; il test di connessione lo legge su ogni errore.
     */
    private class ErrorDiagnostics(val secrets: List<String?>, val clientErrorsOnly: Boolean = false) {
        fun covers(status: Int): Boolean = !clientErrorsOnly || status in 400..499
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
        /** Solo il compile con output strutturato (0.5.1): `{"format":{"type":"json_schema","schema":{...}}}`. */
        @SerialName("output_config") val outputConfig: JsonObject? = null,
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
        @SerialName("server_tool_use") val serverToolUse: ServerToolUse? = null,
    )

    @Serializable
    private data class ServerToolUse(
        @SerialName("web_search_requests") val webSearchRequests: Long? = null,
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
        const val MAX_OUTPUT_TOKENS = 2_048
        const val COMPILE_MAX_TOKENS = 8_192
        const val WEB_SEARCH_MAX_USES = 3
        const val REPLY_TOOL_MISSING = "reply_tool_missing"
        val FINISH_REASON = Regex("^[A-Za-z0-9_.:-]{1,64}$")
        const val MAX_RESPONSE_BYTES = 512L * 1024L
        const val MAX_ERROR_BODY_BYTES = 16L * 1024L
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
