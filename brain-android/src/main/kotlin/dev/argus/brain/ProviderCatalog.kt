package dev.argus.brain

/** Stile di autenticazione HTTP del provider. Il transport monta l'header dallo store cifrato. */
enum class AuthStyle { BEARER, X_API_KEY }

/** Come il transport nomina il tetto output: OpenAI gpt-5.x rigetta max_tokens. */
enum class OutputCapParam { MAX_TOKENS, MAX_COMPLETION_TOKENS }

/** Controllo reasoning per-request. Il wire coincide con OpenAI/llama.cpp; DEFAULT omette il campo. */
enum class ReasoningEffort(val wireValue: String?) {
    DEFAULT(null), NONE("none"), LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        fun fromWireValue(value: String?): ReasoningEffort =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT
    }
}

/**
 * Meccanismo di attivazione del web search SERVER-SIDE per provider (#52 F3). Il web dei provider è
 * server-side: si aggiunge un tool/flag alla richiesta, il provider fa il loop interno e ritorna il
 * testo finale in UNA chiamata — l'architettura single-turn dei transport resta invariata.
 * - [NONE]: web non attivabile da questo path (default; degradazione graziosa se richiesto).
 * - [ANTHROPIC_TOOL]: server tool `{"type":"web_search_20250305","name":"web_search"}` nei `tools`.
 * - [OPENROUTER_WEB_PLUGIN]: plugin `"plugins":[{"id":"web"}]` nel body Chat Completions, `model` intatto.
 *   Il suffisso `:online` è equivalente ma rompe i preset (`@preset/...:online` dà 404). Entrambi sono
 *   deprecati da OpenRouter a favore del tool lato server `openrouter:web_search` (lavoro futuro).
 * - [OPENAI_RESPONSES]: tool `{"type":"web_search"}` sulla Responses API (`POST {base}/responses`,
 *   endpoint diverso da /chat/completions). Il modello gpt-5.x fa il loop web internamente e ritorna il
 *   testo negli item `output→message→output_text`. Validato live 2026-07-17.
 * - [GEMINI_NATIVE]: tool `{"google_search":{}}` sull'API nativa `generateContent`
 *   (`POST {geminiHost}/v1beta/models/{model}:generateContent`, header `x-goog-api-key`), NON sullo shim
 *   OpenAI-compat (che rifiuta il grounding con 400). Testo = `candidates[0].content.parts[].text`.
 *   Validato live 2026-07-17.
 */
enum class WebSearchMechanism { NONE, ANTHROPIC_TOOL, OPENROUTER_WEB_PLUGIN, OPENAI_RESPONSES, GEMINI_NATIVE }

/** Flag di comportamento per-provider: MAI `if (provider == ...)` dentro un transport. */
data class ProviderQuirks(
    val forceToolChoiceAuto: Boolean = false,
    val outputCapParam: OutputCapParam = OutputCapParam.MAX_TOKENS,
    val extraBodyPassthrough: Boolean = false,
    /** Header addizionali NON di autenticazione (es. anthropic-version). */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Come il transport attiva il web search server-side (o [WebSearchMechanism.NONE]). */
    val webSearch: WebSearchMechanism = WebSearchMechanism.NONE,
    /**
     * Temperatura inviata SOLO nelle richieste di compile (null = campo omesso, default del provider):
     * una temperatura bassa rende costante la compilazione della stessa richiesta (stessa regola, non una
     * volta una regola e una volta una domanda di chiarimento). Act e health non la inviano mai.
     *
     * Solo OpenRouter e Custom (OpenAI-compatibile). NON per gli altri, di proposito:
     * - OpenAI: con i modelli di ragionamento una temperatura diversa da 1 dà errore 400;
     * - Anthropic: con il thinking attivo la temperatura non è accettata;
     * - Gemini 3: abbassarla degrada le risposte.
     */
    val compileTemperature: Double? = null,
)

/** Prezzi in micro-USD per 1M token (es. $2.50/MTok = 2_500_000). Interi: niente float sui soldi. */
data class ModelPrice(
    val inputMicrosPerMTok: Long,
    val outputMicrosPerMTok: Long,
    val cachedInputMicrosPerMTok: Long? = null,
    /**
     * Micro-USD per ricerca web server-side. null = prezzo non in catalogo: le ricerche vengono
     * contate (`TurnUsage.webSearches`) ma NON addebitate (niente prezzi inventati).
     */
    val webSearchMicrosPerCall: Long? = null,
)

