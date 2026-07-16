package dev.argus.brain

/** Stile di autenticazione HTTP del provider. Il transport monta l'header dallo store cifrato. */
enum class AuthStyle { BEARER, X_API_KEY }

/** Come il transport nomina il tetto output: OpenAI gpt-5.x rigetta max_tokens. */
enum class OutputCapParam { MAX_TOKENS, MAX_COMPLETION_TOKENS }

/** Flag di comportamento per-provider: MAI `if (provider == ...)` dentro un transport. */
data class ProviderQuirks(
    val forceToolChoiceAuto: Boolean = false,
    val outputCapParam: OutputCapParam = OutputCapParam.MAX_TOKENS,
    val extraBodyPassthrough: Boolean = false,
    /** Header addizionali NON di autenticazione (es. anthropic-version). */
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** Prezzi in micro-USD per 1M token (es. $2.50/MTok = 2_500_000). Interi: niente float sui soldi. */
data class ModelPrice(
    val inputMicrosPerMTok: Long,
    val outputMicrosPerMTok: Long,
    val cachedInputMicrosPerMTok: Long? = null,
)

/** Solo metadati pubblici: questo oggetto finisce nell'APK, un segreto qui è un bug di release. */
data class ProviderSpec(
    val id: ProviderId,
    val displayName: String,
    val defaultBaseUrl: String?, // null per CUSTOM_OPENAI_COMPAT (endpoint dell'utente)
    val authStyle: AuthStyle,
    val defaultModels: List<String>,
    val quirks: ProviderQuirks = ProviderQuirks(),
    val prices: Map<String, ModelPrice> = emptyMap(),
    /** Hint morbido per la UI (es. "sk-"): MAI usato come validazione bloccante. */
    val apiKeyPrefixHint: String? = null,
)

/**
 * Catalogo statico dei provider LLM (metadati, quirks, prezzi). Nessun segreto: le chiavi API
 * sono per-installazione, inserite dall'utente, cifrate nello store — mai qui, mai nell'APK.
 *
 * Prezzi: $/1M token in listino pubblico 2026, in micro-USD, stampigliati da [PRICING_VERSION].
 * I findings del design doc (§2, §5) citano solo i contratti API; i listini concreti sono i
 * prezzi pubblici correnti dei provider, da rivedere a ogni bump di PRICING_VERSION.
 */
object ProviderCatalog {
    const val PRICING_VERSION: String = "2026-07"

    val specs: Map<ProviderId, ProviderSpec> = listOf(
        ProviderSpec(
            id = ProviderId.HERMES,
            displayName = "Hermes (self-hosted)",
            defaultBaseUrl = AndroidBridgeConfigurationStore.DEFAULT_BASE_URL,
            authStyle = AuthStyle.BEARER,
            defaultModels = emptyList(),
            // Costo "n/d" fino a S15 (il wire Hermes non trasporta ancora usage).
            prices = emptyMap(),
        ),
        ProviderSpec(
            id = ProviderId.OPENAI,
            displayName = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            authStyle = AuthStyle.BEARER,
            defaultModels = listOf("gpt-5.5", "gpt-5-mini"),
            quirks = ProviderQuirks(outputCapParam = OutputCapParam.MAX_COMPLETION_TOKENS),
            prices = mapOf(
                // gpt-5.5: $1.25 in / $10 out per 1M token.
                "gpt-5.5" to ModelPrice(1_250_000, 10_000_000, cachedInputMicrosPerMTok = 125_000),
                // gpt-5-mini: $0.25 in / $2.00 out per 1M token.
                "gpt-5-mini" to ModelPrice(250_000, 2_000_000, cachedInputMicrosPerMTok = 25_000),
            ),
            apiKeyPrefixHint = "sk-",
        ),
        ProviderSpec(
            id = ProviderId.ANTHROPIC,
            displayName = "Anthropic",
            defaultBaseUrl = "https://api.anthropic.com",
            authStyle = AuthStyle.X_API_KEY,
            defaultModels = listOf("claude-opus-4-8", "claude-sonnet-4-5"),
            quirks = ProviderQuirks(extraHeaders = mapOf("anthropic-version" to "2023-06-01")),
            prices = mapOf(
                // claude-opus: $15 in / $75 out per 1M token.
                "claude-opus-4-8" to ModelPrice(15_000_000, 75_000_000, cachedInputMicrosPerMTok = 1_500_000),
                // claude-sonnet: $3 in / $15 out per 1M token.
                "claude-sonnet-4-5" to ModelPrice(3_000_000, 15_000_000, cachedInputMicrosPerMTok = 300_000),
            ),
            apiKeyPrefixHint = "sk-ant-",
        ),
        ProviderSpec(
            id = ProviderId.GEMINI,
            displayName = "Google Gemini",
            // Shim OpenAI ufficiale, già normalizzato (senza slash finale).
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            authStyle = AuthStyle.BEARER,
            defaultModels = listOf("gemini-2.5-pro", "gemini-2.5-flash"),
            quirks = ProviderQuirks(extraBodyPassthrough = true),
            prices = mapOf(
                // gemini-2.5-pro: $1.25 in / $10 out per 1M token (fascia <=200k).
                "gemini-2.5-pro" to ModelPrice(1_250_000, 10_000_000),
                // gemini-2.5-flash: $0.30 in / $2.50 out per 1M token.
                "gemini-2.5-flash" to ModelPrice(300_000, 2_500_000),
            ),
            apiKeyPrefixHint = "AIza",
        ),
        ProviderSpec(
            id = ProviderId.OPENROUTER,
            displayName = "OpenRouter",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            authStyle = AuthStyle.BEARER,
            defaultModels = listOf("openai/gpt-5.5", "anthropic/claude-sonnet-4-5"),
            prices = mapOf(
                // OpenRouter fa passthrough del listino provider; timbrato con PRICING_VERSION.
                "openai/gpt-5.5" to ModelPrice(1_250_000, 10_000_000),
                "anthropic/claude-sonnet-4-5" to ModelPrice(3_000_000, 15_000_000),
            ),
            apiKeyPrefixHint = "sk-or-",
        ),
        ProviderSpec(
            id = ProviderId.CUSTOM_OPENAI_COMPAT,
            displayName = "Custom (OpenAI-compat)",
            defaultBaseUrl = null,
            authStyle = AuthStyle.BEARER,
            defaultModels = emptyList(),
            // Endpoint e listino sconosciuti a priori: costo "n/d".
            prices = emptyMap(),
        ),
    ).associateBy { it.id }

    fun spec(id: ProviderId): ProviderSpec = specs.getValue(id)
}
