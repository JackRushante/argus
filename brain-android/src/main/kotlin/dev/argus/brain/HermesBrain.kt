package dev.argus.brain

import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.runtime.DeviceState

/**
 * [Brain] Android su bridge Hermes (spec §7). In P0-B il contratto `Brain` ha SOLO `compile` one-shot:
 * `act()` (InvokeLlm, P1) e `chat()` streaming (P3) arriveranno nelle fasi rispettive e qui non esistono.
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
            // Il contratto del bridge (§2 rev 3) porta {message, manifest, history?}. Lo stato corrente del
            // dispositivo è già codificato nelle capability del manifest (chiavi/tool disponibili); `state`
            // resta a disposizione per estensioni future del protocollo.
            transport.compile(message = nl, manifest = manifest.render())
        } catch (e: BridgeException) {
            CompileResult(
                reply = "Non riesco a contattare l'assistente adesso. Riprova tra poco.",
                draft = null,
                metaError = "bridge_${e.kind.name.lowercase()}: ${e.message}",
            )
        }
}
