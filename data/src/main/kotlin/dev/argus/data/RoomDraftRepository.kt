package dev.argus.data

import androidx.room.withTransaction
import dev.argus.data.entities.AutomationEntity
import dev.argus.data.entities.PendingDraftEntity
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.Trigger
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftDeleteResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Store delle bozze e unico writer Room del passaggio PENDING -> ARMED. */
class RoomDraftRepository(private val db: ArgusDatabase) : DraftRepository {
    private val drafts = db.draftDao()
    private val automations = db.automationDao()

    override suspend fun create(newDraft: NewDraft): DraftWriteResult = db.withTransaction {
        if (newDraft.automationId.value.isBlank())
            return@withTransaction DraftWriteResult.Rejected("automation_id_invalid")
        if (newDraft.atMillis < 0)
            return@withTransaction DraftWriteResult.Rejected("timestamp_invalid")

        val existing = automations.getById(newDraft.automationId.value)
        if (existing == null && newDraft.expectedAutomationFingerprint != null)
            return@withTransaction DraftWriteResult.Rejected("automation_missing")
        if (existing != null && newDraft.expectedAutomationFingerprint == null)
            return@withTransaction DraftWriteResult.Rejected("automation_exists")

        if (existing != null) {
            val approved = verifiedAutomation(existing)
            if (approved == null) {
                automations.markNeedsReview(existing.id)
                return@withTransaction DraftWriteResult.Rejected("automation_integrity_failure")
            }
            if (approved.approvalFingerprint != newDraft.expectedAutomationFingerprint)
                return@withTransaction DraftWriteResult.Rejected("automation_stale")
            val editable = existing.status == AutomationStatus.ARMED && existing.enabled ||
                existing.status == AutomationStatus.DISABLED && !existing.enabled
            if (!editable)
                return@withTransaction DraftWriteResult.Rejected("automation_not_editable")
        }

        val snapshot = snapshot(
            id = newDraft.id,
            automationId = newDraft.automationId,
            revision = 1,
            draft = newDraft.draft,
            createdBy = newDraft.createdBy,
            priority = newDraft.priority,
            createdAtMillis = newDraft.atMillis,
            updatedAtMillis = newDraft.atMillis,
            baseAutomationFingerprint = newDraft.expectedAutomationFingerprint,
        )
        if (drafts.insert(snapshot.toEntity()) != -1L) {
            if (existing == null) return@withTransaction DraftWriteResult.Saved(snapshot)

            val paused = automations.pauseForReviewIfUnchanged(
                id = existing.id,
                expectedStatus = existing.status,
                expectedEnabled = existing.enabled,
                expectedJson = existing.json,
                expectedName = existing.name,
                expectedPriority = existing.priority,
                expectedCooldownMs = existing.cooldownMs,
                expectedSchemaVersion = existing.schemaVersion,
            )
            if (paused == 1) return@withTransaction DraftWriteResult.Saved(snapshot)

            drafts.delete(snapshot.id.value, snapshot.revision)
            return@withTransaction DraftWriteResult.Rejected("automation_stale")
        }

        val current = drafts.getById(newDraft.id.value)
            ?: drafts.getByAutomationId(newDraft.automationId.value)
        DraftWriteResult.Conflict(current?.revision)
    }

    override suspend fun revise(
        id: DraftId,
        expectedRevision: Long,
        draft: AutomationDraft,
        priority: Int,
        atMillis: Long,
    ): DraftWriteResult = db.withTransaction {
        if (atMillis < 0) return@withTransaction DraftWriteResult.Rejected("timestamp_invalid")
        val row = drafts.getById(id.value) ?: return@withTransaction DraftWriteResult.Conflict(null)
        if (row.revision != expectedRevision)
            return@withTransaction DraftWriteResult.Conflict(row.revision)
        if (row.revision == Long.MAX_VALUE)
            return@withTransaction DraftWriteResult.Rejected("revision_exhausted")

        val current = decode(row, persistQuarantine = true)
        if (!current.hasValidFingerprint())
            return@withTransaction DraftWriteResult.Rejected("draft_integrity_failure")

        val updated = snapshot(
            id = current.id,
            automationId = current.automationId,
            revision = current.revision + 1,
            draft = draft,
            createdBy = current.createdBy,
            priority = priority,
            createdAtMillis = current.createdAtMillis,
            updatedAtMillis = maxOf(atMillis, current.updatedAtMillis),
            baseAutomationFingerprint = current.baseAutomationFingerprint,
        )
        val changed = drafts.revise(
            id = id.value,
            expectedRevision = expectedRevision,
            name = updated.draft.name,
            fingerprint = updated.fingerprint.value,
            createdBy = updated.createdBy,
            priority = updated.priority,
            schemaVersion = updated.schemaVersion,
            updatedAtMillis = updated.updatedAtMillis,
            draftJson = ArgusJson.encodeToString(AutomationDraft.serializer(), updated.draft),
        )
        if (changed == 1) DraftWriteResult.Saved(updated)
        else DraftWriteResult.Conflict(drafts.getById(id.value)?.revision)
    }

