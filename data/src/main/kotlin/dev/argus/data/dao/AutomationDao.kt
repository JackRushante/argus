package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.argus.data.entities.AutomationEntity
import dev.argus.data.entities.FireClaimEntity
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireClaimResult
import kotlinx.coroutines.flow.Flow

data class AutomationRuntimeState(
    val status: AutomationStatus,
    val enabled: Boolean,
    val lastFiredAt: Long?,
)

@Dao
interface AutomationDao {

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Query("SELECT * FROM automations ORDER BY priority ASC, id ASC")
    suspend fun getAll(): List<AutomationEntity>

    @Query("SELECT * FROM automations ORDER BY priority ASC, id ASC")
    fun observeAll(): Flow<List<AutomationEntity>>

    /**
     * Solo ARMED + enabled, in priorità CRESCENTE (spec §5: il più prioritario esegue ultimo e vince).
     * Il literal 'ARMED' combacia con [AutomationStatus.ARMED]`.name` scritto dal converter.
     */
    @Query("SELECT * FROM automations WHERE status = 'ARMED' AND enabled = 1 ORDER BY priority ASC, id ASC")
    suspend fun armed(): List<AutomationEntity>

    @Upsert
    suspend fun upsert(entity: AutomationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAutomation(entity: AutomationEntity): Long

    /** Evita il lost update fra edit/save e claim del cooldown. */
    @Transaction
    suspend fun upsertPreservingLastFired(entity: AutomationEntity) {
        upsert(entity.copy(lastFiredAt = lastFiredAt(entity.id)))
    }

    @Query(
        "UPDATE automations SET status = 'DISABLED', enabled = 0 " +
            "WHERE id = :id AND status IN ('ARMED', 'PENDING_APPROVAL')",
    )
    suspend fun disable(id: String): Int

    @Query(
        "UPDATE automations SET status = 'DISABLED', enabled = 0 " +
            "WHERE id = :id AND status = 'ARMED' AND enabled = 1 " +
            "AND json = :expectedJson AND name = :expectedName AND priority = :expectedPriority " +
            "AND cooldownMs = :expectedCooldownMs AND schemaVersion = :expectedSchemaVersion",
    )
    suspend fun disableIfUnchanged(
        id: String,
        expectedJson: String,
        expectedName: String,
        expectedPriority: Int,
        expectedCooldownMs: Long,
        expectedSchemaVersion: Int,
    ): Int

    @Query(
        "UPDATE automations SET status = 'ARMED', enabled = 1 " +
            "WHERE id = :id AND status = 'DISABLED' AND enabled = 0 " +
            "AND json = :expectedJson AND name = :expectedName AND priority = :expectedPriority " +
            "AND cooldownMs = :expectedCooldownMs AND schemaVersion = :expectedSchemaVersion",
    )
    suspend fun enableIfUnchanged(
        id: String,
        expectedJson: String,
        expectedName: String,
        expectedPriority: Int,
        expectedCooldownMs: Long,
        expectedSchemaVersion: Int,
    ): Int

    @Query(
        "UPDATE automations SET status = 'PENDING_APPROVAL', enabled = 0 " +
            "WHERE id = :id AND status = :expectedStatus AND enabled = :expectedEnabled " +
            "AND json = :expectedJson AND name = :expectedName AND priority = :expectedPriority " +
            "AND cooldownMs = :expectedCooldownMs AND schemaVersion = :expectedSchemaVersion",
    )
    suspend fun pauseForReviewIfUnchanged(
        id: String,
        expectedStatus: AutomationStatus,
        expectedEnabled: Boolean,
        expectedJson: String,
        expectedName: String,
        expectedPriority: Int,
        expectedCooldownMs: Long,
        expectedSchemaVersion: Int,
    ): Int

    /** Annullare un edit non ri-arma implicitamente la vecchia versione. */
    @Query(
        "UPDATE automations SET status = 'DISABLED', enabled = 0 " +
            "WHERE id = :id AND status = 'PENDING_APPROVAL' AND enabled = 0",
    )
    suspend fun cancelPendingReview(id: String): Int

    @Query(
        "UPDATE automations SET status = 'NEEDS_REVIEW', enabled = 0 " +
            "WHERE id = :id AND (status != 'NEEDS_REVIEW' OR enabled != 0)",
    )
    suspend fun markNeedsReview(id: String): Int

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Registra l'ultimo scatto (cooldown); non tocca [AutomationEntity.json]. */
    @Query("UPDATE automations SET lastFiredAt = :atMillis WHERE id = :id")
    suspend fun recordFired(id: String, atMillis: Long)

    @Query("SELECT lastFiredAt FROM automations WHERE id = :id")
    suspend fun lastFiredAt(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFireClaim(entity: FireClaimEntity): Long

    @Query(
        "SELECT executionId FROM fire_claims " +
            "WHERE automationId = :automationId AND eventIdHash = :eventIdHash",
    )
    suspend fun claimedExecutionId(automationId: String, eventIdHash: String): String?

    @Query("SELECT COUNT(*) FROM fire_claims WHERE automationId = :automationId")
    suspend fun claimCount(automationId: String): Int

    @Query(
        "UPDATE automations SET lastFiredAt = :atMillis " +
            "WHERE id = :automationId AND status = 'ARMED' AND enabled = 1 AND (" +
            ":cooldownMs = 0 OR lastFiredAt IS NULL OR " +
            "(:atMillis > lastFiredAt AND :atMillis - lastFiredAt >= :cooldownMs))",
    )
    suspend fun acquireCooldown(
        automationId: String,
        atMillis: Long,
        cooldownMs: Long,
    ): Int

    @Query(
        "UPDATE fire_claims SET status = :status, completedAtMillis = :atMillis " +
            "WHERE executionId = :executionId AND status = 'RUNNING'",
    )
    suspend fun finishSuppressedClaim(
        executionId: String,
        status: ExecutionStatus,
        atMillis: Long,
    ): Int

    @Query("SELECT status, enabled, lastFiredAt FROM automations WHERE id = :id")
    suspend fun runtimeState(id: String): AutomationRuntimeState?

    /** Deduplica evento e acquisisce il cooldown nella stessa transazione Room. */
    @Transaction
    suspend fun claimFire(
        claim: FireClaimEntity,
        atMillis: Long,
        cooldownMs: Long,
    ): FireClaimResult {
        if (runtimeState(claim.automationId) == null) return FireClaimResult.NotEligible

        if (insertFireClaim(claim) == -1L) {
            val existing = requireNotNull(claimedExecutionId(claim.automationId, claim.eventIdHash)) {
                "Collisione executionId senza claim corrispondente"
            }
            return FireClaimResult.Duplicate(ExecutionId(existing))
        }

        if (acquireCooldown(claim.automationId, atMillis, cooldownMs) == 1)
            return FireClaimResult.Claimed

        val current = runtimeState(claim.automationId)
        if (current?.status == AutomationStatus.ARMED && current.enabled && current.lastFiredAt != null) {
            val retryAt = if (current.lastFiredAt > Long.MAX_VALUE - cooldownMs) Long.MAX_VALUE
            else current.lastFiredAt + cooldownMs
            check(
                finishSuppressedClaim(
                    claim.executionId,
                    ExecutionStatus.SUPPRESSED_COOLDOWN,
                    atMillis,
                ) == 1,
            ) { "Claim cooldown non finalizzato" }
            return FireClaimResult.Cooldown(retryAt)
        }
        check(
            finishSuppressedClaim(
                claim.executionId,
                ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE,
                atMillis,
            ) == 1,
        ) { "Claim non eleggibile non finalizzato" }
        return FireClaimResult.NotEligible
    }
}
