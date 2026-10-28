package dev.argus.brain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
 * - [OPENROUTER_SERVER_TOOL]: server tool `{"type":"openrouter:web_search"}` nei `tools` del body Chat
 *   Completions (accanto al reply tool), `model` intatto, con `max_tool_calls` come tetto di ricerche.
 *   Dalla 0.5.1 sostituisce il plugin `{"id":"web"}` e il suffisso `:online`, deprecati da OpenRouter
 *   (il suffisso rompeva anche i preset: `@preset/...:online` dava 404).
 * - [OPENAI_RESPONSES]: tool `{"type":"web_search"}` sulla Responses API (`POST {base}/responses`,
 *   endpoint diverso da /chat/completions). Il modello gpt-5.x fa il loop web internamente e ritorna il
 *   testo negli item `output→message→output_text`. Validato live 2026-07-17.
 * - [GEMINI_NATIVE]: tool `{"google_search":{}}` sull'API nativa `generateContent`
 *   (`POST {geminiHost}/v1beta/models/{model}:generateContent`, header `x-goog-api-key`), NON sullo shim
 *   OpenAI-compat (che rifiuta il grounding con 400). Testo = `candidates[0].content.parts[].text`.
 *   Validato live 2026-07-17.
 */
enum class WebSearchMechanism { NONE, ANTHROPIC_TOOL, OPENROUTER_SERVER_TOOL, OPENAI_RESPONSES, GEMINI_NATIVE }

/**
 * Output strutturato nativo del COMPILE (0.5.1): al posto di «una frase + riga `@@META@@ {json}`» il modello
 * risponde con un oggetto JSON `{reply, draft, error_code}`. Il protocollo `@@META@@` resta la base e il
 * ripiego: se il provider rifiuta il parametro, o il modello lo ignora, il transport torna ad esso da solo
 * (vedi `StructuredOutput`). Un flag per provider: spegnerlo qui basta a tornare al solo protocollo base.
 * - [NONE]: mai (default; bridge Hermes).
 * - [RESPONSE_FORMAT]: Chat Completions OpenAI-compatibile. Prova `response_format` `json_schema`, se il
 *   server lo rifiuta `json_object`, se rifiuta anche quello il protocollo base.
 * - [OUTPUT_CONFIG]: Messages API di Anthropic, `output_config.format` di tipo `json_schema` (GA, senza header
 *   beta). Lo schema è chiuso e senza ricorsione, quindi `draft` viaggia come stringa che contiene l'oggetto.
 */
