package dev.argus.automation

import android.os.SystemClock
import dev.argus.brain.AgentTransport
import dev.argus.brain.BridgeErrorKind
import dev.argus.brain.BridgeException
import dev.argus.brain.DefaultTransportFactory
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.TransportBackedBrain
import dev.argus.brain.TransportFactory
import dev.argus.brain.TransportHealth
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding
import kotlinx.coroutines.CancellationException

sealed interface BridgeHealthResult {
    data class Reachable(val health: TransportHealth, val latencyMillis: Long) : BridgeHealthResult
    /**
     * [statusCode] e [providerMessage] spiegano la causa all'utente nel test di connessione (404 = URL
     * sbagliato, 400 = modello o opzioni rifiutati, ...). Il messaggio è già ripulito dal transport e
     * non va mai né nei log né persistito.
     */
    data class Unreachable(
        val kind: BridgeErrorKind,
        val statusCode: Int? = null,
        val providerMessage: String? = null,
    ) : BridgeHealthResult
}

/**
 * Facade runtime che ricostruisce il transport solo quando cambia la [ProviderConfig] selezionata
 * (provider, URL o modello). Il bearer viene invece letto dallo store protetto a ogni richiesta,
 * quindi né URL né token richiedono un riavvio processo. Il transport concreto lo sceglie
 * [TransportFactory]: qui non c'è alcuna conoscenza dei provider.
 */
class ConfiguredBridgeBrain(
    private val configuration: ProviderConfigStore,
    private val privacyAccepted: () -> Boolean,
    private val factory: TransportFactory = DefaultTransportFactory(configuration),
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : Brain {
    @Volatile
    private var cached: Pair<ProviderConfig, AgentTransport>? = null

    override suspend fun compile(
        nl: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        requirePrivacyConsent()
        return TransportBackedBrain(currentTransport()).compile(nl, manifest, state)
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult {
        requirePrivacyConsent()
        return TransportBackedBrain(currentTransport()).act(context, goal, contextSources, allowedTools)
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult {
        requirePrivacyConsent()
        return TransportBackedBrain(currentTransport()).actV2(context, action)
    }

    override suspend fun actResolved(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
        runtimeData: List<RuntimeDataBinding>,
    ): ActResult {
        requirePrivacyConsent()
        return TransportBackedBrain(currentTransport())
            .actResolved(context, goal, contextSources, allowedTools, runtimeData)
    }

    suspend fun health(): BridgeHealthResult {
        if (!privacyAccepted()) {
            return BridgeHealthResult.Unreachable(BridgeErrorKind.CONFIGURATION)
        }
        val started = elapsedRealtimeMillis()
        return try {
            BridgeHealthResult.Reachable(
                health = currentTransport().health(),
                latencyMillis = (elapsedRealtimeMillis() - started).coerceAtLeast(0),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: BridgeException) {
            BridgeHealthResult.Unreachable(error.kind, error.statusCode, error.providerMessage)
        } catch (_: IllegalArgumentException) {
            BridgeHealthResult.Unreachable(BridgeErrorKind.CONFIGURATION)
        }
    }

    /** Config del provider selezionato (senza segreti): la chat ne ricava il timeout del compile. */
    fun selectedConfig(): ProviderConfig = configuration.selectedProviderConfig()

    /** Pronto = ha la chiave che il provider selezionato richiede (nessuna per un Custom anonimo). */
    suspend fun configured(): Boolean {
        val id = configuration.selectedProviderId()
        return !ProviderCatalog.spec(id).apiKeyRequired || configuration.hasApiKey(id)
    }

    /**
     * Cache single-slot keyed sulla [ProviderConfig] selezionata (che NON contiene la chiave API:
     * il bearer si legge on-demand a ogni richiesta). Un cambio di provider/URL/modello invalida e
     * ricostruisce. Il throw della factory per un provider senza transport si propaga qui, fuori dal
     * try di [TransportBackedBrain]: è lo stesso canale del blocco privacy, già gestito da
     * ChatViewModel e dalla lane generativa.
     */
    private fun currentTransport(): AgentTransport {
        val config = configuration.selectedProviderConfig()
        cached?.takeIf { it.first == config }?.second?.let { return it }
        return synchronized(this) {
            cached?.takeIf { it.first == config }?.second
                ?: createTransport(config).also { cached = config to it }
        }
    }

    /**
     * Una configurazione che il transport rifiuta alla costruzione (URL con credenziali, http non
     * loopback, ...) è un problema di CONFIGURAZIONE: esce come errore di trasporto tipizzato, sullo
     * stesso canale del blocco privacy. Così un [IllegalArgumentException] che arriva ai chiamanti è
     * sempre un errore interno dell'app, mai una configurazione sbagliata.
     */
    private fun createTransport(config: ProviderConfig): AgentTransport =
        try {
            factory.create(config)
        } catch (error: IllegalArgumentException) {
            throw BridgeException(
                BridgeErrorKind.CONFIGURATION,
                "configurazione provider non valida",
                cause = error,
            )
        }

    private fun requirePrivacyConsent() {
        if (!privacyAccepted()) {
            throw BridgeException(
                BridgeErrorKind.CONFIGURATION,
                "consenso privacy non accettato",
            )
        }
    }
}
