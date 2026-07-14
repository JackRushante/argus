package dev.argus.engine.model
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class AutomationId(val value: String)
enum class CreatedBy { LLM, USER }
enum class AutomationStatus { PENDING_APPROVAL, ARMED, DISABLED, NEEDS_REVIEW }

const val SCHEMA_VERSION = 1

@Serializable
data class Automation(
    val id: AutomationId,
    val name: String,
    val createdBy: CreatedBy,
    val status: AutomationStatus,
    val trigger: Trigger,
    val actions: List<Action>,
    val conditions: Condition? = null,
    val enabled: Boolean = true,
    /** Nello stesso batch: esecuzione in ordine CRESCENTE → il più prioritario scrive ultimo e vince (spec §5). */
    val priority: Int = 0,
    val cooldownMs: Long = 0,
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Snapshot derivato e fingerprintato di trigger, condizioni e azioni richiesti a runtime. */
    val requiredCapabilities: Set<String> = CapabilityRequirements.derive(trigger, actions, conditions),
    /** Hash dei dati eseguibili approvati; ogni edit lo rende non più corrispondente. */
    val approvalFingerprint: ApprovalFingerprint? = null,
)

/** Bozza proposta dall'LLM: come Automation ma senza id/status (li assegna l'app all'approvazione). */
@Serializable
data class AutomationDraft(
    val name: String,
    val trigger: Trigger,
    val actions: List<Action>,
    val conditions: Condition? = null,
    val rationale: String = "",
    val cooldownMs: Long = 0,
)
