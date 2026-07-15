package dev.argus.engine.connectivity

import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Normalizza un cambio connettività senza far entrare SSID o indirizzi Bluetooth negli ID
 * persistiti. [sourceIdentity] distingue le sorgenti ma viene usata soltanto come materiale hash.
 */
class ConnectivityEventParser {
    fun parse(
        medium: ConnMedium,
        state: ConnState,
        name: String?,
        sourceIdentity: String,
        atMillis: Long,
    ): TriggerEnvelope? {
        val identity = sourceIdentity.takeIf {
            it.isNotBlank() && it.length <= MAX_SOURCE_IDENTITY_CHARS && it.none(Char::isISOControl)
        } ?: return null
        val safeName = name
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(MAX_NAME_CHARS)
            ?.takeIf(String::isNotEmpty)
        val instant = atMillis.coerceAtLeast(0)
        return TriggerEnvelope(
            id = TriggerEventId(
                "connectivity:" + digest(medium.name, state.name, identity, instant.toString()),
            ),
            event = TriggerEvent.ConnectivityChanged(medium, state, safeName),
        )
    }

    private fun digest(vararg values: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            messageDigest.update(value.toByteArray(StandardCharsets.UTF_8))
            messageDigest.update(0)
        }
        return messageDigest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_SOURCE_IDENTITY_CHARS = 512
        const val MAX_NAME_CHARS = 120
    }
}
