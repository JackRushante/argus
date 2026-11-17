package dev.argus.engine.dryrun

import dev.argus.engine.model.Action
import dev.argus.engine.model.FeatureGates
import dev.argus.engine.model.HttpRequestPolicy
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.NotificationActionPolicy
import dev.argus.engine.model.SecretPolicy
import dev.argus.engine.model.TelephonyActionPolicy
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.ReplyChannels
import dev.argus.engine.safety.StaticShellSafety

/**
 * Closed codes the Android executor returns at fire time, repeated here because the dry run checks the same
 * things without the executor. Each value is the executor's own constant (an Android unit test compares them).
 */
object DryRunCodes {
    const val SHIZUKU_UNAVAILABLE = "shizuku_unavailable"
    const val SHELL_EXTERNAL_TRIGGER = "shell_external_trigger"
    const val UI_STEPS_EXTERNAL_TRIGGER = "ui_steps_external_trigger"
    const val REPLY_EVENT_UNVERIFIED = "reply_event_unverified"
    const val REPLY_PACKAGE_UNTRUSTED = "reply_package_untrusted"
    const val UNSUPPORTED_PHASE = "unsupported_phase"
    const val ASK_OUTSIDE_PROGRAM = "ask_outside_program"
    const val STORED_UNRESOLVED = "stored_unresolved"
    const val INVOKE_LLM_V2_UNSUPPORTED = "p4_invoke_llm_v2_unsupported"
    const val P4_NOT_YET_EXECUTABLE = "p4_not_yet_executable"
    const val TELEPHONY_UNAVAILABLE = "telephony_unavailable"
    const val ACTION_INVALID = "action_invalid"
    const val SMS_PERMISSION_MISSING = "sms_send_permission_missing"
    const val CALL_PERMISSION_MISSING = "call_permission_missing"
    const val HTTP_CLEARTEXT_SECRET = "http_cleartext_secret"
    const val HTTP_REQUEST_INVALID = "http_request_invalid"
    const val HTTP_SECRET_MISSING = "http_secret_missing"

    /** The dry run stopped itself after [DryRunSimulator.MAX_STEPS] steps. Never a code of a real run. */
    const val STEP_LIMIT = "dry_run_step_limit"
}

/**
 * The fire-time checks of the Android executor that need no device action: who triggered a shell command or a
 * macro, a ready privileged backend, the notification a reply goes to, telephony and intent rules, HTTP secret
 * rules. Each check calls the same policy function the executor calls, in the same order; none of them acts.
 * What only the device can answer by acting (a send, a rate cap that writes its ledger, the receivers of an
 * implicit intent, a budget) is not simulated.
 */
internal object DryRunLeafChecks {
    private val PRIVILEGED = setOf(
        Action.AppControl::class, Action.SetAirplane::class, Action.SetLocation::class, Action.SetNfc::class,
        Action.SetBatterySaver::class, Action.LockScreen::class, Action.Screenshot::class, Action.AppPermission::class,
    )

