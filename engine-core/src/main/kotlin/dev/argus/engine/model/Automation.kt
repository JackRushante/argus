@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.argus.engine.model
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class AutomationId(val value: String)
enum class CreatedBy { LLM, USER }
enum class AutomationStatus { PENDING_APPROVAL, ARMED, DISABLED, NEEDS_REVIEW }

const val AUTOMATION_SCHEMA_VERSION_V1 = 1
const val AUTOMATION_SCHEMA_VERSION_P4 = 2

/** Contratto indipendente dal materiale fingerprint e dal protocollo bridge. */
object AutomationSchema {
    val supportedVersions: Set<Int> = setOf(AUTOMATION_SCHEMA_VERSION_V1, AUTOMATION_SCHEMA_VERSION_P4)

    fun versionFor(draft: AutomationDraft): Int =
        if (requiresP4(draft.vars, draft.actions, draft.conditions)) AUTOMATION_SCHEMA_VERSION_P4
        else AUTOMATION_SCHEMA_VERSION_V1

    fun isCompatible(automation: Automation): Boolean {
        val needsP4 = requiresP4(automation.vars, automation.actions, automation.conditions)
        return when (automation.schemaVersion) {
            AUTOMATION_SCHEMA_VERSION_V1 -> !needsP4
            AUTOMATION_SCHEMA_VERSION_P4 -> needsP4
            else -> false
        }
    }

    fun isSupportedVersion(version: Int): Boolean = version in supportedVersions

    fun requiresP4(automation: Automation): Boolean =
        requiresP4(automation.vars, automation.actions, automation.conditions)

    private fun requiresP4(
        vars: List<VarBinding>,
        actions: List<Action>,
        conditions: Condition?,
    ): Boolean {
        if (vars.isNotEmpty() || conditions?.containsP4Condition() == true) return true
        val pending = ArrayDeque<Action>()
        actions.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            when (val action = pending.removeFirst()) {
                is Action.If -> {
                    return true
                }
                is Action.While -> {
                    return true
                }
                is Action.RunShell -> if (action.captureAs != null) return true
                is Action.InvokeLlm -> if (action.captureAs != null) return true
                is Action.InvokeLlmV2 -> if (action.captureAs != null) return true
                else -> Unit
            }
        }
        return false
    }

    private fun Condition.containsP4Condition(): Boolean {
        val pending = ArrayDeque<Condition>()
        pending.addLast(this)
        while (pending.isNotEmpty()) {
            when (val condition = pending.removeFirst()) {
                is Condition.VarCompare, is Condition.BooleanLiteral -> return true
                is Condition.And -> condition.all.forEach(pending::addLast)
                is Condition.Or -> condition.any.forEach(pending::addLast)
                is Condition.Not -> pending.addLast(condition.cond)
                else -> Unit
            }
        }
        return false
    }
}

@Serializable
data class Automation(
    val id: AutomationId,
    val name: String,
    val createdBy: CreatedBy,
    val status: AutomationStatus,
    val trigger: Trigger,
    val actions: List<Action>,
    /**
     * Variabili di programma dichiarate e approvate (P4 §2.2). @EncodeDefault(NEVER) + default
     * emptyList: una regola SENZA feature P4 serializza byte-per-byte come prima (vincolo
     * fingerprint non negoziabile), mentre le regole P4 la emettono e la includono nel fingerprint.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val vars: List<VarBinding> = emptyList(),
    val conditions: Condition? = null,
    val enabled: Boolean = true,
    /** Nello stesso batch: esecuzione in ordine CRESCENTE → il più prioritario scrive ultimo e vince (spec §5). */
    val priority: Int = 0,
    val cooldownMs: Long = 0,
    val schemaVersion: Int = AUTOMATION_SCHEMA_VERSION_V1,
    /** Snapshot derivato e fingerprintato di trigger, condizioni, azioni e variabili richiesti a runtime. */
    val requiredCapabilities: Set<String> = CapabilityRequirements.derive(trigger, actions, conditions, vars),
    /** Hash dei dati eseguibili approvati; ogni edit lo rende non più corrispondente. */
    val approvalFingerprint: ApprovalFingerprint? = null,
)

/** Bozza proposta dall'LLM: come Automation ma senza id/status (li assegna l'app all'approvazione). */
@Serializable
data class AutomationDraft(
    val name: String,
    val trigger: Trigger,
    val actions: List<Action>,
    /** Variabili dichiarate dal draft (P4 §2.2). @EncodeDefault(NEVER): un draft senza feature P4
     *  resta byte-identico a prima. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val vars: List<VarBinding> = emptyList(),
    val conditions: Condition? = null,
    val rationale: String = "",
    val cooldownMs: Long = 0,
)
