package dev.argus.data

import androidx.room.TypeConverter
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.data.entities.UsageEventKind
import dev.argus.data.entities.UsageEventOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.ExecutionStatus

/**
 * Enum <-> TEXT. Gli enum sono persistiti col loro [Enum.name] così che le query con literal
 * (es. `WHERE status = 'ARMED'`) combacino con il valore memorizzato.
 */
class Converters {
    @TypeConverter fun statusToString(s: AutomationStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): AutomationStatus = AutomationStatus.valueOf(s)

    @TypeConverter fun createdByToString(value: CreatedBy): String = value.name
    @TypeConverter fun stringToCreatedBy(value: String): CreatedBy = CreatedBy.valueOf(value)

    @TypeConverter fun auditKindToString(k: AuditKind): String = k.name
    @TypeConverter fun stringToAuditKind(s: String): AuditKind = AuditKind.valueOf(s)

    @TypeConverter fun executionStatusToString(value: ExecutionStatus): String = value.name
    @TypeConverter fun stringToExecutionStatus(value: String): ExecutionStatus = ExecutionStatus.valueOf(value)

    @TypeConverter fun actionOutcomeToString(value: ActionJournalOutcome): String = value.name
    @TypeConverter fun stringToActionOutcome(value: String): ActionJournalOutcome =
        ActionJournalOutcome.valueOf(value)

    @TypeConverter fun usageKindToString(value: UsageEventKind): String = value.name
    @TypeConverter fun stringToUsageKind(value: String): UsageEventKind = UsageEventKind.valueOf(value)

    @TypeConverter fun usageOutcomeToString(value: UsageEventOutcome): String = value.name
    @TypeConverter fun stringToUsageOutcome(value: String): UsageEventOutcome =
        UsageEventOutcome.valueOf(value)
}
