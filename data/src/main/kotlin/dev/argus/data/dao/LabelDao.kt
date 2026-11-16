package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.argus.data.entities.AutomationLabelEntity
import kotlinx.coroutines.flow.Flow

/** Etichette delle regole salvate (0.7.2). Le scritture con i limiti passano da `RoomRuleLabelStore`. */
@Dao
interface LabelDao {
    @Query("SELECT * FROM automation_labels ORDER BY automationId ASC, addedAtMillis ASC, labelKey ASC")
    fun observeAll(): Flow<List<AutomationLabelEntity>>

    @Query("SELECT * FROM automation_labels ORDER BY automationId ASC, addedAtMillis ASC, labelKey ASC")
    suspend fun all(): List<AutomationLabelEntity>

    @Query("SELECT labelKey FROM automation_labels WHERE automationId = :automationId")
    suspend fun keysOf(automationId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AutomationLabelEntity): Long

    @Query("DELETE FROM automation_labels WHERE automationId = :automationId AND labelKey = :labelKey")
    suspend fun delete(automationId: String, labelKey: String): Int
}
