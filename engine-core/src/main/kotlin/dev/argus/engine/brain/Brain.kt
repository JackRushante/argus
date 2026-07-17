package dev.argus.engine.brain
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext

data class CompileResult(
    val reply: String,
    val draft: AutomationDraft?,
    val metaError: String?,
    /** S15: usage reale del turno quando il transport lo riporta (bridge Hermes o provider diretto). */
    val usage: TurnUsage? = null,
)

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
}
