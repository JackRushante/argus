package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.argus.data.entities.ObservedConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservedConversationDao {
    @Query(
        "SELECT * FROM observed_conversations " +
            "ORDER BY lastSeenAtMillis DESC, conversationId ASC LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<ObservedConversationEntity>

    @Query(
        "SELECT * FROM observed_conversations " +
            "ORDER BY lastSeenAtMillis DESC, conversationId ASC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<ObservedConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(conversation: ObservedConversationEntity): Long

    @Query(
        "UPDATE observed_conversations SET " +
            "packageName = :packageName, displayName = :displayName, " +
            "isGroup = :isGroup, lastSeenAtMillis = :lastSeenAtMillis " +
            "WHERE conversationId = :conversationId " +
            "AND lastSeenAtMillis <= :lastSeenAtMillis",
    )
    suspend fun updateIfNotOlder(
        conversationId: String,
        packageName: String,
        displayName: String,
        isGroup: Boolean?,
        lastSeenAtMillis: Long,
    )

    @Query(
        "DELETE FROM observed_conversations WHERE conversationId NOT IN (" +
            "SELECT conversationId FROM observed_conversations " +
            "ORDER BY lastSeenAtMillis DESC, conversationId ASC LIMIT :maximumRows)",
    )
    suspend fun trim(maximumRows: Int): Int

    @Transaction
    suspend fun record(conversation: ObservedConversationEntity, maximumRows: Int) {
        if (insertIfAbsent(conversation) == -1L) {
            updateIfNotOlder(
                conversationId = conversation.conversationId,
                packageName = conversation.packageName,
                displayName = conversation.displayName,
                isGroup = conversation.isGroup,
                lastSeenAtMillis = conversation.lastSeenAtMillis,
            )
        }
        trim(maximumRows)
    }
}
