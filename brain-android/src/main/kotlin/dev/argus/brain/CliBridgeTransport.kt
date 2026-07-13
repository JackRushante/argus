package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.runtime.DeviceState
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Il token arriva da uno store protetto; non viene mai incorporato nel repository o nell'APK. */
fun interface BridgeAuthProvider {
    suspend fun bearerToken(): String?
}

enum class BridgeErrorKind {
    CONFIGURATION,
    TIMEOUT,
    NETWORK,
    AUTH,
    HTTP,
    PROTOCOL,
}

/** Errore di confine con soli metadati sicuri: il body remoto non entra mai nel messaggio. */
class BridgeException(
    val kind: BridgeErrorKind,
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

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
data class BridgeHealth(
    @SerialName("schema_version") val schemaVersion: Int,
    val status: String,
    val model: String,
)

/**
 * Contratto unico Argus → Hermes: HTTPS, bearer auth, request id idempotente e risposta v1 strict.
 * Il vecchio fallback `/chat` è intenzionalmente escluso: non è versionato né adatto a un confine
 * che produce dati eseguibili.
 */
class CliBridgeTransport internal constructor(
    baseUrl: String,
    private val authProvider: BridgeAuthProvider,
    private val client: OkHttpClient,
    private val allowCleartextForTests: Boolean,
    private val requestIdFactory: () -> String,
) {
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
    private val healthUrl = base.newBuilder().addPathSegment(HEALTH_PATH_SEGMENT).build()

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

    suspend fun compile(
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
            schemaVersion = PROTOCOL_SCHEMA_VERSION,
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

    suspend fun health(): BridgeHealth {
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
        if (health.schemaVersion != PROTOCOL_SCHEMA_VERSION || health.status != "ok" ||
            health.model.isBlank() || health.model.length > 128) {
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
        if (envelope.schemaVersion != PROTOCOL_SCHEMA_VERSION) {
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

    private fun CapabilityManifest.toEnvelope() = ManifestEnvelope(
        deviceModel = deviceModel,
        androidApi = androidVersion,
        shizukuAvailable = shizukuAvailable,
        grantedPermissions = grantedPermissions,
        availableTools = availableTools,
        unavailableTools = unavailableTools,
        whitelistedContacts = whitelistedContacts.map { ContactEnvelope(it.displayName, it.id) },
        stateKeys = stateKeys,
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

    companion object {
        const val PROTOCOL_SCHEMA_VERSION = 1
        const val MAX_REQUEST_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val MAX_MESSAGE_CHARS = 8_192
        private const val MAX_REPLY_CHARS = 32_768
        private const val MAX_TOKEN_CHARS = 2_048
        private const val MAX_PACKAGE_CHARS = 255
        private const val COMPILE_PATH_SEGMENT = "compile"
        private const val HEALTH_PATH_SEGMENT = "health"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val TEST_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val ERROR_CODE = Regex("[a-z][a-z0-9_]{0,63}")
        private val SAFE_STATE_VALUE = Regex("[A-Za-z0-9._:+-]{1,64}")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

        fun defaultClient(timeoutSeconds: Long = 60): OkHttpClient = OkHttpClient.Builder()
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
