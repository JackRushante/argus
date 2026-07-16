package dev.argus.brain

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext

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

    suspend fun health(): TransportHealth
}
