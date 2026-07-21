package dev.argus.brain

import dev.argus.engine.model.Action
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.VarScope

/**
 * Costruisce, tramite il VERO [TaintAwareInterpolator] (l'unico produttore dei binding — il
 * costruttore di [RuntimeDataBinding] è internal a engine-core), un goal RISOLTO con il solo marker
 * opaco `{{ARGUS_RUNTIME_DATA_1}}` e la lista di [RuntimeDataBinding] che porta il valore RAW
 * [sentinel]. È esattamente il contratto che la lane passerà a `actResolved` in slice 2, così i test
 * di trasporto esercitano la vera catena marker⇄binding senza mai fabbricare un binding a mano.
 */
object RuntimeDataTestFixture {
    const val SENTINEL = "INJECT_SENTINEL_XYZ"

    data class Resolved(val goal: String, val runtimeData: List<RuntimeDataBinding>)

    fun resolved(
        sentinel: String = SENTINEL,
        variableName: String = "secret",
    ): Resolved {
        val action = Action.InvokeLlm(
            goal = "Rispondi usando il dato \${$variableName}",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 60_000,
        )
        val scope = VarScope(
            mapOf(
                variableName to VarValue(
                    text = sentinel,
                    type = VarType.TEXT,
                    integrity = IntegrityLabel.TAINTED,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                    provenance = setOf(ValueProvenance.NOTIFICATION),
                ),
            ),
        )
        val resolution = TaintAwareInterpolator().resolve(action, scope)
        check(resolution is ActionResolution.Resolved) { "atteso Resolved, ottenuto $resolution" }
        val invoke = resolution.value.action as Action.InvokeLlm
        return Resolved(invoke.goal, resolution.value.runtimeData)
    }
}
