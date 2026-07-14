package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stato operativo di AlarmManager. Nessuna FK intenzionale: dopo la cancellazione di una
 * automazione il record deve sopravvivere finché la reconciliation cancella il PendingIntent OS.
 */
@Entity(tableName = "scheduled_time_alarms")
data class ScheduledTimeAlarmEntity(
    @PrimaryKey val automationId: String,
    val approvalFingerprint: String,
    val eventAtMillis: Long,
    val wakeAtMillis: Long,
    val requestedPrecision: String,
    val scheduledMode: String,
    val updatedAtMillis: Long,
)
