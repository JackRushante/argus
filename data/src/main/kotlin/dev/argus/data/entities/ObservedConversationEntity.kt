package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "observed_conversations",
    indices = [Index("lastSeenAtMillis")],
)
data class ObservedConversationEntity(
    @PrimaryKey val conversationId: String,
    val packageName: String,
    val displayName: String,
    val isGroup: Boolean?,
    val lastSeenAtMillis: Long,
)
