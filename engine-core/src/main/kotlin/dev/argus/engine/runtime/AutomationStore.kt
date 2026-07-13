package dev.argus.engine.runtime

import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ApprovalFingerprint
import kotlinx.coroutines.flow.Flow

data class FireClaimRequest(
    val automationId: AutomationId,
    val eventId: TriggerEventId,
    val executionId: ExecutionId,
    val atMillis: Long,
    val cooldownMs: Long,
) {
    init {
        require(atMillis >= 0) { "atMillis non può essere negativo" }
        require(cooldownMs >= 0) { "cooldownMs non può essere negativo" }
    }
}

sealed interface FireClaimResult {
    data object Claimed : FireClaimResult
    data class Duplicate(val existingExecutionId: ExecutionId) : FireClaimResult
    data class Cooldown(val retryAtMillis: Long) : FireClaimResult
    data object NotEligible : FireClaimResult
}

interface AutomationStore {
    suspend fun get(id: AutomationId): Automation?
    suspend fun all(): List<Automation>
    fun observeAll(): Flow<List<Automation>>
    suspend fun armed(): List<Automation>
    suspend fun delete(id: AutomationId)
    suspend fun disable(id: AutomationId)
    /** Disattiva solo se la regola è ancora ARMED e coincide con lo snapshot approvato indicato. */
    suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean
    /** Riattiva solo una regola ancora identica allo snapshot approvato. */
    suspend fun enable(id: AutomationId): Boolean
    suspend fun markNeedsReview(id: AutomationId)
    /** Quarantena solo la revisione ARMED ancora identica a quella verificata dal caller. */
    suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean

    /** Claim idempotente e check+update cooldown DEVONO essere una singola operazione atomica. */
    suspend fun claimFire(request: FireClaimRequest): FireClaimResult

    // Compatibilità lettura/manutenzione; l'Engine usa esclusivamente claimFire per gli scatti.
    suspend fun recordFired(id: AutomationId, atMillis: Long)
    suspend fun lastFiredAt(id: AutomationId): Long?
}
