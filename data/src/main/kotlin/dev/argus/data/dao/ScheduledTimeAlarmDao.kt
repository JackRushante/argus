package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.argus.data.entities.ScheduledTimeAlarmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTimeAlarmDao {
    @Query("SELECT * FROM scheduled_time_alarms WHERE automationId = :automationId")
    suspend fun get(automationId: String): ScheduledTimeAlarmEntity?

    @Query("SELECT * FROM scheduled_time_alarms ORDER BY automationId")
    suspend fun all(): List<ScheduledTimeAlarmEntity>

    /** Per la UI: il vero «prossimo scatto» dei ritardi relativi sta nello stato persistito. */
    @Query("SELECT * FROM scheduled_time_alarms ORDER BY automationId")
    fun observeAll(): Flow<List<ScheduledTimeAlarmEntity>>

    @Upsert
    suspend fun upsert(entity: ScheduledTimeAlarmEntity)

    @Query("DELETE FROM scheduled_time_alarms WHERE automationId = :automationId")
    suspend fun delete(automationId: String): Int

    @Query("SELECT COUNT(*) FROM scheduled_time_alarms")
    suspend fun count(): Int
}