    /** The code that would block or fail [action] (already resolved), or null if it would run. */
    fun blockCode(action: Action, event: TriggerEvent, facts: DryRunFacts, program: Boolean): String? {
        if (program) {
            // Branches of the resolved-leaf executor before it falls back to the flat one.
            when (action) {
                is Action.InvokeLlmV2 -> return DryRunCodes.INVOKE_LLM_V2_UNSUPPORTED
                is Action.InvokeLlm, is Action.SetStored -> return null
                else -> Unit
            }
        }
        return when (action) {
            is Action.RunShell -> when {
                !StaticShellSafety.allows(event, facts.whitelistedConversationIds) -> DryRunCodes.SHELL_EXTERNAL_TRIGGER
                !facts.privilegedReady -> DryRunCodes.SHIZUKU_UNAVAILABLE
                else -> null
            }
            is Action.UiSteps ->
                if (!StaticShellSafety.allows(event, facts.whitelistedConversationIds)) {
                    DryRunCodes.UI_STEPS_EXTERNAL_TRIGGER
                } else {
                    null
                }
            is Action.Tap, is Action.InputText -> DryRunCodes.UNSUPPORTED_PHASE
            is Action.WhatsAppReply -> replyCode(event, ReplyChannels::isWhatsApp)
            is Action.NotificationReply -> gated(action) ?: replyCode(event, ReplyChannels::isNotificationReplyPackage)
            is Action.NotificationAction -> gated(action) ?: run {
                val notification = event as? TriggerEvent.NotificationPosted
                if (notification == null || notification.notificationKey.isNullOrBlank()) {
                    NotificationActionPolicy.CODE_EVENT_UNVERIFIED
                } else {
                    null
                }
            }
            is Action.HttpRequest -> gated(action) ?: httpCode(action, facts)
            is Action.SendSms -> gated(action) ?: when {
                !facts.hasTelephony -> DryRunCodes.TELEPHONY_UNAVAILABLE
                !TelephonyActionPolicy.isValidNumber(action.number) || action.text.isBlank() ||
                    action.text.length > TelephonyActionPolicy.MAX_SMS_CHARS -> DryRunCodes.ACTION_INVALID
                !facts.canSendSms -> DryRunCodes.SMS_PERMISSION_MISSING
                else -> null
            }
            is Action.CallNumber -> gated(action) ?: when {
                !facts.hasTelephony -> DryRunCodes.TELEPHONY_UNAVAILABLE
                !TelephonyActionPolicy.isValidNumber(action.number) -> DryRunCodes.ACTION_INVALID
                !facts.canCall -> DryRunCodes.CALL_PERMISSION_MISSING
                else -> null
            }
            is Action.SendIntent -> gated(action) ?: intentCode(action, facts)
            is Action.InvokeLlm -> gated(action)
            is Action.Ask -> DryRunCodes.ASK_OUTSIDE_PROGRAM
            is Action.SetStored -> DryRunCodes.STORED_UNRESOLVED
            is Action.Wait, is Action.If, is Action.While -> DryRunCodes.P4_NOT_YET_EXECUTABLE
            is Action.ScreenCall -> gated(action)
            else -> if (action::class in PRIVILEGED && !facts.privilegedReady) DryRunCodes.SHIZUKU_UNAVAILABLE else null
        }
    }

    private fun gated(action: Action): String? =
        if (FeatureGates.blocks(action)) FeatureGates.NOT_AVAILABLE_YET else null

    private fun replyCode(event: TriggerEvent, packageAllowed: (String) -> Boolean): String? {
        val notification = event as? TriggerEvent.NotificationPosted ?: return DryRunCodes.REPLY_EVENT_UNVERIFIED
        return if (packageAllowed(notification.pkg)) null else DryRunCodes.REPLY_PACKAGE_UNTRUSTED
    }

    /** As the HTTP executor before the first byte: cleartext secrets, the request shape, each secret by NAME. */
    private fun httpCode(action: Action.HttpRequest, facts: DryRunFacts): String? {
        val carriesSecrets = SecretPolicy.carriesSecrets(action)
        if (carriesSecrets && !HttpRequestPolicy.secretsAllowedTo(action.url)) return DryRunCodes.HTTP_CLEARTEXT_SECRET
        if (!HttpRequestPolicy.isResolvedShapeValid(action)) return DryRunCodes.HTTP_REQUEST_INVALID
        val used = action.secretHeaders.orEmpty().map { it.secret } + action.secretQuery.orEmpty().map { it.secret }
        if (used.any { it !in facts.secretNames }) return DryRunCodes.HTTP_SECRET_MISSING
        return null
    }

    private fun intentCode(action: Action.SendIntent, facts: DryRunFacts): String? {
        TelephonyActionPolicy.intentProblems(action, resolved = true).firstOrNull()?.let { return it }
        val own = facts.ownPackage
        fun isArgus(pkg: String) = TelephonyActionPolicy.isArgusName(pkg) ||
            (own != null && (pkg == own || pkg.startsWith("$own.")))
        if (action.cls?.let(::isArgus) == true || action.pkg?.let(::isArgus) == true) {
            return TelephonyActionPolicy.INTENT_TARGET_FORBIDDEN
        }
        if (action.kind == IntentKind.SERVICE && !facts.privilegedReady) return DryRunCodes.SHIZUKU_UNAVAILABLE
        return null
    }
}
