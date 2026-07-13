package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.argus.engine.model.CreatedBy

@Entity(
    tableName = "pending_drafts",
    indices = [
        Index(value = ["automationId"], unique = true),
        Index(value = ["updatedAtMillis"]),
    ],
)
data class PendingDraftEntity(
    @PrimaryKey val id: String,
    val automationId: String,
    val name: String,
    val revision: Long,
    val fingerprint: String,
    val createdBy: CreatedBy,
    val priority: Int,
    val schemaVersion: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val quarantineCode: String? = null,
    val draftJson: String,
)
