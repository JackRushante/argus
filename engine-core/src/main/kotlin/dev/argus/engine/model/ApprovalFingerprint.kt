package dev.argus.engine.model

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
@Serializable
value class ApprovalFingerprint(val value: String) {
    init {
        require(value.matches(Regex("^[0-9a-f]{64}$"))) { "Fingerprint approvazione non valido" }
    }
}

/** Hash canonico dei soli dati eseguibili mostrati all'utente; la prosa LLM è esclusa. */
object ApprovalFingerprints {
    fun of(automation: Automation): ApprovalFingerprint {
        val normalized = automation.copy(
            status = AutomationStatus.PENDING_APPROVAL,
            enabled = false,
            approvalFingerprint = null,
        )
        val canonical = ArgusJson.encodeToString(
            Automation.serializer(),
            normalized,
        )
        val material = "argus-approval-v1\u0000$canonical"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
        return ApprovalFingerprint(digest.toHex())
    }

    private fun ByteArray.toHex(): String {
        val alphabet = "0123456789abcdef"
        return buildString(size * 2) {
            for (byte in this@toHex) {
                val unsigned = byte.toInt() and 0xff
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0f])
            }
        }
    }
}
