package dev.argus.brain

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Costruisce un [AgentTransport] da una [ProviderConfig]. È l'unico punto dell'app che conosce i
 * transport concreti: il resto del grafo dipende solo dal contratto [AgentTransport].
 */
fun interface TransportFactory {
    fun create(config: ProviderConfig): AgentTransport
}

/**
 * Wave 1: solo HERMES è cablato ([CliBridgeTransport]); ogni altro provider a catalogo fallisce con
 * [TransportNotImplementedException] (i transport concreti arrivano in Wave 2/3). La chiave API NON
 * viene letta alla costruzione: [CliBridgeTransport] la richiede per-richiesta via [ProviderSecrets],
 * così l'hot-swap del token non richiede un riavvio del processo.
 */
class DefaultTransportFactory(
    private val secrets: ProviderSecrets,
    private val client: OkHttpClient = CliBridgeTransport.defaultClient(),
) : TransportFactory {
    override fun create(config: ProviderConfig): AgentTransport = when (config.providerId) {
        ProviderId.HERMES -> CliBridgeTransport(
            baseUrl = config.baseUrl,
            authProvider = BridgeAuthProvider { secrets.apiKey(ProviderId.HERMES) },
            client = client,
        )
        // S5: i provider Chat Completions condividono un solo adapter, parametrizzato dal catalog.
        ProviderId.OPENAI,
        ProviderId.GEMINI,
        ProviderId.OPENROUTER,
        ProviderId.CUSTOM_OPENAI_COMPAT -> OpenAICompatTransport(
            providerId = config.providerId,
            spec = ProviderCatalog.spec(config.providerId),
            config = config,
            apiKey = { secrets.apiKey(config.providerId) },
            client = clientFor(config),
        )
        // S7: ANTHROPIC -> AnthropicMessagesTransport (x-api-key, Messages API dedicata).
        ProviderId.ANTHROPIC -> AnthropicMessagesTransport(
            spec = ProviderCatalog.spec(ProviderId.ANTHROPIC),
            config = config,
            apiKey = { secrets.apiKey(ProviderId.ANTHROPIC) },
            client = client,
        )
    }

    /**
     * Il client del provider. Un server locale (Custom su loopback o rete privata) ha il compile a 180 s
     * ([CompileTimeoutPolicy]): il client di base scade a 125 s e taglierebbe la chiamata prima, quindi per
     * lui i timeout HTTP si allungano a poco oltre il compile. Per gli altri provider il client non cambia
     * (mai più corto di quello ricevuto, e un timeout assente resta assente).
     */
    internal fun clientFor(config: ProviderConfig): OkHttpClient {
        val needed = CompileTimeoutPolicy.timeoutMillis(config.providerId, config.baseUrl) +
            CompileTimeoutPolicy.HTTP_MARGIN_MILLIS
        val call = client.callTimeoutMillis
        if (call == 0 || needed <= call) return client
        return client.newBuilder()
            .callTimeout(needed, TimeUnit.MILLISECONDS)
            .readTimeout(maxOf(needed, client.readTimeoutMillis.toLong()), TimeUnit.MILLISECONDS)
            .writeTimeout(maxOf(needed, client.writeTimeoutMillis.toLong()), TimeUnit.MILLISECONDS)
            .build()
    }
}
