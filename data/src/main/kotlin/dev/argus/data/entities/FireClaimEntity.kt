package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import dev.argus.engine.runtime.ExecutionStatus

/**
 * Deduplica persistente. [eventIdHash] è un digest: notification key/contact id non finiscono
 * mai in chiaro nel DB. La PK composta rende atomica la redelivery per automazione.
 */
@Entity(
    tableName = "fire_claims",
    primaryKeys = ["automationId", "eventIdHash"],
    foreignKeys = [
        ForeignKey(
            entity = AutomationEntity::class,
            parentColumns = ["id"],
            childColumns = ["automationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["executionId"], unique = true),
        Index(value = ["claimedAtMillis"]),
        Index(value = ["status"]),
        Index(value = ["completedAtMillis"]),
    ],
)
data class FireClaimEntity(
    val automationId: String,
    val eventIdHash: String,
    val executionId: String,
    val claimedAtMillis: Long,
    /** Le righe pre-v4 diventano INTERRUPTED; i nuovi claim passano sempre RUNNING esplicito. */
    @ColumnInfo(defaultValue = "'INTERRUPTED'")
    val status: ExecutionStatus = ExecutionStatus.RUNNING,
    val completedAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val succeededCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val failedCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val submittedCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val deferredCount: Int = 0,
)
