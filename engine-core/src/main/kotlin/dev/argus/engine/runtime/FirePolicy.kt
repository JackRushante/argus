package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.SCHEMA_VERSION
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import kotlinx.coroutines.CancellationException

sealed interface FirePolicyDecision {
    data object Allow : FirePolicyDecision
    data class Block(val code: String, val needsReview: Boolean) : FirePolicyDecision
}

/** Gate obbligatorio e senza default permissivo, eseguito per ogni regola al fire-time. */
fun interface FirePolicy {
    suspend fun evaluate(automation: Automation, event: TriggerEvent): FirePolicyDecision
}

data class FirePolicySnapshot(
    val knownTools: Set<String>,
    val availableCapabilities: Set<String>,
    val whitelistedConversationIds: Set<String>,
    /** Capability mancanti solo per un outage recuperabile, non per revoca della policy. */
    val transientlyUnavailableCapabilities: Set<String> = emptySet(),
)

fun interface FirePolicySnapshotProvider {
    suspend fun current(): FirePolicySnapshot
}

/** Nomi stabili condivisi tra policy e capability probe Android. */
object ActionCapabilities {
    const val SET_WIFI = CapabilityIds.ACTION_SET_WIFI
    const val SET_BLUETOOTH = CapabilityIds.ACTION_SET_BLUETOOTH
    const val SET_DND = CapabilityIds.ACTION_SET_DND
    const val SET_RINGER = CapabilityIds.ACTION_SET_RINGER
    const val LAUNCH_APP = CapabilityIds.ACTION_LAUNCH_APP
    const val OPEN_URL = CapabilityIds.ACTION_OPEN_URL
    const val SHOW_NOTIFICATION = CapabilityIds.ACTION_SHOW_NOTIFICATION
    const val TAP = CapabilityIds.ACTION_TAP
    const val INPUT_TEXT = CapabilityIds.ACTION_INPUT_TEXT
    const val WHATSAPP_REPLY = CapabilityIds.ACTION_WHATSAPP_REPLY
    const val RUN_SHELL = CapabilityIds.ACTION_RUN_SHELL
    const val COPY_TO_CLIPBOARD = CapabilityIds.ACTION_COPY_TO_CLIPBOARD
    const val INVOKE_LLM = CapabilityIds.ACTION_INVOKE_LLM
}

/**
 * Ripete gli invarianti del draft con whitelist/capability correnti. Le cause strutturali
 * mettono la regola in NEEDS_REVIEW; indisponibilità transitorie la bloccano soltanto.
 */
class RevalidatingFirePolicy(
    private val snapshots: FirePolicySnapshotProvider,
) : FirePolicy {

    override suspend fun evaluate(automation: Automation, event: TriggerEvent): FirePolicyDecision {
        val snapshot = try {
            snapshots.current()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return FirePolicyDecision.Block("capability_snapshot_unavailable", needsReview = false)
        }

        if (automation.schemaVersion != SCHEMA_VERSION)
            return FirePolicyDecision.Block("schema_incompatible", needsReview = true)
        if (automation.approvalFingerprint == null ||
            automation.approvalFingerprint != ApprovalFingerprints.of(automation)
        ) return FirePolicyDecision.Block("approval_fingerprint_mismatch", needsReview = true)

        val draft = AutomationDraft(
            name = automation.name,
            trigger = automation.trigger,
            actions = automation.actions,
            conditions = automation.conditions,
            cooldownMs = automation.cooldownMs,
        )
        val validationErrors = DraftValidator(snapshot.knownTools)
            .validate(draft, snapshot.whitelistedConversationIds)
            .any { it.severity == Severity.ERROR }
        if (validationErrors)
            return FirePolicyDecision.Block("validation_failed", needsReview = true)

        // P0-B non dispone ancora del flusso di conferma live: mai eseguire shell privilegiata.
        if (automation.actions.any { it is Action.RunShell })
            return FirePolicyDecision.Block("live_confirmation_required", needsReview = false)

        val derivedRequirements = CapabilityRequirements.derive(
            automation.trigger,
            automation.actions,
            automation.conditions,
        )
        if (automation.requiredCapabilities != derivedRequirements)
            return FirePolicyDecision.Block("capability_requirements_mismatch", needsReview = true)

        val required = automation.requiredCapabilities
        val missing = required - snapshot.availableCapabilities
        if (missing.isNotEmpty()) {
            val onlyTransient = snapshot.transientlyUnavailableCapabilities.containsAll(missing)
            return FirePolicyDecision.Block("capability_unavailable", needsReview = !onlyTransient)
        }

        if (automation.actions.any(::canReply)) {
            val notification = event as? TriggerEvent.NotificationPosted
                ?: return FirePolicyDecision.Block("reply_event_unverified", needsReview = false)
            val configured = automation.trigger as? dev.argus.engine.model.Trigger.Notification
                ?: return FirePolicyDecision.Block("reply_event_unverified", needsReview = true)
            val verified = notification.pkg in DraftValidator.WHATSAPP_PACKAGES &&
                notification.pkg == configured.pkg &&
                notification.isGroup == false &&
                notification.conversationId != null &&
                notification.conversationId == configured.conversationId &&
                notification.conversationId in snapshot.whitelistedConversationIds
            if (!verified)
                return FirePolicyDecision.Block("reply_event_unverified", needsReview = false)
            if (notification.notificationKey.isNullOrBlank())
                return FirePolicyDecision.Block("reply_notification_unavailable", needsReview = false)
        }

        return FirePolicyDecision.Allow
    }

    private fun canReply(action: Action): Boolean = when (action) {
        is Action.WhatsAppReply -> true
        is Action.InvokeLlm -> action.allowedTools.any { it.equals("whatsapp_reply", ignoreCase = true) }
        else -> false
    }
}
