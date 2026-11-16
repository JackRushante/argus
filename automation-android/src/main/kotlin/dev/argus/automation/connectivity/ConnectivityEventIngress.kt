package dev.argus.automation.connectivity

import dev.argus.automation.SequentialLanes
import dev.argus.engine.connectivity.ConnectivityEventParser
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ConnectivityStateSnapshot(
    val state: ConnState,
    val name: String?,
    val transitionAtMillis: Long,
)

interface ConnectivityStateStore {
    fun last(sourceKey: String): ConnectivityStateSnapshot?
    fun pending(sourceKey: String): TriggerEnvelope?
    fun pending(): List<Pair<String, TriggerEnvelope>>
    fun record(
        sourceKey: String,
        snapshot: ConnectivityStateSnapshot,
        pending: TriggerEnvelope? = null,
    )
    fun complete(sourceKey: String, eventId: String)
}

fun interface ConnectivityEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

/**
 * Single-writer delle transizioni Connectivity. Lo stato persistito evita falsi fire al restart;
 * una transizione realmente persa mentre il processo era morto viene invece recuperata, ma solo se
 * il monitor era già attivo in continuo (vedi `recoverMissed` di [observe]).
 *
 * Il pending si persiste sotto [mutex] (sezioni brevi); la consegna, che può durare ore, avviene
 * FUORI da quel lock in una coda sequenziale per sorgente ([lanes], B3): le transizioni della
 * stessa sorgente restano in ordine e il record resta «dovuto» fino al completamento, mentre
 * sorgenti diverse e i recovery non aspettano.
 */
