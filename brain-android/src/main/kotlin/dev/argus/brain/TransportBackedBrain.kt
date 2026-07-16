package dev.argus.brain

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext

/**
 * [Brain] su un [AgentTransport] qualunque (ex `HermesBrain`, mappatura errori invariata):
 * `compile` e `act` sono one-shot; lo streaming chat resta P3.
 *
 * Gestione errori: gli errori di trasporto ([TransportException]) sono catturati e mappati a un
 * risultato con `metaError` valorizzato — il ViewModel riceve sempre un risultato gestibile e l'app
 * non crasha mai per un bridge irraggiungibile. Si cattura SOLO [TransportException] (una
 * [java.io.IOException]): così `CancellationException` si propaga e la cancellazione cooperativa
 * resta intatta. Il prefisso "bridge_" dei metaError è API stabile verso UI e journal: NON cambiarlo
 * per i nuovi provider.
 */
class TransportBackedBrain(
    private val transport: AgentTransport,
) : Brain {
    override suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult =
        try {
            transport.compile(message = nl, manifest = manifest, state = state)
        } catch (e: TransportException) {
            CompileResult(reply = UNREACHABLE_REPLY, draft = null, metaError = e.toMetaError())
        }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = try {
        transport.act(context, goal, contextSources, allowedTools)
    } catch (e: TransportException) {
        ActResult(text = null, metaError = e.toMetaError())
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult = try {
        transport.actV2(context, action)
    } catch (e: TransportException) {
        ActResult(text = null, metaError = e.toMetaError())
    }

    private fun TransportException.toMetaError(): String = "bridge_${kind.name.lowercase()}"

    companion object {
        const val UNREACHABLE_REPLY = "Non riesco a contattare l'assistente adesso. Riprova tra poco."
    }
}
