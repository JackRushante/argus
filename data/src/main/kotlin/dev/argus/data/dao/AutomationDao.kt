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
import dev.argus.engine.runtime.FireClaimResult

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

    /**
     * Solo ARMED + enabled, in priorità CRESCENTE (spec §5: il più prioritario esegue ultimo e vince).
     * Il literal 'ARMED' combacia con [AutomationStatus.ARMED]`.name` scritto dal converter.
     */
    @Query("SELECT * FROM automations WHERE status = 'ARMED' AND enabled = 1 ORDER BY priority ASC, id ASC")
    suspend fun armed(): List<AutomationEntity>

    @Upsert
    suspend fun upsert(entity: AutomationEntity)

    /** Evita il lost update fra edit/save e claim del cooldown. */
    @Transaction
    suspend fun upsertPreservingLastFired(entity: AutomationEntity) {
        upsert(entity.copy(lastFiredAt = lastFiredAt(entity.id)))
    }

    /** Aggiorna solo lo status (il converter applica l'enum al bind param). */
    @Query("UPDATE automations SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: AutomationStatus)

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
            return FireClaimResult.Cooldown(retryAt)
        }
        return FireClaimResult.NotEligible
    }
}
