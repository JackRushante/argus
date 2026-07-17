package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.argus.data.entities.ActionResultEntity
import dev.argus.data.entities.FireClaimEntity
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.SubmittedActionCompletion
import dev.argus.engine.runtime.SubmittedActionState
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionJournalDao {
    @Upsert
    suspend fun upsertAction(entity: ActionResultEntity)

    @Query(
        "SELECT fire_claims.status AS executionStatus, " +
            "action_results.outcome AS actionOutcome FROM fire_claims " +
            "LEFT JOIN action_results ON action_results.executionId = fire_claims.executionId " +
            "AND action_results.actionIndex = :actionIndex " +
            "WHERE fire_claims.executionId = :executionId",
    )
    fun observeSubmission(
        executionId: String,
        actionIndex: Int,
    ): Flow<SubmittedActionState?>

    @Query(
        "SELECT fire_claims.status AS executionStatus, " +
            "action_results.outcome AS actionOutcome FROM fire_claims " +
            "LEFT JOIN action_results ON action_results.executionId = fire_claims.executionId " +
            "AND action_results.actionIndex = :actionIndex " +
            "WHERE fire_claims.executionId = :executionId",
    )
    suspend fun submission(executionId: String, actionIndex: Int): SubmittedActionState?

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
            "submittedCount = :submittedCount, deferredCount = :deferredCount " +
            "WHERE executionId = :executionId AND (status IN ('RUNNING', 'SUBMITTED') OR " +
            "(status = :status AND completedAtMillis = :completedAtMillis " +
            "AND succeededCount = :succeededCount AND failedCount = :failedCount " +
            "AND submittedCount = :submittedCount AND deferredCount = :deferredCount))",
    )
    suspend fun finish(
        executionId: String,
        status: ExecutionStatus,
        completedAtMillis: Long,
        succeededCount: Int,
        failedCount: Int,
        submittedCount: Int,
        deferredCount: Int,
    ): Int

    @Query(
        "UPDATE fire_claims SET status = :status, completedAtMillis = :completedAtMillis, " +
            "succeededCount = :succeededCount, failedCount = :failedCount, " +
            "submittedCount = :submittedCount, deferredCount = :deferredCount " +
            "WHERE executionId = :executionId AND status = 'SUBMITTED'",
    )
    suspend fun resolveExecution(
        executionId: String,
        status: ExecutionStatus,
        completedAtMillis: Long,
        succeededCount: Int,
        failedCount: Int,
        submittedCount: Int,
        deferredCount: Int,
    ): Int

    @Transaction
    suspend fun resolveSubmitted(completion: SubmittedActionCompletion): Boolean {
        val state = submission(completion.executionId.value, completion.actionIndex)
        if (state?.ready != true) return false
        val current = actions(completion.executionId.value)
            .firstOrNull { it.actionIndex == completion.actionIndex }
            ?: return false
        upsertAction(
            current.copy(
                outcome = completion.outcome,
                atMillis = completion.atMillis,
                errorCode = completion.errorCode,
            ),
        )
        val resolved = actions(completion.executionId.value)
        val succeeded = resolved.count { it.outcome == ActionJournalOutcome.SUCCEEDED }
        val failed = resolved.count { it.outcome == ActionJournalOutcome.FAILED }
        val submitted = resolved.count { it.outcome == ActionJournalOutcome.SUBMITTED }
        val deferred = resolved.count { it.outcome == ActionJournalOutcome.DEFERRED }
        val status = when {
            submitted > 0 -> ExecutionStatus.SUBMITTED
            completion.suppressedStatus != null && failed == resolved.size -> completion.suppressedStatus!!
            deferred > 0 -> ExecutionStatus.DEFERRED
            failed == resolved.size && resolved.isNotEmpty() -> ExecutionStatus.FAILED
            failed > 0 -> ExecutionStatus.PARTIAL
            else -> ExecutionStatus.SUCCEEDED
        }
        check(
            resolveExecution(
                executionId = completion.executionId.value,
                status = status,
                completedAtMillis = completion.atMillis,
                succeededCount = succeeded,
                failedCount = failed,
                submittedCount = submitted,
                deferredCount = deferred,
            ) == 1,
        ) { "Esecuzione async non più risolvibile" }
        return true
    }

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
            "WHERE status IN ('RUNNING', 'SUBMITTED') AND claimedAtMillis < :staleBeforeMillis",
    )
    suspend fun interruptStale(staleBeforeMillis: Long, atMillis: Long): Int

    @Query(
        "DELETE FROM fire_claims WHERE status NOT IN ('RUNNING', 'SUBMITTED') AND (" +
            "claimedAtMillis < :olderThanMillis OR executionId NOT IN (" +
            "SELECT executionId FROM fire_claims ORDER BY claimedAtMillis DESC, executionId DESC LIMIT :maxRows))",
    )
    suspend fun trim(olderThanMillis: Long, maxRows: Int): Int
}
