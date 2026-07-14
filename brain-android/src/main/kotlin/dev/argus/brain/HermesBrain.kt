package dev.argus.brain

import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext

/**
 * [Brain] Android su bridge Hermes (spec §7): `compile` e `act` sono one-shot; lo streaming chat è P3.
 *
 * Gestione errori: gli errori di trasporto ([BridgeException]) sono catturati e mappati a un [CompileResult]
 * con `metaError` valorizzato — il ViewModel riceve sempre un risultato gestibile e l'app non crasha mai per
 * un bridge irraggiungibile. Si cattura SOLO [BridgeException] (una [java.io.IOException]): così
 * `CancellationException` si propaga e la cancellazione cooperativa resta intatta.
 */
class HermesBrain(
    private val transport: CliBridgeTransport,
) : Brain {
    override suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult =
        try {
            transport.compile(message = nl, manifest = manifest, state = state)
        } catch (e: BridgeException) {
            CompileResult(
                reply = "Non riesco a contattare l'assistente adesso. Riprova tra poco.",
                draft = null,
                metaError = "bridge_${e.kind.name.lowercase()}",
            )
        }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = try {
        transport.act(context, goal, contextSources, allowedTools)
    } catch (e: BridgeException) {
        ActResult(
            text = null,
            metaError = "bridge_${e.kind.name.lowercase()}",
        )
    }
}
