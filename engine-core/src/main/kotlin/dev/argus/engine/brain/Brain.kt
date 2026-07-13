package dev.argus.engine.brain
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.runtime.DeviceState

data class CompileResult(val reply: String, val draft: AutomationDraft?, val metaError: String?)

/** Porta verso il modello (spec §7). P0: solo compile one-shot.
 *  act() (InvokeLlm, P1) e chat() streaming (P3) verranno aggiunti nelle fasi rispettive. */
interface Brain {
    suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult
}
