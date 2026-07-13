package dev.argus.engine.safety

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.SCHEMA_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@JvmInline
value class DraftId(val value: String) {
    init {
        require(value.isNotBlank()) { "DraftId non può essere vuoto" }
    }
}

/** Snapshot immutabile mostrato in approvazione. La revisione parte da 1. */
data class PendingDraft(
    val id: DraftId,
    val automationId: AutomationId,
    val revision: Long,
    val fingerprint: ApprovalFingerprint,
    val draft: AutomationDraft,
    val createdBy: CreatedBy,
    val priority: Int,
    val schemaVersion: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Versione approvata sostituita da questo draft; null per una nuova automazione. */
    val baseAutomationFingerprint: ApprovalFingerprint? = null,
    val integrityError: String? = null,
) {
    init {
        require(revision >= 1) { "La revisione draft deve essere positiva" }
    }

    fun pendingAutomation(): Automation = Automation(
        id = automationId,
        name = draft.name,
        createdBy = createdBy,
        status = AutomationStatus.PENDING_APPROVAL,
        trigger = draft.trigger,
        actions = draft.actions,
        conditions = draft.conditions,
        enabled = false,
        priority = priority,
        cooldownMs = draft.cooldownMs,
        schemaVersion = schemaVersion,
        approvalFingerprint = null,
    )

    fun armedAutomation(): Automation = pendingAutomation().copy(
        status = AutomationStatus.ARMED,
        enabled = true,
        approvalFingerprint = fingerprint,
    )

    fun hasValidFingerprint(): Boolean =
        integrityError == null && schemaVersion == SCHEMA_VERSION &&
            fingerprint == ApprovalFingerprints.of(pendingAutomation())
}

data class NewDraft(
    val id: DraftId,
    val automationId: AutomationId,
    val draft: AutomationDraft,
    val createdBy: CreatedBy = CreatedBy.LLM,
    val priority: Int = 0,
    val atMillis: Long,
    /** Obbligatorio per modificare una regola già approvata; null crea un nuovo id. */
    val expectedAutomationFingerprint: ApprovalFingerprint? = null,
)

sealed interface DraftWriteResult {
    data class Saved(val snapshot: PendingDraft) : DraftWriteResult
    data class Conflict(val currentRevision: Long?) : DraftWriteResult
    data class Rejected(val code: String) : DraftWriteResult
}

sealed interface DraftDeleteResult {
    data object Deleted : DraftDeleteResult
    data object Missing : DraftDeleteResult
    data class Stale(val currentRevision: Long) : DraftDeleteResult
}

sealed interface DraftArmResult {
    data class Armed(val automation: Automation) : DraftArmResult
    data object Missing : DraftArmResult
    data class Stale(val currentRevision: Long) : DraftArmResult
    data object IntegrityFailure : DraftArmResult
    data object AutomationConflict : DraftArmResult
}

interface DraftRepository {
    suspend fun create(newDraft: NewDraft): DraftWriteResult

    suspend fun revise(
        id: DraftId,
        expectedRevision: Long,
        draft: AutomationDraft,
        priority: Int,
        atMillis: Long,
    ): DraftWriteResult

    suspend fun get(id: DraftId): PendingDraft?
    fun observeAll(): Flow<List<PendingDraft>>
    suspend fun delete(id: DraftId, expectedRevision: Long): DraftDeleteResult

    suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): DraftArmResult
}

data class DraftReview(
    val snapshot: PendingDraft,
    val issues: List<ValidationIssue>,
) {
    val canArm: Boolean = issues.none { it.severity == Severity.ERROR }
}

sealed interface ApprovalResult {
    data class Armed(val automation: Automation) : ApprovalResult
    data object Missing : ApprovalResult
    data class Stale(val currentRevision: Long) : ApprovalResult
    data class ValidationFailed(val issues: List<ValidationIssue>) : ApprovalResult
    data object PolicyUnavailable : ApprovalResult
    data object IntegrityFailure : ApprovalResult
    data object AutomationConflict : ApprovalResult
}

fun interface ApprovalWhitelistProvider {
    suspend fun currentConversationIds(): Set<String>
}

/** Boundary applicativo: rivalida lo snapshot esatto e delega l'arm atomico al repository. */
class ApprovalService(
    private val repository: DraftRepository,
    private val validator: DraftValidator,
    private val whitelistProvider: ApprovalWhitelistProvider,
) {
    suspend fun review(id: DraftId): DraftReview? {
        val snapshot = repository.get(id) ?: return null
        if (!snapshot.hasValidFingerprint()) return DraftReview(
            snapshot,
            listOf(ValidationIssue(Severity.ERROR, "approval_integrity_failure", "Bozza non integra")),
        )
        val whitelist = currentWhitelist() ?: return DraftReview(
            snapshot,
            listOf(ValidationIssue(Severity.ERROR, "approval_policy_unavailable", "Policy non disponibile")),
        )
        return DraftReview(snapshot, validator.validate(snapshot.draft, whitelist))
    }

    suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): ApprovalResult {
        val snapshot = repository.get(id) ?: return ApprovalResult.Missing
        if (snapshot.revision != expectedRevision) return ApprovalResult.Stale(snapshot.revision)
        if (snapshot.fingerprint != expectedFingerprint || !snapshot.hasValidFingerprint())
            return ApprovalResult.IntegrityFailure

        val whitelist = currentWhitelist() ?: return ApprovalResult.PolicyUnavailable
        val issues = validator.validate(snapshot.draft, whitelist)
        if (issues.any { it.severity == Severity.ERROR }) return ApprovalResult.ValidationFailed(issues)

        return when (val result = repository.arm(id, expectedRevision, expectedFingerprint)) {
            is DraftArmResult.Armed -> ApprovalResult.Armed(result.automation)
            DraftArmResult.Missing -> ApprovalResult.Missing
            is DraftArmResult.Stale -> ApprovalResult.Stale(result.currentRevision)
            DraftArmResult.IntegrityFailure -> ApprovalResult.IntegrityFailure
            DraftArmResult.AutomationConflict -> ApprovalResult.AutomationConflict
        }
    }

    private suspend fun currentWhitelist(): Set<String>? = try {
        whitelistProvider.currentConversationIds()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
