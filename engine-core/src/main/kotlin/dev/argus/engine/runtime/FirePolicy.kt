package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
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
)

fun interface FirePolicySnapshotProvider {
    suspend fun current(): FirePolicySnapshot
}

/** Nomi stabili condivisi tra policy e capability probe Android. */
object ActionCapabilities {
    const val SET_WIFI = "action.set_wifi"
    const val SET_BLUETOOTH = "action.set_bluetooth"
    const val SET_DND = "action.set_dnd"
    const val SET_RINGER = "action.set_ringer"
    const val LAUNCH_APP = "action.launch_app"
    const val OPEN_URL = "action.open_url"
    const val SHOW_NOTIFICATION = "action.show_notification"
    const val TAP = "action.tap"
    const val INPUT_TEXT = "action.input_text"
    const val WHATSAPP_REPLY = "action.whatsapp_reply"
    const val RUN_SHELL = "action.run_shell"
    const val INVOKE_LLM = "action.invoke_llm"
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

        val required = automation.actions.flatMapTo(linkedSetOf(), ::requiredCapabilities)
        if (!snapshot.availableCapabilities.containsAll(required))
            return FirePolicyDecision.Block("capability_unavailable", needsReview = true)

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

    private fun requiredCapabilities(action: Action): Set<String> = when (action) {
        is Action.SetWifi -> setOf(ActionCapabilities.SET_WIFI)
        is Action.SetBluetooth -> setOf(ActionCapabilities.SET_BLUETOOTH)
        is Action.SetDnd -> setOf(ActionCapabilities.SET_DND)
        is Action.SetRinger -> setOf(ActionCapabilities.SET_RINGER)
        is Action.LaunchApp -> setOf(ActionCapabilities.LAUNCH_APP)
        is Action.OpenUrl -> setOf(ActionCapabilities.OPEN_URL)
        is Action.ShowNotification -> setOf(ActionCapabilities.SHOW_NOTIFICATION)
        is Action.Tap -> setOf(ActionCapabilities.TAP)
        is Action.InputText -> setOf(ActionCapabilities.INPUT_TEXT)
        is Action.WhatsAppReply -> setOf(ActionCapabilities.WHATSAPP_REPLY)
        is Action.RunShell -> setOf(ActionCapabilities.RUN_SHELL)
        is Action.InvokeLlm -> setOf(ActionCapabilities.INVOKE_LLM) + action.allowedTools
    }

    private fun canReply(action: Action): Boolean = when (action) {
        is Action.WhatsAppReply -> true
        is Action.InvokeLlm -> action.allowedTools.any { it.equals("whatsapp_reply", ignoreCase = true) }
        else -> false
    }
}
