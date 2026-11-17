package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.NotificationActionPolicy
import dev.argus.engine.model.SpanPolicy
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.toDraft
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.ReplyChannels
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.StaticShellSafety
import kotlinx.coroutines.CancellationException

sealed interface FirePolicyDecision {
    data object Allow : FirePolicyDecision
    data class Block(val code: String, val needsReview: Boolean) : FirePolicyDecision
}

/** Gate obbligatorio e senza default permissivo, eseguito per ogni regola al fire-time. */
fun interface FirePolicy {
    suspend fun evaluate(automation: Automation, event: TriggerEvent): FirePolicyDecision

    /**
     * 0.8: lo scatto di [phase] di una regola-intervallo. La fine ricontrolla tutto ciò che è strutturale (impronta,
     * validazione) e, sul trigger di FINE e sull'evento arrivato, la shell e le macro delle azioni di fine.
     * Default: come [evaluate] (i fake dei test); la produzione ([RevalidatingFirePolicy]) distingue le fasi.
     */
    suspend fun evaluate(automation: Automation, event: TriggerEvent, phase: SpanPhase): FirePolicyDecision =
        evaluate(automation, event)

    /**
     * 0.8: si può rimettere ora un valore registrato con [action]? Servono le stesse capacità che servono per
     * impostarlo. Default: sì (i fake dei test); la produzione controlla le capacità correnti.
     */
    suspend fun evaluateRestore(action: Action): FirePolicyDecision = FirePolicyDecision.Allow
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
    const val SET_MOBILE_DATA = CapabilityIds.ACTION_SET_MOBILE_DATA
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
    const val COPY_TEXT = CapabilityIds.ACTION_COPY_TEXT
    const val SET_ALARM = CapabilityIds.ACTION_SET_ALARM
    const val SET_TIMER = CapabilityIds.ACTION_SET_TIMER
    const val SET_VOLUME = CapabilityIds.ACTION_SET_VOLUME
    const val SET_FLASHLIGHT = CapabilityIds.ACTION_SET_FLASHLIGHT
    const val SET_DARK_MODE = CapabilityIds.ACTION_SET_DARK_MODE
    const val OPEN_SETTINGS_SCREEN = CapabilityIds.ACTION_OPEN_SETTINGS_SCREEN
    const val VIBRATE = CapabilityIds.ACTION_VIBRATE
    const val WRITE_SETTING = CapabilityIds.ACTION_WRITE_SETTING
    const val INVOKE_LLM = CapabilityIds.ACTION_INVOKE_LLM
    const val SPEAK = CapabilityIds.ACTION_SPEAK
    const val MEDIA_CONTROL = CapabilityIds.ACTION_MEDIA_CONTROL
    const val SET_BRIGHTNESS = CapabilityIds.ACTION_SET_BRIGHTNESS
    const val SET_ROTATION = CapabilityIds.ACTION_SET_ROTATION
    const val HTTP_REQUEST = CapabilityIds.ACTION_HTTP_REQUEST
    const val APP_CONTROL = CapabilityIds.ACTION_APP_CONTROL
    const val SET_AIRPLANE = CapabilityIds.ACTION_SET_AIRPLANE
    const val SET_LOCATION = CapabilityIds.ACTION_SET_LOCATION
    const val SET_NFC = CapabilityIds.ACTION_SET_NFC
    const val SET_BATTERY_SAVER = CapabilityIds.ACTION_SET_BATTERY_SAVER
    const val LOCK_SCREEN = CapabilityIds.ACTION_LOCK_SCREEN
    const val SCREENSHOT = CapabilityIds.ACTION_SCREENSHOT
    const val APP_PERMISSION = CapabilityIds.ACTION_APP_PERMISSION
    const val UI_STEPS = CapabilityIds.ACTION_UI_STEPS
}

/**
 * Ripete gli invarianti del draft con whitelist/capability correnti. Le cause strutturali
 * mettono la regola in NEEDS_REVIEW; indisponibilità transitorie la bloccano soltanto.
 */