/** Solo metadati pubblici: questo oggetto finisce nell'APK, un segreto qui è un bug di release. */
data class ProviderSpec(
    val id: ProviderId,
    val displayName: String,
    val defaultBaseUrl: String?, // null per endpoint self-hosted/custom configurati dall'utente
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
    /** false solo per endpoint Custom che accettano richieste anonime (es. llama-server locale). */
    val apiKeyRequired: Boolean = true,
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
    const val PRICING_VERSION: String = "2026-10"

    /** Temperatura del compile per i provider OpenAI-compatibili «aperti» (vedi [ProviderQuirks.compileTemperature]). */
    private const val COMPILE_TEMPERATURE = 0.2

    val specs: Map<ProviderId, ProviderSpec> = listOf(
        ProviderSpec(
            id = ProviderId.HERMES,
            displayName = "Hermes (self-hosted)",
            defaultBaseUrl = null,
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
            // Il primo è il modello che l'app USA quando il campo è vuoto: il modello base economico
            // misurato dal banco di prova. I nomi cambiano nel tempo e stanno solo qui, mai nei testi.
            defaultModels = listOf("gpt-6-luna"),
            // Web via Responses API: tool `web_search` su `POST {base}/responses` (NON /chat/completions,
            // che vorrebbe un modello `-search-preview`). gpt-5.x fa il loop web internamente e ritorna il
            // testo in `output→message→output_text`. Validato live 2026-07-17.
            quirks = ProviderQuirks(
                outputCapParam = OutputCapParam.MAX_COMPLETION_TOKENS,
                webSearch = WebSearchMechanism.OPENAI_RESPONSES,
            ),
            costTracked = true,
            // Le voci dei modelli non più suggeriti restano: chi li ha già configurati conserva la stima del costo.
            prices = mapOf(
                // gpt-6-luna: $0.10 in / $0.01 in cache / $0.50 out per 1M token (pagina ufficiale OpenAI).
                "gpt-6-luna" to ModelPrice(100_000, 500_000, cachedInputMicrosPerMTok = 10_000),
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
            // Primo = modello usato a campo vuoto (modello base economico del banco di prova).
            defaultModels = listOf("claude-haiku-5-5"),
            quirks = ProviderQuirks(
                extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
                // Server tool web_search_20250305: loop di ricerca interno lato Anthropic, single-turn.
                webSearch = WebSearchMechanism.ANTHROPIC_TOOL,
            ),
            costTracked = true,
            // Le voci dei modelli non più suggeriti restano: chi li ha già configurati conserva la stima del costo.
            prices = mapOf(
                // claude-haiku-5-5: $0.10 in / $0.50 out per 1M token fino a 100K token di prompt (Anthropic);
                // la lettura dalla cache costa 0,1x l'ingresso.
                "claude-haiku-5-5" to ModelPrice(100_000, 500_000, cachedInputMicrosPerMTok = 10_000),
                // claude-opus: $5 in / $25 out per 1M token (listino attuale; prima $15/$75).
                "claude-opus-4-8" to ModelPrice(5_000_000, 25_000_000, cachedInputMicrosPerMTok = 500_000),
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
            // Primo = modello usato a campo vuoto. gemini-2.5-pro non si suggerisce più: Google lo ha messo
            // tra i modelli legacy riservati agli utenti esistenti.
            defaultModels = listOf("gemini-3.8-flash"),
            // Web via API nativa: tool `{"google_search":{}}` su `POST {geminiHost}/v1beta/models/
            // {model}:generateContent` (header `x-goog-api-key`), NON sullo shim OpenAI-compat
            // /chat/completions (che rifiuta il grounding con 400 — smoke 2026-07-17). Il transport deriva
            // l'host nativo dal baseUrl compat togliendo `/openai`. Validato live 2026-07-17.
            quirks = ProviderQuirks(
                extraBodyPassthrough = true,
                webSearch = WebSearchMechanism.GEMINI_NATIVE,
            ),
            costTracked = true,
            // Le voci dei modelli non più suggeriti restano: chi li ha già configurati conserva la stima del costo.
            prices = mapOf(
                // gemini-3.8-flash: $0.75 in / $3.75 out / cache $0.075 per 1M token. Listino Google valido
                // fino al 2026-12-31: dal 2027-01-01 raddoppia ($1.50 / $7.50 / cache $0.15) e va aggiornato.
                "gemini-3.8-flash" to ModelPrice(750_000, 3_750_000, cachedInputMicrosPerMTok = 75_000),
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
            // Alias `~<fornitore>/<famiglia>-latest`: OpenRouter li sposta da solo sull'ultima versione della
            // famiglia, così il suggerimento non invecchia a ogni uscita (verificati il 2026-10-26: puntano a
            // DeepSeek V4.1 Flash, Gemini 3.8 Flash, Kimi K3 e Claude Haiku 5.5). Il primo è il modello usato
            // a campo vuoto. Lista provvisoria: può aggiungersi `~openai/gpt-luna-latest` dopo un'altra misura.
            defaultModels = listOf(
                "~deepseek/deepseek-flash-latest",
                "~google/gemini-flash-latest",
                "~moonshotai/kimi-latest",
                "~anthropic/claude-haiku-latest",
            ),
            // Web attivato dal plugin `{"id":"web"}` (loop di ricerca interno lato OpenRouter); non dal
            // suffisso `:online`, che rompe i preset `@preset/...`.
            quirks = ProviderQuirks(
                webSearch = WebSearchMechanism.OPENROUTER_WEB_PLUGIN,
                compileTemperature = COMPILE_TEMPERATURE,
            ),
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
            quirks = ProviderQuirks(compileTemperature = COMPILE_TEMPERATURE),
            prices = emptyMap(),
            apiKeyRequired = false,
        ),
    ).associateBy { it.id }

    fun spec(id: ProviderId): ProviderSpec = specs.getValue(id)
}
