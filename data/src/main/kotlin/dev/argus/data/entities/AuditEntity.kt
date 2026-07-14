package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.argus.engine.runtime.AuditKind

/** Log append-only di ogni scatto/soppressione/errore (spec §10.6, [dev.argus.engine.runtime.AuditSink]). */
@Entity(
    tableName = "audit",
    indices = [Index("automationId"), Index("atMillis"), Index("eventIdHash"), Index("executionId")],
)
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val automationId: String,
    val kind: AuditKind,
    val atMillis: Long,
    val detail: String = "",
    val eventIdHash: String? = null,
    val executionId: String? = null,
)
