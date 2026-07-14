package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "whitelisted_contacts",
    indices = [Index("displayName")],
)
data class WhitelistedContactEntity(
    @PrimaryKey val conversationId: String,
    val displayName: String,
)
