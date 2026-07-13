package dev.argus.engine.runtime

import dev.argus.engine.model.AutomationId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Identità stabile assegnata dalla sorgente del trigger e riusata in ogni redelivery. */
@JvmInline
value class TriggerEventId(val value: String) {
    init {
        require(value.isNotBlank()) { "TriggerEventId non può essere vuoto" }
        require(value.length <= 512) { "TriggerEventId supera 512 caratteri" }
    }
}

/** Identità opaca di una singola esecuzione automation+evento. */
@JvmInline
value class ExecutionId(val value: String) {
    init {
        require(value.isNotBlank()) { "ExecutionId non può essere vuoto" }
        require(value.length <= 128) { "ExecutionId supera 128 caratteri" }
    }
}

data class TriggerEnvelope(val id: TriggerEventId, val event: TriggerEvent)

fun interface ExecutionIdFactory {
    fun create(automationId: AutomationId, eventId: TriggerEventId): ExecutionId
}

/**
 * ID deterministico: la redelivery dello stesso evento produce lo stesso correlation ID.
 * Il valore raw dell'evento (che può contenere una notification key) non finisce nei log.
 */
object StableExecutionIdFactory : ExecutionIdFactory {
    override fun create(automationId: AutomationId, eventId: TriggerEventId): ExecutionId {
        val material = "${automationId.value}\u0000${eventId.value}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        val alphabet = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = alphabet[value ushr 4]
            hex[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return ExecutionId(hex.concatToString())
    }
}
