package dev.argus.engine.directboot

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.toDraft
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * One rule in the device-encrypted copy. [rule] is the approved rule without its name (the copy keeps nothing
 * the locked path does not need); [fingerprint] binds the entry to the approved revision; [digest] catches a
 * damaged entry. The copy lives in app-private storage: the digest is not a defence against an attacker who can
 * already write there, it only makes corruption fail closed.
 */
@Serializable
data class DirectBootCopyEntry(
    val fingerprint: String,
    val digest: String,
    val rule: Automation,
)

@Serializable
data class DirectBootCopyFile(
    val version: Int,
    val rules: List<DirectBootCopyEntry>,
)

/** A copy entry the locked path refused, with a closed code (never content). */
data class DirectBootRejection(val automationId: AutomationId?, val code: String)

/** What the locked path accepts from the copy, after checking every entry again. */
data class DirectBootCopyRead(
    val rules: List<Automation>,
    val rejected: List<DirectBootRejection>,
)

object DirectBootCopy {
    const val VERSION = 1
    const val MAX_RULES = 32
    const val MAX_CHARS = 256 * 1024

    // Closed codes of a refused entry (they reach the Log after unlock).
    const val UNREADABLE = "before_unlock_copy_unreadable"
    const val CORRUPT = "before_unlock_copy_corrupt"
    const val NOT_ARMED = "before_unlock_not_armed"
    const val NOT_QUALIFYING = "before_unlock_not_qualifying"
    const val INVALID = "before_unlock_invalid"
    const val DUPLICATE = "before_unlock_duplicate"
    const val TOO_MANY = "before_unlock_too_many"

    /** Stands in for the name the copy does not keep, only so that the validator's name check passes. */
    private const val VALIDATION_NAME = "before unlock"

    /**
     * The copy entry of [automation], or null when it must not be in the copy: not armed, without a valid
     * approval, or not qualifying. The fingerprint is checked against the rule itself before anything is copied.
     */
    fun entryFor(automation: Automation): DirectBootCopyEntry? {
        val fingerprint = automation.approvalFingerprint ?: return null
        if (automation.status != AutomationStatus.ARMED || !automation.enabled) return null
        if (!DirectBootPolicy.qualifies(automation)) return null
        if (ApprovalFingerprints.of(automation) != fingerprint) return null
        val stored = automation.copy(name = "")
        return DirectBootCopyEntry(fingerprint.value, digestOf(stored), stored)
    }

    fun encode(entries: List<DirectBootCopyEntry>): String {
        require(entries.size <= MAX_RULES) { "too many rules for the copy" }
        val encoded = ArgusJson.encodeToString(
            DirectBootCopyFile.serializer(),
            DirectBootCopyFile(VERSION, entries.sortedBy { it.rule.id.value }),
        )
        require(encoded.length <= MAX_CHARS) { "copy too large" }
        return encoded
    }

    /**
     * Reads the copy back and checks every entry again (never trust the copy): integrity, binding to the approved
     * revision, armed, qualifying and valid for the validator. A file that cannot be read gives no rule at all.
     */
    fun decode(text: String, validator: DraftValidator): DirectBootCopyRead {
        if (text.length > MAX_CHARS) return unreadable()
        val file = runCatching { ArgusJson.decodeFromString(DirectBootCopyFile.serializer(), text) }.getOrNull()
            ?: return unreadable()
        if (file.version != VERSION) return unreadable()

        val accepted = mutableListOf<Automation>()
        val rejected = mutableListOf<DirectBootRejection>()
        val seen = mutableSetOf<AutomationId>()
        file.rules.forEach { entry ->
            val id = entry.rule.id
            val code = when {
                id in seen -> DUPLICATE
                accepted.size >= MAX_RULES -> TOO_MANY
                else -> refusal(entry, validator)
            }
            seen += id
            if (code == null) accepted += entry.rule else rejected += DirectBootRejection(id, code)
        }
        return DirectBootCopyRead(accepted, rejected)
    }

    private fun refusal(entry: DirectBootCopyEntry, validator: DraftValidator): String? {
        val rule = entry.rule
        val fingerprint = runCatching { ApprovalFingerprint(entry.fingerprint) }.getOrNull() ?: return CORRUPT
        if (rule.name.isNotEmpty() || rule.approvalFingerprint != fingerprint) return CORRUPT
        if (digestOf(rule) != entry.digest) return CORRUPT
        if (rule.status != AutomationStatus.ARMED || !rule.enabled) return NOT_ARMED
        if (!DirectBootPolicy.qualifies(rule)) return NOT_QUALIFYING
        val errors = runCatching {
            validator.validate(rule.toDraft().copy(name = VALIDATION_NAME), emptySet())
                .any { it.severity == Severity.ERROR }
        }.getOrDefault(true)
        return if (errors) INVALID else null
    }

    private fun unreadable() = DirectBootCopyRead(emptyList(), listOf(DirectBootRejection(null, UNREADABLE)))

    internal fun digestOf(rule: Automation): String {
        val canonical = ArgusJson.encodeToString(Automation.serializer(), rule)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("argus-before-unlock-v$VERSION\u0000$canonical".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
