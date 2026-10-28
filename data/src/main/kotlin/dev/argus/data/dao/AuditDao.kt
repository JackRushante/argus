package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.argus.data.entities.AuditEntity
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionStatus
import kotlinx.coroutines.flow.Flow

data class AuditLogRecord(
    val id: Long,
    val automationId: String,
    val automationName: String?,
    val kind: AuditKind,
    val atMillis: Long,
    val detail: String,
    val executionId: String?,
    val executionStatus: ExecutionStatus?,
    val succeededCount: Int?,
    val failedCount: Int?,
    val submittedCount: Int?,
    val deferredCount: Int? = null,
)

@Dao
interface AuditDao {

    @Insert
    suspend fun insert(entity: AuditEntity): Long

    @Query("SELECT * FROM audit WHERE automationId = :automationId ORDER BY atMillis DESC, id DESC")
    suspend fun forAutomation(automationId: String): List<AuditEntity>

    @Query("SELECT * FROM audit ORDER BY atMillis DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditEntity>

    @Query(
        "SELECT audit.id AS id, audit.automationId AS automationId, " +
            "automations.name AS automationName, audit.kind AS kind, audit.atMillis AS atMillis, " +
            "audit.detail AS detail, audit.executionId AS executionId, " +
            "fire_claims.status AS executionStatus, " +
            "fire_claims.succeededCount AS succeededCount, " +
            "fire_claims.failedCount AS failedCount, " +
            "fire_claims.submittedCount AS submittedCount, " +
            "fire_claims.deferredCount AS deferredCount " +
            "FROM audit " +
            "LEFT JOIN automations ON automations.id = audit.automationId " +
            "LEFT JOIN fire_claims ON fire_claims.executionId = audit.executionId " +
            "ORDER BY audit.atMillis DESC, audit.id DESC LIMIT :limit",
    )
    fun observeLog(limit: Int): Flow<List<AuditLogRecord>>

    @Query(
        "SELECT audit.id AS id, audit.automationId AS automationId, " +
            "automations.name AS automationName, audit.kind AS kind, audit.atMillis AS atMillis, " +
            "audit.detail AS detail, audit.executionId AS executionId, " +
            "fire_claims.status AS executionStatus, " +
            "fire_claims.succeededCount AS succeededCount, " +
            "fire_claims.failedCount AS failedCount, " +
            "fire_claims.submittedCount AS submittedCount, " +
            "fire_claims.deferredCount AS deferredCount " +
            "FROM audit " +
            "LEFT JOIN automations ON automations.id = audit.automationId " +
            "LEFT JOIN fire_claims ON fire_claims.executionId = audit.executionId " +
            "WHERE audit.automationId = :automationId " +
            "ORDER BY audit.atMillis DESC, audit.id DESC LIMIT :limit",
    )
    fun observeLogForAutomation(automationId: String, limit: Int): Flow<List<AuditLogRecord>>

    /**
     * Le righe del log da [sinceMillis] in poi (0.7.0: «errori di oggi» nel riepilogo della lista). Stessa
     * proiezione di [observeLog]; il limite protegge da giornate con migliaia di righe.
     */
    @Query(
        "SELECT audit.id AS id, audit.automationId AS automationId, " +
            "automations.name AS automationName, audit.kind AS kind, audit.atMillis AS atMillis, " +
            "audit.detail AS detail, audit.executionId AS executionId, " +
            "fire_claims.status AS executionStatus, " +
            "fire_claims.succeededCount AS succeededCount, " +
            "fire_claims.failedCount AS failedCount, " +
            "fire_claims.submittedCount AS submittedCount, " +
            "fire_claims.deferredCount AS deferredCount " +
            "FROM audit " +
            "LEFT JOIN automations ON automations.id = audit.automationId " +
            "LEFT JOIN fire_claims ON fire_claims.executionId = audit.executionId " +
            "WHERE audit.atMillis >= :sinceMillis " +
            "ORDER BY audit.atMillis DESC, audit.id DESC LIMIT :limit",
    )
    fun observeLogSince(sinceMillis: Long, limit: Int): Flow<List<AuditLogRecord>>

    @Query(
        "DELETE FROM audit WHERE atMillis < :olderThanMillis OR id NOT IN (" +
            "SELECT id FROM audit ORDER BY atMillis DESC, id DESC LIMIT :maxRows)",
    )
    suspend fun trim(olderThanMillis: Long, maxRows: Int): Int

    /**
     * «Svuota il log»: tutto l'audit tranne le righe di esecuzioni ancora aperte o recuperabili
     * (RUNNING, SUBMITTED, DEFERRED). Le righe senza esecuzione (lifecycle, condizioni) vanno via.
     */
    @Query(
        "DELETE FROM audit WHERE executionId IS NULL OR executionId NOT IN (" +
            "SELECT executionId FROM fire_claims WHERE status IN ('RUNNING', 'SUBMITTED', 'DEFERRED'))",
    )
    suspend fun deleteExceptOpenExecutions(): Int
}
