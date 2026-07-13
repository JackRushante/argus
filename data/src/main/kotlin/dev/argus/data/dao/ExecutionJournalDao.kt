package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.argus.data.entities.ActionResultEntity
import dev.argus.data.entities.FireClaimEntity
import dev.argus.engine.runtime.ExecutionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionJournalDao {
    @Upsert
    suspend fun upsertAction(entity: ActionResultEntity)

    @Query("SELECT status FROM fire_claims WHERE executionId = :executionId")
    suspend fun status(executionId: String): ExecutionStatus?

    @Transaction
    suspend fun upsertActionIfActive(entity: ActionResultEntity): Boolean {
        if (status(entity.executionId) !in setOf(ExecutionStatus.RUNNING, ExecutionStatus.SUBMITTED))
            return false
        upsertAction(entity)
        return true
    }

    @Query(
        "UPDATE fire_claims SET status = :status, completedAtMillis = :completedAtMillis, " +
            "succeededCount = :succeededCount, failedCount = :failedCount, " +
            "submittedCount = :submittedCount " +
            "WHERE executionId = :executionId AND (status IN ('RUNNING', 'SUBMITTED') OR " +
            "(status = :status AND completedAtMillis = :completedAtMillis " +
            "AND succeededCount = :succeededCount AND failedCount = :failedCount " +
            "AND submittedCount = :submittedCount))",
    )
    suspend fun finish(
        executionId: String,
        status: ExecutionStatus,
        completedAtMillis: Long,
        succeededCount: Int,
        failedCount: Int,
        submittedCount: Int,
    ): Int

    @Query("SELECT * FROM fire_claims WHERE executionId = :executionId")
    suspend fun execution(executionId: String): FireClaimEntity?

    @Query("SELECT * FROM action_results WHERE executionId = :executionId ORDER BY actionIndex ASC")
    suspend fun actions(executionId: String): List<ActionResultEntity>

    /**
     * Un'unica invalidation Room evita query N+1 senza caricare l'intera storia del journal.
     * Il limite è espresso in righe audit perché è lo stesso confine usato dalla timeline UI.
     */
    @Query(
        "SELECT * FROM action_results WHERE executionId IN (" +
            "SELECT executionId FROM audit WHERE executionId IS NOT NULL " +
            "ORDER BY atMillis DESC, id DESC LIMIT :auditLimit" +
            ") ORDER BY atMillis DESC, executionId ASC, actionIndex ASC",
    )
    fun observeRecentActions(auditLimit: Int): Flow<List<ActionResultEntity>>

    @Query(
        "SELECT * FROM action_results WHERE executionId IN (" +
            "SELECT executionId FROM audit WHERE automationId = :automationId " +
            "AND executionId IS NOT NULL " +
            "ORDER BY atMillis DESC, id DESC LIMIT :auditLimit" +
            ") ORDER BY atMillis DESC, executionId ASC, actionIndex ASC",
    )
    fun observeRecentActionsForAutomation(
        automationId: String,
        auditLimit: Int,
    ): Flow<List<ActionResultEntity>>

    @Query("SELECT COUNT(*) FROM fire_claims")
    suspend fun executionCount(): Int

    @Query("SELECT COUNT(*) FROM action_results")
    suspend fun actionCount(): Int

    @Query(
        "UPDATE fire_claims SET status = 'INTERRUPTED', completedAtMillis = :atMillis " +
            "WHERE status = 'RUNNING' AND claimedAtMillis < :staleBeforeMillis",
    )
    suspend fun interruptStale(staleBeforeMillis: Long, atMillis: Long): Int

    @Query(
        "DELETE FROM fire_claims WHERE status != 'RUNNING' AND (" +
            "claimedAtMillis < :olderThanMillis OR executionId NOT IN (" +
            "SELECT executionId FROM fire_claims ORDER BY claimedAtMillis DESC, executionId DESC LIMIT :maxRows))",
    )
    suspend fun trim(olderThanMillis: Long, maxRows: Int): Int
}
