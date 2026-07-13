package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

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
    ],
)
data class FireClaimEntity(
    val automationId: String,
    val eventIdHash: String,
    val executionId: String,
    val claimedAtMillis: Long,
)
