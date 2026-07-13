package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.argus.data.entities.AuditEntity

@Dao
interface AuditDao {

    @Insert
    suspend fun insert(entity: AuditEntity): Long

    @Query("SELECT * FROM audit WHERE automationId = :automationId ORDER BY atMillis DESC, id DESC")
    suspend fun forAutomation(automationId: String): List<AuditEntity>

    @Query("SELECT * FROM audit ORDER BY atMillis DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditEntity>

    @Query(
        "DELETE FROM audit WHERE atMillis < :olderThanMillis OR id NOT IN (" +
            "SELECT id FROM audit ORDER BY atMillis DESC, id DESC LIMIT :maxRows)",
    )
    suspend fun trim(olderThanMillis: Long, maxRows: Int): Int
}
