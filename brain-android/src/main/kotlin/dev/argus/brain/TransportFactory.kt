package dev.argus.brain

import okhttp3.OkHttpClient

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
        // Wave 2: OPENAI/GEMINI/OPENROUTER/CUSTOM_OPENAI_COMPAT -> OpenAICompatTransport (S5-S6);
        //         ANTHROPIC -> AnthropicMessagesTransport (S7).
        else -> throw TransportNotImplementedException(config.providerId)
    }
}
