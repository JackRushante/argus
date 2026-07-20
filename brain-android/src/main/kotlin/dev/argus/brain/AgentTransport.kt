package dev.argus.brain

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding

/** Vista neutrale della salute di un transport; [BridgeHealth] è l'implementazione Hermes. */
interface TransportHealth {
    val model: String
}

/**
 * Contratto comune dei transport LLM. Stessa superficie one-shot di [dev.argus.engine.brain.Brain];
 * gli errori escono SOLO come [TransportException]; nessun segreto in messaggi/toString.
 * Le firme ricalcano esattamente [CliBridgeTransport]: l'adesione è dichiarativa, non una riscrittura.
 */
interface AgentTransport {
    val providerId: ProviderId

    suspend fun compile(message: String, manifest: CapabilityManifest, state: DeviceState): CompileResult

    suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult

    suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult

    /**
     * Profilo RISOLTO P4-D2 (anti-injection): il dato runtime TAINTED [runtimeData] viaggia in un
     * messaggio DATA separato e delimitato, MAI nel system; il [goal] porta solo i marker opachi
     * `{{ARGUS_RUNTIME_DATA_n}}`. Default fail-closed: i transport che non lo implementano restano
     * invariati (nessun valore runtime finisce mai in un prompt di sistema per errore).
     */
    suspend fun actResolved(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
        runtimeData: List<RuntimeDataBinding>,
    ): ActResult = ActResult(text = null, metaError = "act_resolved_unsupported")

    suspend fun health(): TransportHealth
}
