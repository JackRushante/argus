package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import dev.argus.engine.runtime.ActionJournalOutcome

@Entity(
    tableName = "action_results",
    primaryKeys = ["executionId", "actionIndex"],
    foreignKeys = [
        ForeignKey(
            entity = FireClaimEntity::class,
            parentColumns = ["executionId"],
            childColumns = ["executionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("executionId"), Index("atMillis")],
)
data class ActionResultEntity(
    val executionId: String,
    val actionIndex: Int,
    val actionType: String,
    val outcome: ActionJournalOutcome,
    val atMillis: Long,
    val errorCode: String? = null,
)