    override suspend fun get(id: DraftId): PendingDraft? =
        drafts.getById(id.value)?.let { decode(it, persistQuarantine = true) }

    override fun observeAll(): Flow<List<PendingDraft>> = drafts.observeAll().map { rows ->
        val decoded = ArrayList<PendingDraft>(rows.size)
        for (row in rows) decoded += decode(row, persistQuarantine = true)
        decoded
    }

    override suspend fun delete(id: DraftId, expectedRevision: Long): DraftDeleteResult =
        db.withTransaction {
            val row = drafts.getById(id.value) ?: return@withTransaction DraftDeleteResult.Missing
            if (row.revision != expectedRevision)
                return@withTransaction DraftDeleteResult.Stale(row.revision)
            if (drafts.delete(id.value, expectedRevision) == 1) {
                automations.cancelPendingReview(row.automationId)
                DraftDeleteResult.Deleted
            } else DraftDeleteResult.Stale(drafts.getById(id.value)?.revision ?: row.revision)
        }

    override suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): DraftArmResult = db.withTransaction {
        val row = drafts.getById(id.value) ?: return@withTransaction DraftArmResult.Missing
        if (row.revision != expectedRevision)
            return@withTransaction DraftArmResult.Stale(row.revision)

        val snapshot = decode(row, persistQuarantine = true)
        if (!snapshot.hasValidFingerprint() || snapshot.fingerprint != expectedFingerprint)
            return@withTransaction DraftArmResult.IntegrityFailure
        val armed = snapshot.armedAutomation()
        val existing = automations.getById(snapshot.automationId.value)
        val baseFingerprint = snapshot.baseAutomationFingerprint
        if (baseFingerprint == null) {
            if (existing != null) return@withTransaction DraftArmResult.AutomationConflict
            if (automations.insertAutomation(armed.toEntity()) == -1L)
                return@withTransaction DraftArmResult.AutomationConflict
        } else {
            if (existing == null) return@withTransaction DraftArmResult.AutomationConflict
            if (existing.status != AutomationStatus.PENDING_APPROVAL || existing.enabled)
                return@withTransaction DraftArmResult.AutomationConflict
            val approved = verifiedAutomation(existing)
            if (approved == null) {
                automations.markNeedsReview(existing.id)
                return@withTransaction DraftArmResult.IntegrityFailure
            }
            if (approved.approvalFingerprint != baseFingerprint)
                return@withTransaction DraftArmResult.AutomationConflict
            automations.upsert(armed.toEntity(lastFiredAt = existing.lastFiredAt))
        }
        check(drafts.delete(id.value, expectedRevision) == 1) {
            "La bozza è cambiata dentro la transazione di arm"
        }
        DraftArmResult.Armed(armed)
    }

    private suspend fun decode(
        entity: PendingDraftEntity,
        persistQuarantine: Boolean,
    ): PendingDraft {
        var error = entity.quarantineCode
        if (error == null && !AutomationSchema.isSupportedVersion(entity.schemaVersion)) {
            error = "schema_incompatible"
        }
        if (error == null && entity.revision < 1) error = "revision_invalid"
        if (error == null && entity.automationId.isBlank()) error = "automation_id_invalid"
        if (error == null && (entity.createdAtMillis < 0 || entity.updatedAtMillis < entity.createdAtMillis))
            error = "timestamp_invalid"

        val fingerprint = runCatching { ApprovalFingerprint(entity.fingerprint) }.getOrElse {
            if (error == null) error = "fingerprint_invalid"
            ZERO_FINGERPRINT
        }
        var baseAutomationFingerprint: ApprovalFingerprint? = null
        if (entity.baseAutomationFingerprint != null) {
            baseAutomationFingerprint = runCatching {
                ApprovalFingerprint(entity.baseAutomationFingerprint)
            }.getOrNull()
            if (error == null && baseAutomationFingerprint == null)
                error = "base_fingerprint_invalid"
        }
        val draft = if (error == null) {
            runCatching {
                ArgusJson.decodeFromString(AutomationDraft.serializer(), entity.draftJson)
            }.getOrElse {
                error = "draft_json_invalid"
                placeholder(entity.name)
            }
        } else {
            placeholder(entity.name)
        }

        var snapshot = PendingDraft(
            id = DraftId(entity.id.ifBlank { "invalid-draft" }),
            automationId = dev.argus.engine.model.AutomationId(entity.automationId),
            revision = entity.revision.coerceAtLeast(1),
            fingerprint = fingerprint,
            draft = draft,
            createdBy = entity.createdBy,
            priority = entity.priority,
            schemaVersion = entity.schemaVersion,
            createdAtMillis = entity.createdAtMillis.coerceAtLeast(0),
            updatedAtMillis = maxOf(entity.updatedAtMillis, entity.createdAtMillis, 0),
            baseAutomationFingerprint = baseAutomationFingerprint,
            integrityError = error,
        )
        if (error == null && !snapshot.hasValidFingerprint()) {
            error = "fingerprint_mismatch"
            snapshot = snapshot.copy(integrityError = error)
        }
        if (persistQuarantine && entity.quarantineCode == null && error != null)
            drafts.quarantine(entity.id, error)
        return snapshot
    }

    private fun snapshot(
        id: DraftId,
        automationId: dev.argus.engine.model.AutomationId,
        revision: Long,
        draft: AutomationDraft,
        createdBy: dev.argus.engine.model.CreatedBy,
        priority: Int,
        createdAtMillis: Long,
        updatedAtMillis: Long,
        baseAutomationFingerprint: ApprovalFingerprint?,
    ): PendingDraft {
        val schemaVersion = AutomationSchema.versionFor(draft)
        val unsigned = Automation(
            id = automationId,
            name = draft.name,
            createdBy = createdBy,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = draft.trigger,
            actions = draft.actions,
            vars = draft.vars,
            conditions = draft.conditions,
            enabled = false,
            priority = priority,
            cooldownMs = draft.cooldownMs,
            schemaVersion = schemaVersion,
        )
        return PendingDraft(
            id = id,
            automationId = automationId,
            revision = revision,
            fingerprint = ApprovalFingerprints.of(unsigned),
            draft = draft,
            createdBy = createdBy,
            priority = priority,
            schemaVersion = schemaVersion,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            baseAutomationFingerprint = baseAutomationFingerprint,
        )
    }

    private fun PendingDraft.toEntity() = PendingDraftEntity(
        id = id.value,
        automationId = automationId.value,
        name = draft.name,
        revision = revision,
        fingerprint = fingerprint.value,
        createdBy = createdBy,
        priority = priority,
        schemaVersion = schemaVersion,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        baseAutomationFingerprint = baseAutomationFingerprint?.value,
        quarantineCode = integrityError,
        draftJson = ArgusJson.encodeToString(AutomationDraft.serializer(), draft),
    )

    private fun Automation.toEntity(lastFiredAt: Long? = null) = AutomationEntity(
        id = id.value,
        name = name,
        status = status,
        enabled = enabled,
        priority = priority,
        cooldownMs = cooldownMs,
        schemaVersion = schemaVersion,
        lastFiredAt = lastFiredAt,
        json = ArgusJson.encodeToString(Automation.serializer(), this),
    )

    /** Verifica sia il JSON sia le colonne indicizzate, che fanno parte del contenuto approvato. */
    private fun verifiedAutomation(entity: AutomationEntity): Automation? {
        if (!AutomationSchema.isSupportedVersion(entity.schemaVersion)) return null
        val decoded = runCatching {
            ArgusJson.decodeFromString(Automation.serializer(), entity.json)
        }.getOrNull() ?: return null
        val domain = decoded.copy(
            id = dev.argus.engine.model.AutomationId(entity.id),
            name = entity.name,
            status = entity.status,
            enabled = entity.enabled,
            priority = entity.priority,
            cooldownMs = entity.cooldownMs,
            schemaVersion = entity.schemaVersion,
        )
        if (!AutomationSchema.isCompatible(domain)) return null
        val fingerprint = domain.approvalFingerprint ?: return null
        return domain.takeIf { fingerprint == ApprovalFingerprints.of(domain) }
    }

    private fun placeholder(name: String) = AutomationDraft(
        name = name.ifBlank { "Bozza non leggibile" },
        trigger = Trigger.Time(cron = null, at = null, tz = "UTC"),
        actions = emptyList(),
        rationale = "Bozza in quarantena",
    )

    private companion object {
        val ZERO_FINGERPRINT = ApprovalFingerprint("0".repeat(64))
    }
}
