package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
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
    indices = [
        Index("executionId"),
        Index("atMillis"),
        Index(value = ["executionId", "actionPath"], unique = true),
    ],
)
data class ActionResultEntity(
    val executionId: String,
    val actionIndex: Int,
    val actionType: String,
    val outcome: ActionJournalOutcome,
    val atMillis: Long,
    val errorCode: String? = null,
    /** Path strutturale P4; le righe migrate da v10 usano l'indice one-based. */
    @ColumnInfo(defaultValue = "''")
    val actionPath: String = (actionIndex + 1).toString(),
)
