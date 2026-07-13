package dev.argus.data

import dev.argus.data.dao.AuditDao
import dev.argus.data.entities.AuditEntity
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditSink

/** [AuditSink] su Room (spec §10.6). Insert append-only via DAO suspend. */
class RoomAuditSink(private val dao: AuditDao) : AuditSink {
    override suspend fun record(e: AuditEvent) {
        dao.insert(
            AuditEntity(
                automationId = e.automationId.value,
                kind = e.kind,
                atMillis = e.atMillis,
                detail = e.detail,
            )
        )
    }
}
