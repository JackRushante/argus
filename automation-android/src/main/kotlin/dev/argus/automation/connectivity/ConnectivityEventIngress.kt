package dev.argus.automation.connectivity

import dev.argus.engine.connectivity.ConnectivityEventParser
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
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
    fun record(sourceKey: String, snapshot: ConnectivityStateSnapshot)
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
    ) {
        val envelope = mutex.withLock {
            val currentAt = atMillis.coerceAtLeast(0)
            val candidate = parser.parse(
                medium,
                connectionState,
                name,
                sourceIdentity,
                currentAt,
            ) ?: return@withLock null
            val safeName = (candidate.event as TriggerEvent.ConnectivityChanged).name
            val sourceKey = storageKey(medium, sourceIdentity)
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
            ) ?: return@withLock null

            when {
                previous == null -> {
                    state.record(
                        sourceKey,
                        ConnectivityStateSnapshot(connectionState, effectiveName, currentAt),
                    )
                    parsed.takeUnless { initial }
                }
                previous.state != connectionState -> {
                    state.record(
                        sourceKey,
                        ConnectivityStateSnapshot(connectionState, effectiveName, currentAt),
                    )
                    parsed
                }
                previous.name != effectiveName -> {
                    state.record(sourceKey, previous.copy(name = effectiveName))
                    // Come per il doppio broadcast telefonia anonimo/numerato: stessa identità.
                    parser.parse(
                        medium,
                        connectionState,
                        effectiveName,
                        sourceIdentity,
                        previous.transitionAtMillis,
                    )?.takeUnless { initial }
                }
                else -> null
            }
        }
        envelope?.let { dispatcher.dispatch(it) }
    }

    private fun storageKey(medium: ConnMedium, sourceIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${medium.name}\u0000$sourceIdentity".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
