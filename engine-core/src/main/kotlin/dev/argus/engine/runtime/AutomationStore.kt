package dev.argus.engine.runtime

import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
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
    suspend fun save(a: Automation)
    suspend fun delete(id: AutomationId)
    suspend fun disable(id: AutomationId)
    suspend fun markNeedsReview(id: AutomationId)

    /** Claim idempotente e check+update cooldown DEVONO essere una singola operazione atomica. */
    suspend fun claimFire(request: FireClaimRequest): FireClaimResult

    // Compatibilità lettura/manutenzione; l'Engine usa esclusivamente claimFire per gli scatti.
    suspend fun recordFired(id: AutomationId, atMillis: Long)
    suspend fun lastFiredAt(id: AutomationId): Long?
}
