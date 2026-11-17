package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.argus.data.entities.StoredValueEntity
import dev.argus.data.entities.UserModeEntity
import dev.argus.data.entities.UserModeStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Valori conservati e modalità (0.8). Le scritture con i limiti e le transazioni passano da
 * `RoomStoredValueStore` e `RoomUserModeStore`.
 */
@Dao
interface StoredStateDao {
    // --- Valori conservati ---

    @Query("SELECT * FROM stored_values ORDER BY storedKey ASC")
    fun observeValues(): Flow<List<StoredValueEntity>>

    @Query("SELECT * FROM stored_values ORDER BY storedKey ASC")
    suspend fun allValues(): List<StoredValueEntity>

    @Query("SELECT * FROM stored_values WHERE storedKey IN (:keys)")
    suspend fun values(keys: List<String>): List<StoredValueEntity>

    @Query("SELECT * FROM stored_values WHERE storedKey = :key")
    suspend fun value(key: String): StoredValueEntity?

    @Query("SELECT COUNT(*) FROM stored_values")
    suspend fun valueCount(): Int

    @Upsert
    suspend fun upsertValue(entity: StoredValueEntity)

    @Query("DELETE FROM stored_values WHERE storedKey = :key")
    suspend fun deleteValue(key: String): Int

    @Query("DELETE FROM stored_values")
    suspend fun deleteAllValues()

    // --- Modalità ---

    @Query("SELECT * FROM user_modes ORDER BY position ASC, nameKey ASC")
    fun observeModes(): Flow<List<UserModeEntity>>

    @Query("SELECT * FROM user_modes ORDER BY position ASC, nameKey ASC")
    suspend fun allModes(): List<UserModeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMode(entity: UserModeEntity)

    @Query("DELETE FROM user_modes WHERE nameKey = :nameKey")
    suspend fun deleteMode(nameKey: String): Int

    @Query("DELETE FROM user_modes")
    suspend fun deleteAllModes()

    @Query("SELECT * FROM user_mode_state WHERE id = 0")
    fun observeModeState(): Flow<UserModeStateEntity?>

    @Query("SELECT * FROM user_mode_state WHERE id = 0")
    suspend fun modeState(): UserModeStateEntity?

    @Upsert
    suspend fun upsertModeState(entity: UserModeStateEntity)
}
