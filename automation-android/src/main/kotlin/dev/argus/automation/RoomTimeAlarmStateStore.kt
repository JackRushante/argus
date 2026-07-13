package dev.argus.automation

import androidx.room.withTransaction
import dev.argus.data.ArgusDatabase
import dev.argus.data.entities.ScheduledTimeAlarmEntity
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.TimePrecision

/** Adapter Room fail-closed per lo stato operativo dello scheduler. */
class RoomTimeAlarmStateStore(
    private val db: ArgusDatabase,
) : TimeAlarmStateStore {
    private val alarms = db.scheduledTimeAlarmDao()

    override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? = db.withTransaction {
        alarms.get(automationId.value)?.let { decodeOrQuarantine(it) }
    }

    override suspend fun all(): List<ScheduledTimeAlarm> = db.withTransaction {
        alarms.all().mapNotNull { decodeOrQuarantine(it) }
    }

    override suspend fun upsert(alarm: ScheduledTimeAlarm) {
        alarms.upsert(
            ScheduledTimeAlarmEntity(
                automationId = alarm.automationId.value,
                approvalFingerprint = alarm.approvalFingerprint.value,
                eventAtMillis = alarm.eventAtMillis,
                wakeAtMillis = alarm.wakeAtMillis,
                requestedPrecision = alarm.requestedPrecision.name,
                scheduledMode = alarm.scheduledMode.name,
                updatedAtMillis = alarm.updatedAtMillis,
            ),
        )
    }

    override suspend fun delete(automationId: AutomationId) {
        alarms.delete(automationId.value)
    }

    private suspend fun decodeOrQuarantine(row: ScheduledTimeAlarmEntity): ScheduledTimeAlarm? {
        val decoded = runCatching {
            require(row.automationId.isNotBlank()) { "automation_id_invalid" }
            ScheduledTimeAlarm(
                automationId = AutomationId(row.automationId),
                approvalFingerprint = ApprovalFingerprint(row.approvalFingerprint),
                eventAtMillis = row.eventAtMillis,
                wakeAtMillis = row.wakeAtMillis,
                requestedPrecision = TimePrecision.valueOf(row.requestedPrecision),
                scheduledMode = ScheduledAlarmMode.valueOf(row.scheduledMode),
                updatedAtMillis = row.updatedAtMillis,
            )
        }.getOrNull()
        if (decoded != null) return decoded

        alarms.delete(row.automationId)
        return null
    }
}