class RevalidatingFirePolicy(
    private val snapshots: FirePolicySnapshotProvider,
) : FirePolicy {

    override suspend fun evaluate(automation: Automation, event: TriggerEvent): FirePolicyDecision =
        evaluate(automation, event, SpanPhase.START)

    /**
     * 0.8: per la fine di una regola-intervallo il trigger che «preme l'interruttore» è quello di FINE e le azioni
     * che partono sono quelle di fine: shell, macro, risposte e capacità si controllano su quella coppia. Impronta,
     * schema, validazione e requisiti restano della regola intera.
     */
    override suspend fun evaluate(
        automation: Automation,
        event: TriggerEvent,
        phase: SpanPhase,
    ): FirePolicyDecision {
        val snapshot = try {
            snapshots.current()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return FirePolicyDecision.Block("capability_snapshot_unavailable", needsReview = false)
        }

        if (!AutomationSchema.isCompatible(automation))
            return FirePolicyDecision.Block("schema_incompatible", needsReview = true)
        if (automation.approvalFingerprint == null ||
            automation.approvalFingerprint != ApprovalFingerprints.of(automation)
        ) return FirePolicyDecision.Block("approval_fingerprint_mismatch", needsReview = true)

        val exit = automation.exit
        if (phase == SpanPhase.EXIT && exit == null)
            return FirePolicyDecision.Block("trigger_mismatch", needsReview = false)
        val phaseTrigger = if (phase == SpanPhase.EXIT) requireNotNull(exit).trigger else automation.trigger
        val phaseActions = if (phase == SpanPhase.EXIT) requireNotNull(exit).actions else automation.actions

        val hasStaticShell = containsStaticShell(phaseActions)
        if (hasStaticShell &&
            !StaticShellSafety.allows(phaseTrigger, snapshot.whitelistedConversationIds)
        ) return FirePolicyDecision.Block("shell_external_trigger", needsReview = true)

        // 0.5.1: una macro sull'interfaccia ha lo stesso confine della shell: chi preme l'interruttore.
        val hasUiSteps = containsUiSteps(phaseActions)
        if (hasUiSteps &&
            !StaticShellSafety.allows(phaseTrigger, snapshot.whitelistedConversationIds)
        ) return FirePolicyDecision.Block("ui_steps_external_trigger", needsReview = true)

        // L'unica copia tipata regola → bozza: porta anche `confirm` ed `exit` (0.8) al validator.
        val draft = automation.toDraft()
        val validationErrors = DraftValidator(snapshot.knownTools)
            .validate(draft, snapshot.whitelistedConversationIds)
            .any { it.severity == Severity.ERROR }
        if (validationErrors)
            return FirePolicyDecision.Block("validation_failed", needsReview = true)

        // 0.8: una scansione NON verificata del tag approvato (l'activity dei tag è esportata, quindi
        // forgiabile) fa scattare la regola, ma solo per le altre azioni: ogni foglia shell o macro, anche
        // dentro if/while, fallisce all'esecutore sull'evento arrivato davvero (`shell_external_trigger`,
        // `ui_steps_external_trigger`). Un tag diverso resta bloccato qui sotto.
        val unverifiedScanOfApprovedTag = event is TriggerEvent.NfcTagScanned && !event.verified &&
            StaticShellSafety.allows(phaseTrigger, event.copy(verified = true), snapshot.whitelistedConversationIds)

        // Difesa sul dato live oltre al trigger approvato: l'identità va verificata sull'evento
        // realmente arrivato, non solo su quella dichiarata nella regola. Una regressione di
        // routing non deve mai far innescare la shell da un mittente non verificato.
        if (hasStaticShell && !unverifiedScanOfApprovedTag &&
            !StaticShellSafety.allows(
                phaseTrigger,
                event,
                snapshot.whitelistedConversationIds,
            )
        )
            return FirePolicyDecision.Block("shell_external_trigger", needsReview = false)

        if (hasUiSteps && !unverifiedScanOfApprovedTag &&
            !StaticShellSafety.allows(
                phaseTrigger,
                event,
                snapshot.whitelistedConversationIds,
            )
        )
            return FirePolicyDecision.Block("ui_steps_external_trigger", needsReview = false)

        val derivedRequirements = CapabilityRequirements.derive(automation)
        if (automation.requiredCapabilities != derivedRequirements)
            return FirePolicyDecision.Block("capability_requirements_mismatch", needsReview = true)

        // L'inizio chiede tutto ciò che la regola usa (anche la fine deve poter arrivare). La fine chiede solo ciò
        // che le serve per partire: il suo trigger, le sue azioni e le variabili; ogni valore da rimettere ha il
        // suo controllo ([evaluateRestore]), così una capacità mancante ferma solo quel valore.
        val required = if (phase == SpanPhase.EXIT) {
            CapabilityRequirements.derive(phaseTrigger, phaseActions, conditions = null, vars = automation.vars) +
                CapabilityIds.RULE_EXIT
        } else {
            automation.requiredCapabilities
        }
        val missing = required - snapshot.availableCapabilities
        if (missing.isNotEmpty()) {
            // D6: una capability mancante (permesso revocato, Shizuku spento, runtime generativo
            // non pronto) blocca lo scatto ma non manda la regola «da rivedere»: il contenuto
            // approvato non è cambiato. NEEDS_REVIEW resta per impronta, validazione e requisiti.
            return FirePolicyDecision.Block("capability_unavailable", needsReview = false)
        }

        val replyPackageAllowed = replyPackageCheck(phaseActions)
        if (replyPackageAllowed != null) {
            val notification = event as? TriggerEvent.NotificationPosted
                ?: return FirePolicyDecision.Block("reply_event_unverified", needsReview = false)
            val configured = phaseTrigger as? dev.argus.engine.model.Trigger.Notification
                ?: return FirePolicyDecision.Block("reply_event_unverified", needsReview = true)
            val verified = replyPackageAllowed(notification.pkg) &&
                notification.pkg == configured.pkg &&
                notification.isGroup == false &&
                notification.conversationId != null &&
                notification.conversationId == configured.conversationId &&
                notification.conversationId in snapshot.whitelistedConversationIds &&
                ReplyChannels.isStableConversation(notification.pkg, notification.conversationId)
            if (!verified)
                return FirePolicyDecision.Block("reply_event_unverified", needsReview = false)
            if (notification.notificationKey.isNullOrBlank())
                return FirePolicyDecision.Block("reply_notification_unavailable", needsReview = false)
        }

        // 0.8 notification_action: agisce solo sulla notifica viva dell'app approvata.
        if (contains(phaseActions) { it is Action.NotificationAction }) {
            val notification = event as? TriggerEvent.NotificationPosted
            val configured = phaseTrigger as? dev.argus.engine.model.Trigger.Notification
            val verified = notification != null && configured != null &&
                notification.pkg == configured.pkg &&
                !notification.notificationKey.isNullOrBlank()
            if (!verified)
                return FirePolicyDecision.Block(NotificationActionPolicy.CODE_EVENT_UNVERIFIED, needsReview = false)
        }

        return FirePolicyDecision.Allow
    }

    /**
     * 0.8: rimettere un valore usa le capacità che servono a impostarlo (lo stesso tipo d'azione). Un'azione che
     * esegue codice o parla con l'esterno non è mai un ripristino.
     */
    override suspend fun evaluateRestore(action: Action): FirePolicyDecision {
        if (SpanPolicy.slotOf(action) == null)
            return FirePolicyDecision.Block(SpanCodes.RESTORE_FAILED, needsReview = false)
        val snapshot = try {
            snapshots.current()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return FirePolicyDecision.Block("capability_snapshot_unavailable", needsReview = false)
        }
        val required = CapabilityRequirements.derive(Trigger.Manual, listOf(action)) -
            CapabilityRequirements.derive(Trigger.Manual, emptyList())
        return if ((required - snapshot.availableCapabilities).isEmpty()) {
            FirePolicyDecision.Allow
        } else {
            FirePolicyDecision.Block(SpanCodes.RESTORE_CAPABILITY_UNAVAILABLE, needsReview = false)
        }
    }

    /**
     * I package a cui le risposte della regola possono andare, o null se la regola non risponde. Ogni
     * azione di risposta restringe: `whatsapp_reply` solo WhatsApp, `notification_reply` solo le altre
     * app di [ReplyChannels], l'AI entrambe.
     */
    private fun replyPackageCheck(actions: List<Action>): ((String) -> Boolean)? {
        var any = false
        var whatsAppOnly = false
        var notificationOnly = false
        visitLeaves(actions) { action ->
            when (action) {
                is Action.WhatsAppReply -> { any = true; whatsAppOnly = true }
                is Action.NotificationReply -> { any = true; notificationOnly = true }
                else -> if (canReply(action)) any = true
            }
        }
        if (!any) return null
        return { pkg ->
            ReplyChannels.isReplyPackage(pkg) &&
                (!whatsAppOnly || ReplyChannels.isWhatsApp(pkg)) &&
                (!notificationOnly || ReplyChannels.isNotificationReplyPackage(pkg))
        }
    }

    private fun contains(actions: List<Action>, predicate: (Action) -> Boolean): Boolean {
        var found = false
        visitLeaves(actions) { if (predicate(it)) found = true }
        return found
    }

    private fun visitLeaves(actions: List<Action>, visit: (Action) -> Unit) {
        val pending = ArrayDeque<Action>()
        actions.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            when (val action = pending.removeFirst()) {
                is Action.If -> {
                    action.then.forEach(pending::addLast)
                    action.orElse.forEach(pending::addLast)
                }
                is Action.While -> action.body.forEach(pending::addLast)
                else -> visit(action)
            }
        }
    }

    private fun containsStaticShell(actions: List<Action>): Boolean {
        val pending = ArrayDeque<Action>()
        actions.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            when (val action = pending.removeFirst()) {
                is Action.RunShell -> return true
                is Action.If -> {
                    action.then.forEach(pending::addLast)
                    action.orElse.forEach(pending::addLast)
                }
                is Action.While -> action.body.forEach(pending::addLast)
                else -> Unit
            }
        }
        return false
    }

    private fun containsUiSteps(actions: List<Action>): Boolean {
        val pending = ArrayDeque<Action>()
        actions.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            when (val action = pending.removeFirst()) {
                is Action.UiSteps -> return true
                is Action.If -> {
                    action.then.forEach(pending::addLast)
                    action.orElse.forEach(pending::addLast)
                }
                is Action.While -> action.body.forEach(pending::addLast)
                else -> Unit
            }
        }
        return false
    }

    private fun canReply(action: Action): Boolean = when (action) {
        is Action.WhatsAppReply -> true
        is Action.InvokeLlm -> action.allowedTools.any { it.equals("whatsapp_reply", ignoreCase = true) }
        is Action.InvokeLlmV2 -> action.allowedTools.any { it.equals("whatsapp_reply", ignoreCase = true) }
        else -> false
    }
}
