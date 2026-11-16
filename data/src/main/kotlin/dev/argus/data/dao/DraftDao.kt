package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.argus.data.entities.PendingDraftEntity
import dev.argus.engine.model.CreatedBy
import kotlinx.coroutines.flow.Flow

/** Proiezione delle etichette di una bozza: [labels] è la colonna grezza, letta con `DraftLabels.decode`. */
data class DraftLabelsRow(val automationId: String, val labels: String)

@Dao
interface DraftDao {
    @Query("SELECT * FROM pending_drafts WHERE id = :id")
    suspend fun getById(id: String): PendingDraftEntity?

    @Query("SELECT * FROM pending_drafts WHERE automationId = :automationId")
    suspend fun getByAutomationId(automationId: String): PendingDraftEntity?

    @Query("SELECT * FROM pending_drafts ORDER BY updatedAtMillis DESC, id ASC")
    fun observeAll(): Flow<List<PendingDraftEntity>>

    /** 0.7.2: etichette delle bozze di regole nuove (colonna `labels`, una per riga). */
    @Query("SELECT automationId, labels FROM pending_drafts WHERE labels IS NOT NULL ORDER BY automationId ASC")
    fun observeLabels(): Flow<List<DraftLabelsRow>>

    @Query("SELECT automationId, labels FROM pending_drafts WHERE labels IS NOT NULL ORDER BY automationId ASC")
    suspend fun allLabels(): List<DraftLabelsRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PendingDraftEntity): Long

    @Query(
        "UPDATE pending_drafts SET name = :name, revision = revision + 1, " +
            "fingerprint = :fingerprint, createdBy = :createdBy, priority = :priority, " +
            "schemaVersion = :schemaVersion, updatedAtMillis = :updatedAtMillis, " +
            "quarantineCode = NULL, draftJson = :draftJson " +
            "WHERE id = :id AND revision = :expectedRevision AND quarantineCode IS NULL",
    )
    suspend fun revise(
        id: String,
        expectedRevision: Long,
        name: String,
        fingerprint: String,
        createdBy: CreatedBy,
        priority: Int,
        schemaVersion: Int,
        updatedAtMillis: Long,
        draftJson: String,
    ): Int

    @Query(
        "UPDATE pending_drafts SET quarantineCode = :code " +
            "WHERE id = :id AND quarantineCode IS NULL",
    )
    suspend fun quarantine(id: String, code: String): Int

    @Query("DELETE FROM pending_drafts WHERE id = :id AND revision = :expectedRevision")
    suspend fun delete(id: String, expectedRevision: Long): Int
}