class ConnectivityEventIngress(
    private val parser: ConnectivityEventParser,
    private val state: ConnectivityStateStore,
    private val dispatcher: ConnectivityEventDispatcher,
) {
    private val mutex = Mutex()
    private val lanes = SequentialLanes<String>()

    /**
     * [initial] marca la prima osservazione di un monitor appena avviato: di norma diventa solo
     * RIFERIMENTO (aggiorna lo stato salvato, nessun dispatch), perché lo stato salvato può
     * risalire a quando il monitor era spento. Solo con [recoverMissed] (monitor già attivo in
     * continuo, per esempio ripartito dopo un kill) un cambio rispetto allo stato salvato è una
     * transizione realmente persa e viene recapitata.
     */
    suspend fun observe(
        medium: ConnMedium,
        connectionState: ConnState,
        name: String?,
        sourceIdentity: String,
        atMillis: Long,
        initial: Boolean = false,
        recoverMissed: Boolean = false,
    ) {
        val currentAt = atMillis.coerceAtLeast(0)
        val candidate = parser.parse(
            medium,
            connectionState,
            name,
            sourceIdentity,
            currentAt,
        ) ?: return
        val safeName = (candidate.event as TriggerEvent.ConnectivityChanged).name
        val sourceKey = storageKey(medium, sourceIdentity)
        val referenceOnly = initial && !recoverMissed
        lanes.run(sourceKey) {
            // Un pending lasciato indietro (dispatch fallito/processo morto) va prima di tutto.
            mutex.withLock { state.pending(sourceKey) }?.let { deliver(sourceKey, it) }
            val step = mutex.withLock {
                recordTransition(
                    medium,
                    connectionState,
                    sourceIdentity,
                    sourceKey,
                    currentAt,
                    candidate,
                    safeName,
                    initial,
                    referenceOnly,
                )
            }
            when (step) {
                null -> Unit
                is Step.Deliver -> deliver(sourceKey, step.envelope)
                is Step.Switch -> {
                    // Il pending è uno per sorgente: prima la disconnessione dalla rete vecchia
                    // (già persistita), poi si persiste e si consegna la connessione alla nuova.
                    // Se il processo muore in mezzo, lo stato salvato resta «scollegato» e la
                    // prossima osservazione della rete nuova è una transizione vera.
                    deliver(sourceKey, step.disconnected)
                    mutex.withLock { state.record(sourceKey, step.connectedSnapshot, step.connected) }
                    deliver(sourceKey, step.connected)
                }
            }
        }
    }

    private sealed interface Step {
        class Deliver(val envelope: TriggerEnvelope) : Step

        /** Cambio di rete a stato invariato (B4): due transizioni con id nuovi. */
        class Switch(
            val disconnected: TriggerEnvelope,
            val connected: TriggerEnvelope,
            val connectedSnapshot: ConnectivityStateSnapshot,
        ) : Step
    }

    /** Sotto lock: decide la transizione e la persiste (il primo pending). Non consegna. */
    private fun recordTransition(
        medium: ConnMedium,
        connectionState: ConnState,
        sourceIdentity: String,
        sourceKey: String,
        currentAt: Long,
        candidate: TriggerEnvelope,
        safeName: String?,
        initial: Boolean,
        referenceOnly: Boolean,
    ): Step? {
        val previous = state.last(sourceKey)
        // DISCONNECTED non porta sempre l'identità; conserva quella dell'ultima connessione.
        // Un nuovo CONNECTED anonimo invece non deve ereditare un vecchio SSID/dispositivo.
        val effectiveName = when {
            safeName != null -> safeName
            connectionState == ConnState.DISCONNECTED -> previous?.name
            previous?.state == connectionState -> previous.name
            else -> null
        }
        val parsed = if (effectiveName == safeName) candidate else parser.parse(
            medium,
            connectionState,
            effectiveName,
            sourceIdentity,
            currentAt,
        ) ?: return null

        return when {
            previous == null -> {
                state.record(
                    sourceKey,
                    ConnectivityStateSnapshot(connectionState, effectiveName, currentAt),
                    parsed.takeUnless { initial },
                )
                parsed.takeUnless { initial }?.let(Step::Deliver)
            }
            previous.state != connectionState -> {
                val pending = parsed.takeUnless { referenceOnly }
                state.record(
                    sourceKey,
                    ConnectivityStateSnapshot(connectionState, effectiveName, currentAt),
                    pending,
                )
                pending?.let(Step::Deliver)
            }
            previous.name == effectiveName -> null
            previous.name == null -> {
                // Solo un nome che arriva dopo (null → nome) è un arricchimento della stessa
                // transizione, come il doppio broadcast telefonia anonimo/numerato: stessa identità.
                val enriched = parser.parse(
                    medium,
                    connectionState,
                    effectiveName,
                    sourceIdentity,
                    previous.transitionAtMillis,
                )?.takeUnless { initial }
                state.record(sourceKey, previous.copy(name = effectiveName), enriched)
                enriched?.let(Step::Deliver)
            }
            connectionState == ConnState.DISCONNECTED -> {
                // Già scollegati: un nome diverso non è un evento, si aggiorna solo il riferimento.
                state.record(sourceKey, previous.copy(name = effectiveName), null)
                null
            }
            referenceOnly -> {
                // Il cambio di rete è avvenuto a monitor spento: nuovo riferimento, nessun evento.
                state.record(
                    sourceKey,
                    ConnectivityStateSnapshot(connectionState, effectiveName, currentAt),
                    null,
                )
                null
            }
            else -> {
                // Un'altra rete con nome (B4): è una transizione nuova, non un arricchimento.
                // DISCONNECTED per la rete vecchia, poi CONNECTED per la nuova, con id freschi.
                val disconnected = parser.parse(
                    medium,
                    ConnState.DISCONNECTED,
                    previous.name,
                    sourceIdentity,
                    currentAt,
                ) ?: return null
                state.record(
                    sourceKey,
                    ConnectivityStateSnapshot(ConnState.DISCONNECTED, previous.name, currentAt),
                    disconnected,
                )
                Step.Switch(
                    disconnected = disconnected,
                    connected = parsed,
                    connectedSnapshot = ConnectivityStateSnapshot(connectionState, effectiveName, currentAt),
                )
            }
        }
    }

    /**
     * Ripete gli eventi persistiti con lo stesso id; il journal Engine evita doppie azioni.
     * Una sorgente con una consegna già in corso (o accodata) viene saltata: chi occupa la corsia
     * recapita già il suo pending, e il recovery non deve bloccarsi dietro a un dispatch lungo.
     */
    suspend fun recoverPending(): List<String> {
        val recovered = mutableListOf<String>()
        var firstFailure: Exception? = null
        val sources = mutex.withLock { state.pending() }.map { it.first }.sorted()
        sources.forEach { sourceKey ->
            try {
                val done = lanes.runIfIdle(sourceKey) {
                    val envelope = mutex.withLock { state.pending(sourceKey) }
                        ?: return@runIfIdle false
                    deliver(sourceKey, envelope)
                    true
                }
                if (done == true) recovered += sourceKey
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
        return recovered
    }

    /** Fuori dal lock di stato: dispatch (può durare ore), poi conferma sotto lock. */
    private suspend fun deliver(sourceKey: String, envelope: TriggerEnvelope) {
        dispatcher.dispatch(envelope)
        mutex.withLock { state.complete(sourceKey, envelope.id.value) }
    }

    private fun storageKey(medium: ConnMedium, sourceIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${medium.name}\u0000$sourceIdentity".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
