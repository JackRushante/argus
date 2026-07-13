package dev.argus.data

import androidx.room.TypeConverter
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.runtime.AuditKind

/**
 * Enum <-> TEXT. Gli enum sono persistiti col loro [Enum.name] così che le query con literal
 * (es. `WHERE status = 'ARMED'`) combacino con il valore memorizzato.
 */
class Converters {
    @TypeConverter fun statusToString(s: AutomationStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): AutomationStatus = AutomationStatus.valueOf(s)

    @TypeConverter fun auditKindToString(k: AuditKind): String = k.name
    @TypeConverter fun stringToAuditKind(s: String): AuditKind = AuditKind.valueOf(s)
}
