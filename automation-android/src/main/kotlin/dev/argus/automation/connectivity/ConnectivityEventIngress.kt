package dev.argus.automation.connectivity

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
 * una transizione realmente persa mentre il processo era morto viene invece recuperata.
 */
class ConnectivityEventIngress(
    private val parser: ConnectivityEventParser,
    private val state: ConnectivityStateStore,
    private val dispatcher: ConnectivityEventDispatcher,
) {
    private val mutex = Mutex()

    suspend fun observe(
        medium: ConnMedium,
        connectionState: ConnState,
        name: String?,
        sourceIdentity: String,
        atMillis: Long,
        initial: Boolean = false,
    ): Unit = mutex.withLock {
        val currentAt = atMillis.coerceAtLeast(0)
        val candidate = parser.parse(
            medium,
            connectionState,
            name,
            sourceIdentity,
            currentAt,
        ) ?: return@withLock
        val safeName = (candidate.event as TriggerEvent.ConnectivityChanged).name
        val sourceKey = storageKey(medium, sourceIdentity)
        state.pending(sourceKey)?.let { deliver(sourceKey, it) }
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
        ) ?: return@withLock

        val envelope = when {
            previous == null -> {
                state.record(
                    sourceKey,
                    ConnectivityStateSnapshot(connectionState, effectiveName, currentAt),
                    parsed.takeUnless { initial },
                )
                parsed.takeUnless { initial }
            }
            previous.state != connectionState -> {
                state.record(
                    sourceKey,
                    ConnectivityStateSnapshot(connectionState, effectiveName, currentAt),
                    parsed,
                )
                parsed
            }
            previous.name != effectiveName -> {
                // Come per il doppio broadcast telefonia anonimo/numerato: stessa identità.
                val enriched = parser.parse(
                    medium,
                    connectionState,
                    effectiveName,
                    sourceIdentity,
                    previous.transitionAtMillis,
                )?.takeUnless { initial }
                state.record(sourceKey, previous.copy(name = effectiveName), enriched)
                enriched
            }
            else -> null
        }
        envelope?.let { deliver(sourceKey, it) }
        Unit
    }

    /** Ripete gli eventi persistiti con lo stesso id; il journal Engine evita doppie azioni. */
    suspend fun recoverPending(): List<String> = mutex.withLock {
        val recovered = mutableListOf<String>()
        var firstFailure: Exception? = null
        state.pending().sortedBy { it.first }.forEach { (sourceKey, envelope) ->
            try {
                deliver(sourceKey, envelope)
                recovered += sourceKey
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
        recovered
    }

    private suspend fun deliver(sourceKey: String, envelope: TriggerEnvelope) {
        dispatcher.dispatch(envelope)
        state.complete(sourceKey, envelope.id.value)
    }

    private fun storageKey(medium: ConnMedium, sourceIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${medium.name}\u0000$sourceIdentity".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
