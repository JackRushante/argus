package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateContextClassification
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueCoercion
import dev.argus.engine.model.StateValueType
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Il token arriva da uno store protetto; non viene mai incorporato nel repository o nell'APK. */
fun interface BridgeAuthProvider {
    suspend fun bearerToken(): String?
}

// BridgeErrorKind / BridgeException sono ora typealias su TransportErrorKind / TransportException
// (vedi TransportException.kt): il codice qui sotto resta valido carattere per carattere.

@Serializable
private data class CompileRequestEnvelope(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("request_id") val requestId: String,
    val message: String,
    val manifest: ManifestEnvelope,
    val state: StateEnvelope,
)

@Serializable
private data class ManifestEnvelope(
    @SerialName("device_model") val deviceModel: String,
    @SerialName("android_api") val androidApi: Int,
    @SerialName("shizuku_available") val shizukuAvailable: Boolean,
    @SerialName("granted_permissions") val grantedPermissions: List<String>,
    @SerialName("available_tools") val availableTools: List<String>,
    @SerialName("unavailable_tools") val unavailableTools: Map<String, String>,
    @SerialName("whitelisted_contacts") val whitelistedContacts: List<ContactEnvelope>,
    @SerialName("state_keys") val stateKeys: Map<String, String>,
    /** Trigger armabili ora (P2-2): il server li vincola nel prompt di compilazione. */
    @SerialName("available_triggers") val availableTriggers: List<String>,
    @SerialName("state_readers") val stateReaders: StateReadersEnvelope,
)

@Serializable
private data class StateReadersEnvelope(
    @SerialName("policy_version") val policyVersion: Int,
    val families: List<String>,
    val limits: StateReaderLimitsEnvelope,
)

@Serializable
private data class StateReaderLimitsEnvelope(
    @SerialName("max_query_name_length") val maxQueryNameLength: Int,
    @SerialName("max_sysfs_path_length") val maxSysfsPathLength: Int,
    @SerialName("max_expected_length") val maxExpectedLength: Int,
    @SerialName("timeout_millis") val timeoutMillis: Long,
    @SerialName("max_output_bytes") val maxOutputBytes: Int,
    @SerialName("max_scalar_chars") val maxScalarChars: Int,
)

@Serializable
private data class ContactEnvelope(
    @SerialName("display_name") val displayName: String,
    val id: String,
)

@Serializable
private data class StateEnvelope(
    val values: Map<String, String>,
    @SerialName("foreground_app") val foregroundApp: String?,
    /** La posizione esatta non lascia il device: "qui" viene risolto solo all'approvazione. */
    @SerialName("location_available") val locationAvailable: Boolean,
)

@Serializable
private data class CompileResponseEnvelope(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("request_id") val requestId: String,
    val reply: String,
    val meta: CompileMetaEnvelope,
)

