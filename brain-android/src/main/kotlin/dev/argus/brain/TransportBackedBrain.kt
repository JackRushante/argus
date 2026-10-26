package dev.argus.brain

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding

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
            // Un solo tentativo: l'eventuale retry degli errori di formato appartiene al decorator
            // MeteredBrain, che può registrare l'usage del primo turno e ricontrollare il budget
            // HARD prima di contattare nuovamente il provider.
            // D4: la bozza torna con gli alias dei contatti; qui diventano gli id reali, prima
            // della validazione locale.
            ContactAliasMapper.restore(
                transport.compile(message = nl, manifest = manifest, state = state),
                manifest,
            )
        } catch (e: TransportException) {
            // Nessun testo utente per gli errori di trasporto: lo dà la UI localizzata dal metaError.
            // Il messaggio ripulito del provider (4xx) viaggia a parte, solo in memoria, fino alla chat.
            CompileResult(reply = "", draft = null, metaError = metaErrorOf(e), providerMessage = e.providerMessage)
        }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = try {
        transport.act(context, goal, contextSources, allowedTools)
    } catch (e: TransportException) {
        ActResult(text = null, metaError = metaErrorOf(e))
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult = try {
        transport.actV2(context, action)
    } catch (e: TransportException) {
        ActResult(text = null, metaError = metaErrorOf(e))
    }

    override suspend fun actResolved(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
        runtimeData: List<RuntimeDataBinding>,
    ): ActResult = try {
        transport.actResolved(context, goal, contextSources, allowedTools, runtimeData)
    } catch (e: TransportException) {
        ActResult(text = null, metaError = metaErrorOf(e))
    }

    companion object {
        /** Prefisso stabile verso UI e journal degli errori HTTP: `bridge_http_<status>`. */
        const val HTTP_PREFIX = "bridge_http"

        /** Codice meta di un errore di trasporto; HTTP porta lo status (`bridge_http_503`). */
        fun metaErrorOf(error: TransportException): String {
            if (error.kind != TransportErrorKind.HTTP) return "bridge_${error.kind.name.lowercase()}"
            val status = error.statusCode?.takeIf { it in 100..599 } ?: return HTTP_PREFIX
            return "${HTTP_PREFIX}_$status"
        }
    }
}
