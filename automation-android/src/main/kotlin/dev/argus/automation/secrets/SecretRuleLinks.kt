package dev.argus.automation.secrets

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.SecretPolicy
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.secrets.SecretVault
import kotlinx.coroutines.CancellationException

/**
 * 0.8: the compile manifest carries the NAMES of the saved secrets, so the model can reference them. The
 * vault never returns a value here. If the vault cannot be read the list is empty: the model then does not
 * know any name and the approval refuses any it invents.
 */
class SecretNamesCapabilityProbe(
    private val probe: CapabilityProbe,
    private val vault: SecretVault,
) : CapabilityProbe {
    override suspend fun probe(currentState: DeviceState): CapabilityManifest {
        val manifest = probe.probe(currentState)
        val names = try {
            vault.names().toList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        return manifest.copy(savedSecrets = names)
    }
}

/**
 * 0.8: deleting a secret. The rules that name it (armed or switched off) stop and move to «needs review»:
 * they could no longer run, and re-arming them goes through the approval, which refuses a missing secret.
 */
class SecretDeletion(
    private val vault: SecretVault,
    private val automations: AutomationStore,
    private val audit: AuditSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** false if the secret was not deleted; the rules are touched only after a successful delete. */
    suspend fun delete(name: String): Boolean {
        if (!vault.delete(name)) return false
        automations.all()
            .filter { name in SecretPolicy.namesUsedBy(it) }
            .forEach { automation -> moveToReview(automation.id, automation.status, automation.approvalFingerprint) }
        return true
    }

    private suspend fun moveToReview(
        id: AutomationId,
        status: AutomationStatus,
        fingerprint: dev.argus.engine.model.ApprovalFingerprint?,
    ) {
        val moved = when (status) {
            AutomationStatus.ARMED -> if (fingerprint != null) {
                automations.markNeedsReviewIfApproved(id, fingerprint)
            } else {
                automations.markNeedsReview(id)
                true
            }
            AutomationStatus.DISABLED -> {
                automations.markNeedsReview(id)
                true
            }
            // Already to review, or a legacy draft in approval: the approval will refuse the missing name.
            AutomationStatus.NEEDS_REVIEW, AutomationStatus.PENDING_APPROVAL -> false
        }
        if (!moved) return
        try {
            audit.record(AuditEvent(id, AuditKind.RULE_NEEDS_REVIEW, nowMillis(), detail = REASON))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The log never changes the outcome.
        }
    }

    companion object {
        /** Reason recorded in the Log; a closed code, never the secret's name. */
        const val REASON = "secret_deleted"
    }
}
