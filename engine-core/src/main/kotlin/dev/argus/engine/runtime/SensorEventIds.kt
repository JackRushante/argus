package dev.argus.engine.runtime

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.SensorKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Identità stabile per una detection sensore. Accetta soltanto metadati e una sequenza monotona:
 * per costruzione non esiste un parametro nel quale infilare sample, soglie osservate o counter.
 */
object SensorEventIds {
    fun create(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        kind: SensorKind,
        detectionSequence: Long,
    ): TriggerEventId {
        require(detectionSequence >= 0) { "Sequenza detection sensore non valida" }
        val material = buildString {
            append(automationId.value)
            append('\u0000')
            append(approvalFingerprint.value)
            append('\u0000')
            append(kind.wireName)
            append('\u0000')
            append(detectionSequence)
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
        val alphabet = "0123456789abcdef"
        val hex = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = alphabet[value ushr 4]
            hex[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return TriggerEventId("sensor:${kind.wireName}:${hex.concatToString()}")
    }
}