enum class StructuredOutputMode { NONE, RESPONSE_FORMAT, OUTPUT_CONFIG }

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
    /** Output strutturato nativo del compile (solo compile: act e health non lo usano). */
    val structuredOutput: StructuredOutputMode = StructuredOutputMode.NONE,
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
    /**
     * Variazioni di listino già annunciate, in ordine di data crescente (stretta): da [PriceChange.effectiveFrom]
     * vale [PriceChange.price]. Vuoto = il prezzo non cambia nel tempo. Il prezzo giusto per un istante si
     * legge con [effectiveAt]; i campi qui sopra sono il prezzo valido prima della prima variazione.
     */
    val changes: List<PriceChange> = emptyList(),
) {
    /**
     * Prezzo valido all'istante [atMillis] (epoch ms): quello dell'ultima variazione già iniziata, oppure
     * questo stesso prezzo se nessuna è iniziata. Le date di inizio si leggono in UTC: una variazione
     * «dal 2027-01-01» vale dalle 00:00 UTC. Il prezzo restituito non porta a sua volta variazioni da leggere.
     */
    fun effectiveAt(atMillis: Long): ModelPrice {
        if (changes.isEmpty()) return this
        val day = Instant.ofEpochMilli(atMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return changes.lastOrNull { !it.effectiveFrom.isAfter(day) }?.price ?: copy(changes = emptyList())
    }
}

/**
 * Un prezzo che subentra dal giorno [effectiveFrom] (UTC, incluso). [price] è un prezzo piatto: le sue
 * [ModelPrice.changes] devono essere vuote (lo impone il test del catalogo).
 */
data class PriceChange(val effectiveFrom: LocalDate, val price: ModelPrice)

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
    /**
     * true se l'utente può scegliere lo sforzo di ragionamento ([ReasoningEffort]): Custom (llama.cpp & co.)
     * e OpenRouter, che accetta `reasoning_effort` e lo normalizza tra i fornitori. Per gli altri la scelta
     * non si offre: `DEFAULT` omette il campo e il provider usa il suo comportamento.
     */
    val reasoningEffortEditable: Boolean = false,
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
                structuredOutput = StructuredOutputMode.RESPONSE_FORMAT,
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
                // Compile con `output_config.format` json_schema: il ragionamento resta libero (la grammatica
                // vale solo per l'output finale). Un tool forzato lo salterebbe, e i modelli più recenti lo rifiutano.
                structuredOutput = StructuredOutputMode.OUTPUT_CONFIG,
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
                structuredOutput = StructuredOutputMode.RESPONSE_FORMAT,
            ),
            costTracked = true,
            // Le voci dei modelli non più suggeriti restano: chi li ha già configurati conserva la stima del costo.
            prices = mapOf(
                // gemini-3.8-flash: $0.75 in / $3.75 out / cache $0.075 per 1M token fino al 2026-12-31; dal
                // 2027-01-01 il listino Google raddoppia: $1.50 / $7.50 / cache $0.15 (pagina prezzi ufficiale,
                // verificata il 2026-10-26). Il costo di un evento usa il prezzo valido alla sua data.
                "gemini-3.8-flash" to ModelPrice(
                    inputMicrosPerMTok = 750_000,
                    outputMicrosPerMTok = 3_750_000,
                    cachedInputMicrosPerMTok = 75_000,
                    changes = listOf(
                        PriceChange(
                            effectiveFrom = LocalDate.of(2027, 1, 1),
                            price = ModelPrice(1_500_000, 7_500_000, cachedInputMicrosPerMTok = 150_000),
                        ),
                    ),
                ),
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
            // Web attivato dal server tool `openrouter:web_search` (loop di ricerca interno lato OpenRouter);
            // non dal plugin `web` né dal suffisso `:online`, deprecati (e il suffisso rompe i preset
            // `@preset/...`). Output strutturato: si prova e, se il provider rifiuta il parametro o il
            // modello non lo supporta, si torna al protocollo base.
            quirks = ProviderQuirks(
                webSearch = WebSearchMechanism.OPENROUTER_SERVER_TOOL,
                compileTemperature = COMPILE_TEMPERATURE,
                structuredOutput = StructuredOutputMode.RESPONSE_FORMAT,
            ),
            // TOKEN-ONLY: il listino statico copriva 2 modelli su centinaia e non leggiamo il
            // costo reale dal wire — niente stime in dollari, si contano solo i token.
            prices = emptyMap(),
            apiKeyPrefixHint = "sk-or-",
            // `none` spegne il ragionamento su DeepSeek, Luna e Kimi (verificato il 2026-10-26); Gemini, GLM e
            // MiniMax Flash lo rifiutano e il transport ritenta una volta a `low`.
            reasoningEffortEditable = true,
        ),
        ProviderSpec(
            id = ProviderId.CUSTOM_OPENAI_COMPAT,
            displayName = "Custom (OpenAI-compat)",
            defaultBaseUrl = null,
            authStyle = AuthStyle.BEARER,
            defaultModels = emptyList(),
            // Endpoint e listino sconosciuti a priori: costo "n/d".
            quirks = ProviderQuirks(
                compileTemperature = COMPILE_TEMPERATURE,
                // Ollama, LM Studio e llama.cpp accettano json_schema; chi non lo supporta ripiega da solo.
                structuredOutput = StructuredOutputMode.RESPONSE_FORMAT,
            ),
            prices = emptyMap(),
            apiKeyRequired = false,
            reasoningEffortEditable = true,
        ),
    ).associateBy { it.id }

    fun spec(id: ProviderId): ProviderSpec = specs.getValue(id)
}
