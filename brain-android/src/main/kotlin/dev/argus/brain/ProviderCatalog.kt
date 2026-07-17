package dev.argus.brain

/** Stile di autenticazione HTTP del provider. Il transport monta l'header dallo store cifrato. */
enum class AuthStyle { BEARER, X_API_KEY }

/** Come il transport nomina il tetto output: OpenAI gpt-5.x rigetta max_tokens. */
enum class OutputCapParam { MAX_TOKENS, MAX_COMPLETION_TOKENS }

/**
 * Meccanismo di attivazione del web search SERVER-SIDE per provider (#52 F3). Il web dei provider è
 * server-side: si aggiunge un tool/flag alla richiesta, il provider fa il loop interno e ritorna il
 * testo finale in UNA chiamata — l'architettura single-turn dei transport resta invariata.
 * - [NONE]: web non attivabile da questo path (default; degradazione graziosa se richiesto).
 * - [ANTHROPIC_TOOL]: server tool `{"type":"web_search_20250305","name":"web_search"}` nei `tools`.
 * - [OPENROUTER_ONLINE]: slug modello con suffisso `:online`.
 * - [OPENAI_SEARCH]: `web_search_options` + modello `-search-preview` in Chat Completions (o Responses
 *   API). NON usato: i modelli configurati sono gpt-5.x e la Responses API è un endpoint diverso da
 *   quello OpenAI-compat di questo transport — vedi ProviderCatalog OPENAI (resta NONE, onesto).
 * - [GEMINI_GROUNDING]: grounding `google_search` via passthrough `extra_body.google.tools` sullo shim
 *   OpenAI-compat di Gemini (il `tools` OpenAI top-level verrebbe rifiutato con "Unknown name").
 */
enum class WebSearchMechanism { NONE, ANTHROPIC_TOOL, OPENROUTER_ONLINE, OPENAI_SEARCH, GEMINI_GROUNDING }

/** Flag di comportamento per-provider: MAI `if (provider == ...)` dentro un transport. */
data class ProviderQuirks(
    val forceToolChoiceAuto: Boolean = false,
    val outputCapParam: OutputCapParam = OutputCapParam.MAX_TOKENS,
    val extraBodyPassthrough: Boolean = false,
    /** Header addizionali NON di autenticazione (es. anthropic-version). */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Come il transport attiva il web search server-side (o [WebSearchMechanism.NONE]). */
    val webSearch: WebSearchMechanism = WebSearchMechanism.NONE,
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
    /**
     * true SOLO per i provider a listino pubblico noto (OpenAI/Anthropic/Gemini): per loro la stima
     * costo in dollari ha senso. I provider TOKEN-ONLY (Hermes/OpenRouter/Custom) mostrano e limitano
     * solo token: nessuna stima in dollari da listino statico.
     */
    val costTracked: Boolean = false,
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
            // TOKEN-ONLY: self-hosted, nessun dollaro da mostrare (il wire non trasporta usage fino a S15).
            prices = emptyMap(),
        ),
        ProviderSpec(
            id = ProviderId.OPENAI,
            displayName = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            authStyle = AuthStyle.BEARER,
            defaultModels = listOf("gpt-5.5", "gpt-5-mini"),
            // Web NONE: `web_search_options` in Chat Completions richiede un modello `-search-preview`
            // (non i gpt-5.x qui) e il web dei gpt-5 passa dalla Responses API, endpoint diverso da
            // /chat/completions usato da OpenAICompatTransport. Onesto: web non disponibile su questo path.
            quirks = ProviderQuirks(
                outputCapParam = OutputCapParam.MAX_COMPLETION_TOKENS,
                webSearch = WebSearchMechanism.NONE,
            ),
            costTracked = true,
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
            quirks = ProviderQuirks(
                extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
                // Server tool web_search_20250305: loop di ricerca interno lato Anthropic, single-turn.
                webSearch = WebSearchMechanism.ANTHROPIC_TOOL,
            ),
            costTracked = true,
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
            // Web = NONE (onesto): il grounding via shim OpenAI-compat non e' raggiungibile. Smoke live
            // 2026-07-17: sia `extra_body.google.tools=[{google_search:{}}]` (formato documentato) sia il
            // `tools` OpenAI top-level danno 400 ("Unknown name 'tools' at 'extra_body.google'"), anche su
            // gemini-3-flash-preview. Mismatch doc<->API lato Google. Il web di Gemini passa dall'API nativa
            // (non da /chat/completions usata qui). Riattivare quando il compat layer accettera' il grounding.
            quirks = ProviderQuirks(
                extraBodyPassthrough = true,
                webSearch = WebSearchMechanism.NONE,
            ),
            costTracked = true,
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
            // Web attivato dallo slug modello `<model>:online` (loop di ricerca interno lato OpenRouter).
            quirks = ProviderQuirks(webSearch = WebSearchMechanism.OPENROUTER_ONLINE),
            // TOKEN-ONLY: il listino statico copriva 2 modelli su centinaia e non leggiamo il
            // costo reale dal wire — niente stime in dollari, si contano solo i token.
            prices = emptyMap(),
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