@Serializable
private data class CompileMetaEnvelope(
    val draft: JsonElement?,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
private data class ActRequestEnvelope(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("request_id") val requestId: String,
    val goal: String,
    @SerialName("context_sources") val contextSources: List<String>,
    @SerialName("allowed_tools") val allowedTools: List<String>,
    val context: ActContextEnvelope,
)

@Serializable
private data class ActContextEnvelope(
    // Sink NOTIFICA #59: la notifica è OPZIONALE. Per il reply è sempre presente (wire byte-invariato);
    // per il sink è null e @EncodeDefault(NEVER) la OMETTE dal JSON (ArgusJson ha explicitNulls=true,
    // quindi un null verrebbe altrimenti emesso come "notification":null).
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val notification: NotificationContextEnvelope? = null,
    val state: ActStateEnvelope? = null,
)

@Serializable
private data class NotificationContextEnvelope(
    @SerialName("package") val packageName: String,
    val sender: String?,
    val title: String?,
    val text: String,
    @SerialName("is_group") val isGroup: Boolean,
)

@Serializable
private data class ActStateEnvelope(
    val values: Map<String, String>,
    @SerialName("foreground_app") val foregroundApp: String?,
)

@Serializable
private data class ActResponseEnvelope(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("request_id") val requestId: String,
    val result: ActReplyEnvelope?,
    @SerialName("error_code") val errorCode: String?,
)

@Serializable
private data class ActReplyEnvelope(val text: String)

@Serializable
data class BridgeHealth(
    @SerialName("schema_version") val schemaVersion: Int,
    val status: String,
    override val model: String,
    @SerialName("compile_schema_versions") val compileSchemaVersions: List<Int>,
    @SerialName("act_schema_versions") val actSchemaVersions: List<Int>,
    @SerialName("source_sha256") val sourceSha256: String,
) : TransportHealth

@Serializable
private data class ActV2RequestEnvelope(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("request_id") val requestId: String,
    val goal: String,
    @SerialName("allowed_tools") val allowedTools: List<String>,
    val context: ActV2ContextEnvelope,
)

@Serializable
private data class ActV2ContextEnvelope(
    val notification: NotificationContextEnvelope,
    val state: List<ActV2StateValueEnvelope>,
)

@Serializable
private data class ActV2StateValueEnvelope(
    @SerialName("query_id") val queryId: String,
    val query: StateQuery,
    @SerialName("value_type") val valueType: StateValueType,
    @SerialName("policy_version") val policyVersion: Int,
    val integrity: IntegrityLabel,
    val confidentiality: ConfidentialityLabel,
    val value: String,
)

/**
 * Contratto unico Argus → Hermes: HTTPS, bearer auth, request id idempotente e risposta strict
 * per endpoint (`/compile` v2, `/act` v1+v2, `/health/v2`).
 * Il vecchio fallback `/chat` è intenzionalmente escluso: non è versionato né adatto a un confine
 * che produce dati eseguibili.
 */
class CliBridgeTransport internal constructor(
    baseUrl: String,
    private val authProvider: BridgeAuthProvider,
    private val client: OkHttpClient,
    private val allowCleartextForTests: Boolean,
    private val requestIdFactory: () -> String,
) : AgentTransport {
    override val providerId: ProviderId get() = ProviderId.HERMES

    constructor(
        baseUrl: String,
        authProvider: BridgeAuthProvider,
        client: OkHttpClient = defaultClient(),
    ) : this(
        baseUrl = baseUrl,
        authProvider = authProvider,
        client = client,
        allowCleartextForTests = false,
        requestIdFactory = { UUID.randomUUID().toString() },
    )

    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val compileUrl = base.newBuilder().addPathSegment(COMPILE_PATH_SEGMENT).build()
    private val actUrl = base.newBuilder().addPathSegment(ACT_PATH_SEGMENT).build()
    private val healthUrl = base.newBuilder()
        .addPathSegment(HEALTH_PATH_SEGMENT)
        .addPathSegment(HEALTH_VERSION_SEGMENT)
        .build()

    private val json = Json(ArgusJson) {
        isLenient = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    init {
        require(base.username.isEmpty() && base.password.isEmpty()) { "baseUrl non deve contenere credenziali" }
        require(base.query == null && base.fragment == null) { "baseUrl non deve contenere query o fragment" }
        require(base.isHttps || allowCleartextForTests && base.host in TEST_HTTP_HOSTS) {
            "Il bridge Argus richiede HTTPS"
        }
    }

    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        val token = requireToken()
        val requestId = requestIdFactory().takeIf { REQUEST_ID.matches(it) }
            ?: throw BridgeException(BridgeErrorKind.CONFIGURATION, "request_id non valido")
        val cleanMessage = message.trim().takeIf { it.isNotEmpty() && it.length <= MAX_MESSAGE_CHARS }
            ?: throw BridgeException(BridgeErrorKind.CONFIGURATION, "messaggio vuoto o troppo lungo")
        val envelope = CompileRequestEnvelope(
            schemaVersion = COMPILE_SCHEMA_VERSION,
            requestId = requestId,
            message = cleanMessage,
            manifest = manifest.toEnvelope(),
            state = state.toEnvelope(manifest),
        )
        val payload = json.encodeToString(envelope)
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "richiesta troppo grande")
        }
        val request = Request.Builder()
            .url(compileUrl)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("Idempotency-Key", requestId)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return parseCompileResponse(execute(request), expectedRequestId = requestId)
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult {
        val token = requireToken()
        val cleanGoal = goal.trim().takeIf { it.isNotEmpty() && it.length <= MAX_GOAL_CHARS }
            ?: throw BridgeException(BridgeErrorKind.CONFIGURATION, "goal act vuoto o troppo lungo")
        // Accetta sia il profilo REPLY (whatsapp_reply obbligatorio) sia il sink NOTIFICA #59
        // (nessun reply, solo web opzionale). L'envelope inoltra allowedTools tale e quale: il bridge
        // produce testo plain quando manca whatsapp_reply.
        if (!GenerativeContract.isAllowedToolset(allowedTools) &&
            !GenerativeContract.isNotificationToolset(allowedTools)
        ) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "allowed_tools act non supportati")
        }
        val useReplyTool = GenerativeContract.TOOL_WHATSAPP_REPLY in allowedTools
        val actContext = if (useReplyTool) {
            // Reply: "notification" obbligatoria fra le sorgenti + evento WhatsApp 1:1 verificato;
            // envelope CON la notifica (wire byte-invariato).
            if (contextSources.isEmpty() || contextSources != contextSources.distinct() ||
                "notification" !in contextSources || contextSources.any { it !in ACT_CONTEXT_SOURCES }
            ) {
                throw BridgeException(BridgeErrorKind.CONFIGURATION, "context_sources act non supportate")
            }
            val notification = context.event as? TriggerEvent.NotificationPosted
                ?: throw BridgeException(BridgeErrorKind.CONFIGURATION, "act richiede un evento Notification")
            if (notification.pkg !in WHATSAPP_PACKAGES || notification.isGroup != false) {
                throw BridgeException(BridgeErrorKind.CONFIGURATION, "reply act non autorizzata")
            }
            val cleanText = notification.text.cleanUntrusted(MAX_NOTIFICATION_TEXT_CHARS)
                ?: throw BridgeException(BridgeErrorKind.CONFIGURATION, "testo notifica assente")
            ActContextEnvelope(
                notification = NotificationContextEnvelope(
                    packageName = notification.pkg,
                    sender = notification.sender.cleanUntrusted(MAX_NOTIFICATION_SENDER_CHARS),
                    title = notification.title.cleanUntrusted(MAX_NOTIFICATION_TITLE_CHARS),
                    text = cleanText,
                    isGroup = false,
                ),
                state = context.state.toActEnvelope().takeIf { "state" in contextSources },
            )
        } else {
            // Sink NOTIFICA #59 (da timer/immediate): NESSUNA notifica (né in sorgenti né come evento,
            // niente requireWhatsAppNotification). contextSources = [] oppure il solo [state]. La
            // notifica è OMESSA dall'envelope; lo stato viaggia solo se "state" è fra le sorgenti.
            if (contextSources != contextSources.distinct() ||
                contextSources.any { it !in SINK_CONTEXT_SOURCES }
            ) {
                throw BridgeException(BridgeErrorKind.CONFIGURATION, "context_sources sink non supportate")
            }
            ActContextEnvelope(
                notification = null,
                state = context.state.toActEnvelope().takeIf { "state" in contextSources },
            )
        }
        val requestId = deterministicActRequestId(context)
        val envelope = ActRequestEnvelope(
            schemaVersion = ACT_SCHEMA_VERSION,
            requestId = requestId,
            goal = cleanGoal,
            contextSources = contextSources,
            allowedTools = allowedTools,
            context = actContext,
        )
        val payload = json.encodeToString(envelope)
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "richiesta act troppo grande")
        }
        val request = Request.Builder()
            .url(actUrl)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("Idempotency-Key", requestId)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return parseActResponse(
            execute(request),
            expectedRequestId = requestId,
            expectedSchemaVersion = ACT_SCHEMA_VERSION,
        )
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult {
        val token = requireToken()
        val cleanGoal = action.goal.trim().takeIf {
            it.isNotEmpty() && it.length <= MAX_GOAL_CHARS
        } ?: throw BridgeException(
            BridgeErrorKind.CONFIGURATION,
            "goal act v2 vuoto o troppo lungo",
        )
        if ((!GenerativeContract.isAllowedToolset(action.allowedTools) &&
                !GenerativeContract.isNotificationToolset(action.allowedTools)) ||
            !action.replyTargetSender
        ) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "contratto act v2 non supportato")
        }
        if (action.timeoutMs !in MIN_ACT_TIMEOUT_MILLIS..MAX_ACT_TIMEOUT_MILLIS) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "timeout act v2 non valido")
        }
        if (action.stateContext.isEmpty() ||
            action.stateContext.size > StateContextClassification.MAX_QUERIES ||
            action.stateContext.map { it.query.canonicalId }.distinct().size != action.stateContext.size
        ) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "state context act v2 non valido")
        }
        val notification = context.event as? TriggerEvent.NotificationPosted
            ?: throw BridgeException(BridgeErrorKind.CONFIGURATION, "act v2 richiede un evento Notification")
        if (notification.pkg !in WHATSAPP_PACKAGES || notification.isGroup != false) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "reply act v2 non autorizzata")
        }
        val cleanText = notification.text.cleanUntrusted(MAX_NOTIFICATION_TEXT_CHARS)
            ?: throw BridgeException(BridgeErrorKind.CONFIGURATION, "testo notifica assente")
        val state = action.stateContext.map { approved -> approved.toActV2Envelope(context.state) }
        val requestId = deterministicActRequestId(context)
        val envelope = ActV2RequestEnvelope(
            schemaVersion = ACT_V2_SCHEMA_VERSION,
            requestId = requestId,
            goal = cleanGoal,
            allowedTools = action.allowedTools,
            context = ActV2ContextEnvelope(
                notification = NotificationContextEnvelope(
                    packageName = notification.pkg,
                    sender = notification.sender.cleanUntrusted(MAX_NOTIFICATION_SENDER_CHARS),
                    title = notification.title.cleanUntrusted(MAX_NOTIFICATION_TITLE_CHARS),
                    text = cleanText,
                    isGroup = false,
                ),
                state = state,
            ),
        )
        val payload = json.encodeToString(envelope)
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "richiesta act v2 troppo grande")
        }
        val request = Request.Builder()
            .url(actUrl)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("Idempotency-Key", requestId)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return parseActResponse(
            execute(request),
            expectedRequestId = requestId,
            expectedSchemaVersion = ACT_V2_SCHEMA_VERSION,
        )
    }

    override suspend fun health(): BridgeHealth {
        val token = requireToken()
        val request = Request.Builder()
            .url(healthUrl)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val health = try {
            json.decodeFromString(BridgeHealth.serializer(), execute(request))
        } catch (error: SerializationException) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "health envelope non valido", cause = error)
        } catch (error: IllegalArgumentException) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "health envelope non valido", cause = error)
        }
        if (health.schemaVersion != HEALTH_SCHEMA_VERSION || health.status != "ok" ||
            health.model.isBlank() || health.model.length > 128 ||
            health.compileSchemaVersions != SUPPORTED_COMPILE_SCHEMA_VERSIONS ||
            health.actSchemaVersions != SUPPORTED_ACT_SCHEMA_VERSIONS ||
            !SOURCE_SHA256.matches(health.sourceSha256)) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "health incompatibile")
        }
        return health
    }

    private suspend fun requireToken(): String {
        val token = authProvider.bearerToken()?.trim()
        if (token.isNullOrEmpty() || token.length > MAX_TOKEN_CHARS || token.any { it == '\r' || it == '\n' }) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "token bridge non configurato")
        }
        return token
    }

    private suspend fun execute(request: Request): String {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    val kind = if (error is InterruptedIOException) BridgeErrorKind.TIMEOUT else BridgeErrorKind.NETWORK
                    continuation.resumeWithException(
                        BridgeException(kind, "chiamata bridge fallita", cause = error)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(::readSuccessfulJson) }
                    result.fold(
                        onSuccess = { body ->
                            continuation.resume(body)
                        },
                        onFailure = { error ->
                            val mapped = when (error) {
                                is BridgeException -> error
                                is InterruptedIOException -> BridgeException(
                                    BridgeErrorKind.TIMEOUT, "lettura bridge in timeout", cause = error
                                )
                                is IOException -> BridgeException(
                                    BridgeErrorKind.NETWORK, "lettura bridge fallita", cause = error
                                )
                                else -> BridgeException(
                                    BridgeErrorKind.PROTOCOL, "risposta bridge non valida", cause = error
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
            val kind = if (response.code == 401 || response.code == 403) {
                BridgeErrorKind.AUTH
            } else {
                BridgeErrorKind.HTTP
            }
            throw BridgeException(kind, "Bridge HTTP ${response.code}", statusCode = response.code)
        }
        val body = response.body
            ?: throw BridgeException(BridgeErrorKind.PROTOCOL, "risposta senza body")
        val mediaType = body.contentType()
        if (mediaType == null || mediaType.type != "application" ||
            mediaType.subtype != "json" && !mediaType.subtype.endsWith("+json")) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "content-type non JSON")
        }
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_RESPONSE_BYTES) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "risposta troppo grande")
        }
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES.toLong() + 1L)) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "risposta troppo grande")
        }
        return source.readUtf8()
    }

    private fun parseCompileResponse(body: String, expectedRequestId: String): CompileResult {
        val envelope = try {
            json.decodeFromString(CompileResponseEnvelope.serializer(), body)
        } catch (error: SerializationException) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "envelope /compile non valido", cause = error)
        } catch (error: IllegalArgumentException) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "envelope /compile non valido", cause = error)
        }
        if (envelope.schemaVersion != COMPILE_SCHEMA_VERSION) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "schema_version incompatibile")
        }
        if (envelope.requestId != expectedRequestId) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "request_id non corrispondente")
        }
        if (envelope.reply.length > MAX_REPLY_CHARS) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "reply troppo lunga")
        }
        val errorCode = envelope.meta.errorCode
        if (errorCode != null && !ERROR_CODE.matches(errorCode)) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "error_code non valido")
        }
        val draftElement = envelope.meta.draft
            ?: return CompileResult(envelope.reply, null, errorCode ?: "draft_missing")
        if (errorCode != null) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "draft e error_code non possono coesistere")
        }
        val draft = try {
            json.decodeFromJsonElement(AutomationDraft.serializer(), draftElement)
        } catch (_: Exception) {
            return CompileResult(envelope.reply, null, "draft_invalid")
        }
        return CompileResult(envelope.reply, draft, null)
    }

    private fun parseActResponse(
        body: String,
        expectedRequestId: String,
        expectedSchemaVersion: Int,
    ): ActResult {
        val envelope = try {
            json.decodeFromString(ActResponseEnvelope.serializer(), body)
        } catch (error: SerializationException) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "envelope /act non valido", cause = error)
        } catch (error: IllegalArgumentException) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "envelope /act non valido", cause = error)
        }
        if (envelope.schemaVersion != expectedSchemaVersion) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "schema_version incompatibile")
        }
        if (envelope.requestId != expectedRequestId) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "request_id non corrispondente")
        }
        val errorCode = envelope.errorCode
        if (errorCode != null && !ERROR_CODE.matches(errorCode)) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "error_code act non valido")
        }
        val text = envelope.result?.text
        if ((text == null) == (errorCode == null)) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "result ed error_code act non coerenti")
        }
        if (text != null && (text.isBlank() || text.length > MAX_ACT_REPLY_CHARS ||
                text.any { it.isISOControl() && it != '\n' && it != '\t' })) {
            throw BridgeException(BridgeErrorKind.PROTOCOL, "testo act non valido")
        }
        return ActResult(text = text, metaError = errorCode)
    }

    private fun CapabilityManifest.toEnvelope() = ManifestEnvelope(
        deviceModel = deviceModel,
        androidApi = androidApi,
        shizukuAvailable = shizukuAvailable,
        grantedPermissions = grantedPermissions,
        availableTools = availableTools,
        unavailableTools = unavailableTools,
        whitelistedContacts = whitelistedContacts.map { ContactEnvelope(it.displayName, it.id) },
        stateKeys = stateKeys,
        availableTriggers = availableTriggers,
        stateReaders = StateReadersEnvelope(
            policyVersion = stateReaders.policyVersion,
            families = stateReaders.families.map { it.wireName },
            limits = StateReaderLimitsEnvelope(
                maxQueryNameLength = stateReaders.limits.maxQueryNameLength,
                maxSysfsPathLength = stateReaders.limits.maxSysfsPathLength,
                maxExpectedLength = stateReaders.limits.maxExpectedLength,
                timeoutMillis = stateReaders.limits.timeoutMillis,
                maxOutputBytes = stateReaders.limits.maxOutputBytes,
                maxScalarChars = stateReaders.limits.maxScalarChars,
            ),
        ),
    )

    private fun DeviceState.toEnvelope(manifest: CapabilityManifest): StateEnvelope {
        val safeValues = values.entries
            .asSequence()
            .filter { (key, value) -> key in manifest.stateKeys && SAFE_STATE_VALUE.matches(value) }
            .associate { it.key to it.value }
        val safeForeground = foregroundApp
            ?.takeIf { it.length <= MAX_PACKAGE_CHARS && PACKAGE_NAME.matches(it) }
        return StateEnvelope(
            values = safeValues,
            foregroundApp = safeForeground,
            locationAvailable = location != null,
        )
    }

    private fun DeviceState.toActEnvelope(): ActStateEnvelope {
        val safeValues = values.entries
            .asSequence()
            .filter { (key, value) -> key in StateKeys.ALL && SAFE_STATE_VALUE.matches(value) }
            .associate { it.key to it.value }
        val safeForeground = foregroundApp
            ?.takeIf { it.length <= MAX_PACKAGE_CHARS && PACKAGE_NAME.matches(it) }
        return ActStateEnvelope(safeValues, safeForeground)
    }

    private fun ApprovedStateContext.toActV2Envelope(state: DeviceState): ActV2StateValueEnvelope {
        if (policyVersion != StateQueryPolicy.VERSION ||
            !StateQueryPolicy.validQuery(query) ||
            !StateContextClassification.validValueType(query, valueType) ||
            integrity != IntegrityLabel.CLEAN ||
            !StateContextClassification.covers(
                confidentiality,
                StateContextClassification.minimumConfidentiality(query),
            )
        ) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "classificazione act v2 non valida")
        }
        val raw = state.queryValues[query.canonicalId]
            ?: throw BridgeException(BridgeErrorKind.CONFIGURATION, "valore act v2 non disponibile")
        if (raw.isEmpty() || raw.length > StateQueryPolicy.MAX_SCALAR_CHARS ||
            raw.any(Char::isISOControl) || !StateValueCoercion.compatible(raw, valueType)
        ) {
            throw BridgeException(BridgeErrorKind.CONFIGURATION, "valore act v2 non valido")
        }
        return ActV2StateValueEnvelope(
            queryId = query.canonicalId,
            query = query,
            valueType = valueType,
            policyVersion = policyVersion,
            integrity = integrity,
            confidentiality = confidentiality,
            value = raw,
        )
    }

    private fun String?.cleanUntrusted(maximum: Int): String? {
        val clean = this
            ?.filter { !it.isISOControl() || it == '\n' || it == '\t' }
            ?.trim()
            ?.take(maximum)
        return clean?.takeIf { it.isNotEmpty() }
    }

    private fun deterministicActRequestId(context: FireContext): String {
        val material = "${context.executionId.value}\u0000${context.actionIndex}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return "act-" + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val COMPILE_SCHEMA_VERSION = 2
        const val ACT_SCHEMA_VERSION = 1
        const val ACT_V2_SCHEMA_VERSION = 2
        const val HEALTH_SCHEMA_VERSION = 2
        const val MAX_REQUEST_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val MAX_MESSAGE_CHARS = 8_192
        private const val MAX_GOAL_CHARS = 4_000
        private const val MAX_REPLY_CHARS = 32_768
        private const val MAX_ACT_REPLY_CHARS = 4_096
        private const val MAX_NOTIFICATION_TEXT_CHARS = 4_096
        private const val MAX_NOTIFICATION_TITLE_CHARS = 512
        private const val MAX_NOTIFICATION_SENDER_CHARS = 256
        private const val MIN_ACT_TIMEOUT_MILLIS = 1_000L
        private const val MAX_ACT_TIMEOUT_MILLIS = 120_000L
        private const val MAX_TOKEN_CHARS = 2_048
        private const val MAX_PACKAGE_CHARS = 255
        private const val COMPILE_PATH_SEGMENT = "compile"
        private const val ACT_PATH_SEGMENT = "act"
        private const val HEALTH_PATH_SEGMENT = "health"
        private const val HEALTH_VERSION_SEGMENT = "v2"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val TEST_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val ERROR_CODE = Regex("[a-z][a-z0-9_]{0,63}")
        private val SAFE_STATE_VALUE = Regex("[A-Za-z0-9._:+-]{1,64}")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
        private val SOURCE_SHA256 = Regex("[0-9a-f]{64}")
        private val SUPPORTED_COMPILE_SCHEMA_VERSIONS = listOf(1, COMPILE_SCHEMA_VERSION)
        private val SUPPORTED_ACT_SCHEMA_VERSIONS = listOf(ACT_SCHEMA_VERSION, ACT_V2_SCHEMA_VERSION)
        private val ACT_CONTEXT_SOURCES = setOf("notification", "state")

        /** Sink NOTIFICA #59: sorgenti ammesse senza reply — solo "state" (opzionale), MAI
         *  "notification". Lista vuota valida (testo dal solo goal). */
        private val SINK_CONTEXT_SOURCES = setOf("state")
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        // 125s > timeout azione massimo (120s, MAX_ACT_TIMEOUT_MILLIS): l'HTTP non deve tagliare
        // prima che scada il withTimeout della lane generativa, altrimenti una /act web lenta
        // (ricerca web variabile) fallirebbe con un errore di rete invece di rispettare il budget
        // dell'azione. Il main loop del bridge alza già il MODEL_TIMEOUT lato server.
        fun defaultClient(timeoutSeconds: Long = 125): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}
