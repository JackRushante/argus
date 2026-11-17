package dev.argus.automation.dryrun

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.argus.automation.telephony.AndroidTelephonySurface
import dev.argus.automation.telephony.ContactLookup
import dev.argus.engine.dryrun.DryRunFacts
import dev.argus.engine.dryrun.DryRunFactsSource
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.runtime.CallerScope
import dev.argus.engine.runtime.CallerScopeGate
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.PendingDraft
import kotlinx.coroutines.CancellationException

// =============================================================================
// 0.8 «Prova a secco»: what the dry run reads on Android. Every class here only READS (a rule, a draft, names,
// permissions, a contact lookup) and holds functions that read, never a handle to something that acts.
// =============================================================================

/** The rule a dry run simulates, and the trusted names of whitelisted chats for its rendering. */
class DryRunTarget(
    val automation: Automation,
    val conversationLabels: Map<String, String>,
) {
    /** The trusted name of the rule's own chat, if its trigger names a whitelisted conversation. */
    val conversationLabel: String?
        get() = (automation.trigger as? dev.argus.engine.model.Trigger.Notification)
            ?.conversationId?.let(conversationLabels::get)
}

/**
 * Finds the rule behind a detail route: a saved rule by id, or a draft (by its id, or by the rule it edits) as
 * the rule it would become. Same priority as the detail screen: a saved rule that is not a legacy pending one
 * stays the truth of its own detail.
 */
class DryRunRuleReader(
    private val automation: suspend (AutomationId) -> Automation?,
    private val drafts: suspend () -> List<PendingDraft>,
    private val conversationLabels: suspend () -> Map<String, String>,
) {
    suspend fun load(routeId: String): DryRunTarget? {
        val saved = automation(AutomationId(routeId))
        val pending = drafts()
        val rule = when {
            saved != null && saved.status != AutomationStatus.PENDING_APPROVAL -> saved
            else -> pending.firstOrNull { it.id.value == routeId }?.pendingAutomation()
                ?: pending.firstOrNull { it.automationId.value == routeId }?.pendingAutomation()
                ?: saved
        } ?: return null
        val labels = try {
            conversationLabels()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
        return DryRunTarget(rule, labels)
    }
}

/**
 * The facts the executor checks at fire time, read without acting: the whitelist, a ready privileged backend
 * (read from cached status, never a command), the NAMES of the secrets, telephony and its permissions, the app's
 * package. A fact that cannot be read is false or empty: the check it feeds fails closed.
 */
class AndroidDryRunFacts(
    context: Context,
    private val whitelistedIds: suspend () -> Set<String>,
    private val secretNames: suspend () -> Set<String>,
    private val privilegedReady: () -> Boolean,
) : DryRunFactsSource {
    private val appContext = context.applicationContext

    override suspend fun read(): DryRunFacts = DryRunFacts(
        whitelistedConversationIds = readOr(emptySet()) { whitelistedIds() },
        privilegedReady = runCatching(privilegedReady).getOrDefault(false),
        secretNames = readOr(emptySet()) { secretNames() },
        hasTelephony = AndroidTelephonySurface.hasTelephony(appContext),
        canSendSms = granted(Manifest.permission.SEND_SMS),
        canCall = granted(Manifest.permission.CALL_PHONE),
        ownPackage = appContext.packageName,
    )

    private fun granted(permission: String): Boolean = runCatching {
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private suspend fun <T> readOr(fallback: T, read: suspend () -> T): T = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        fallback
    }
}

/**
 * `screen_call` «only callers not in my contacts» in a dry run: the typed number is looked up in the contacts
 * (read only). A hidden number counts as unknown, as for a real call; a lookup that cannot answer is unverified,
 * so nothing runs. The real perimeter comes from the call-screening service and is consumed once per call: the
 * dry run never touches it.
 */
class ContactScopeGate(private val contacts: ContactLookup) : CallerScopeGate {
    override suspend fun closedCode(automation: Automation, event: TriggerEvent): String? {
        val number = (event as? TriggerEvent.PhoneStateChanged)?.number?.takeIf(String::isNotBlank)
            ?: return CallerScopeGate.codeFor(CallerScope.IN_SCOPE)
        val scope = when (runCatching { contacts.isContact(number) }.getOrNull()) {
            true -> CallerScope.KNOWN_CALLER
            false -> CallerScope.IN_SCOPE
            null -> CallerScope.UNVERIFIED
        }
        return CallerScopeGate.codeFor(scope)
    }
}
