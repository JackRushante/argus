package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.argus.data.entities.WhitelistedContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactWhitelistDao {
    @Query("SELECT * FROM whitelisted_contacts ORDER BY displayName COLLATE NOCASE, conversationId")
    suspend fun all(): List<WhitelistedContactEntity>

    @Query("SELECT * FROM whitelisted_contacts ORDER BY displayName COLLATE NOCASE, conversationId")
    fun observeAll(): Flow<List<WhitelistedContactEntity>>

    @Query("SELECT conversationId FROM whitelisted_contacts ORDER BY conversationId")
    suspend fun conversationIds(): List<String>

    @Upsert
    suspend fun upsert(contact: WhitelistedContactEntity)

    @Query("DELETE FROM whitelisted_contacts WHERE conversationId = :conversationId")
    suspend fun remove(conversationId: String): Int
}
