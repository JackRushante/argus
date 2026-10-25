package dev.argus.engine.brain
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding

data class CompileResult(
    val reply: String,
    val draft: AutomationDraft?,
    val metaError: String?,
    /** S15: usage reale del turno quando il transport lo riporta (bridge Hermes o provider diretto). */
    val usage: TurnUsage? = null,
    /**
     * Dettaglio tecnico sicuro del fallimento (percorso e tipo dell'errore del parser, mai frammenti
     * di input), solo per la UI: non entra mai in audit o prompt.
     */
    val detail: String? = null,
)

/** Codici compile condivisi tra transport e decorator: evitano retry basati su stringhe duplicate. */
object CompileMetaError {
    const val DRAFT_INVALID = "draft_invalid"
    const val META_PARSE_ERROR = "meta_parse_error"
    const val META_JSON_MISSING = "meta_json_missing"
    const val DRAFT_MISSING = "draft_missing"
    const val OUTPUT_TRUNCATED = "output_truncated"
    const val CONTEXT_TRUNCATED = "context_truncated"
    const val CLARIFICATION_REQUIRED = "clarification_required"
    const val UNSUPPORTED_CAPABILITY = "unsupported_capability"
    const val LIMIT_EXCEEDED = "limit_exceeded"

    /** Esiti "il modello ha risposto bene ma non c'era una regola da creare": non sono errori. */
    val BENIGN_NO_DRAFT: Set<String> = setOf(CLARIFICATION_REQUIRED, UNSUPPORTED_CAPABILITY, LIMIT_EXCEEDED)

    /** Errori di formato del modello: il retry-once del decorator ha senso (B5). */
    val FORMAT_ERRORS: Set<String> = setOf(DRAFT_INVALID, META_PARSE_ERROR, META_JSON_MISSING)

    /** Codici prodotti dall'app (parser, transport, budget, flusso locale), mai dal modello. */
    private val APP_CODES: Set<String> = FORMAT_ERRORS + setOf(
        DRAFT_MISSING,
        OUTPUT_TRUNCATED,
        CONTEXT_TRUNCATED,
        "empty_response",
        "compile_without_draft",
        "budget_exceeded",
        "budget_unpriced_model",
    )

    /**
     * true se [code] è un `error_code` scelto dal MODELLO (es. un rifiuto motivato come
     * `contact_not_whitelisted`): il modello ha risposto, solo che non c'era una regola da creare.
     * Va mostrato come risposta normale, non come errore. Errori di trasporto (`bridge_*`) e codici
     * dell'app restano errori.
     */
    fun isModelAnswer(code: String): Boolean =
        code in BENIGN_NO_DRAFT || (code !in APP_CODES && !code.startsWith("bridge_"))
}

data class ActResult(
    val text: String?,
    val metaError: String?,
    val usage: TurnUsage? = null,
) {
    init {
        require((text != null) xor (metaError != null)) {
            "ActResult deve contenere esattamente uno tra text e metaError"
        }
        require(text == null || text.isNotBlank()) { "La risposta act non può essere vuota" }
        require(metaError == null || metaError.matches(Regex("^[a-z][a-z0-9_]{0,63}$"))) {
            "metaError act non valido"
        }
    }
}

/** Porta one-shot verso il modello (spec §7). Lo streaming chat resta P3. */
interface Brain {
    suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult

    suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult

    /** Profilo additivo: le implementazioni legacy falliscono chiuse senza rompere i fake v1. */
    suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        ActResult(text = null, metaError = "act_v2_unsupported")

    /**
     * Profilo RISOLTO P4-D2 (contratto anti-injection). Il dato runtime non fidato ([runtimeData],
     * TAINTED) viaggia in un messaggio DATA separato e delimitato, MAI interpolato nel prompt di
     * sistema: il [goal] approvato porta solo i marker opachi `{{ARGUS_RUNTIME_DATA_n}}`. Profilo
     * additivo: le implementazioni legacy falliscono chiuse senza rompere i fake esistenti.
     */
    suspend fun actResolved(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
        runtimeData: List<RuntimeDataBinding>,
    ): ActResult = ActResult(text = null, metaError = "act_resolved_unsupported")
}
