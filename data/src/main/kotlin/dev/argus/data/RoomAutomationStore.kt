package dev.argus.data

import dev.argus.data.dao.AutomationDao
import dev.argus.data.entities.AutomationEntity
import dev.argus.data.entities.FireClaimEntity
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.SCHEMA_VERSION
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [AutomationStore] su Room. Le query girano sui DAO suspend, quindi Room le esegue fuori dal
 * main thread (nessun blocco UI).
 *
 * Mapping entity->dominio:
 *  - read: deserializza `json`, poi sovrascrive gli scalari con le colonne piatte (autoritative:
 *    status via setStatus, ecc.).
 *  - **Decode fallito O schemaVersion incompatibile -> [AutomationStatus.NEEDS_REVIEW]** (spec E8):
 *    mai eccezione, mai drop silenzioso; l'id/nome dalle colonne piatte restano per la UI.
 */
class RoomAutomationStore(private val dao: AutomationDao) : AutomationStore {

    override suspend fun get(id: AutomationId): Automation? =
        dao.getById(id.value)?.let { toDomain(it) }

    override suspend fun all(): List<Automation> =
        dao.getAll().map { toDomain(it) }

    override fun observeAll(): Flow<List<Automation>> =
        dao.observeAll().map { rows -> rows.map { toDomain(it) } }

    override suspend fun armed(): List<Automation> =
        dao.armed().map { toDomain(it) }
            .filter { it.status == AutomationStatus.ARMED && it.enabled }

    override suspend fun delete(id: AutomationId) {
        dao.delete(id.value)
    }

    override suspend fun disable(id: AutomationId) {
        dao.disable(id.value)
    }

    override suspend fun enable(id: AutomationId): Boolean {
        val row = dao.getById(id.value) ?: return false
        if (row.status != AutomationStatus.DISABLED || row.enabled) return false
        val domain = toDomain(row)
        if (domain.status != AutomationStatus.DISABLED ||
            domain.approvalFingerprint == null ||
            domain.approvalFingerprint != ApprovalFingerprints.of(domain)
        ) {
            dao.markNeedsReview(id.value)
            return false
        }
        return dao.enableIfUnchanged(
            id = row.id,
            expectedJson = row.json,
            expectedName = row.name,
            expectedPriority = row.priority,
            expectedCooldownMs = row.cooldownMs,
            expectedSchemaVersion = row.schemaVersion,
        ) == 1
    }

    override suspend fun markNeedsReview(id: AutomationId) {
        dao.markNeedsReview(id.value)
    }

    override suspend fun recordFired(id: AutomationId, atMillis: Long) =
        dao.recordFired(id.value, atMillis)

    override suspend fun lastFiredAt(id: AutomationId): Long? =
        dao.lastFiredAt(id.value)

    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        dao.claimFire(
            claim = FireClaimEntity(
                automationId = request.automationId.value,
                eventIdHash = identifierHash(request.eventId.value),
                executionId = request.executionId.value,
                claimedAtMillis = request.atMillis,
            ),
            atMillis = request.atMillis,
            cooldownMs = request.cooldownMs,
        )

    // --- mapping -------------------------------------------------------------

    private suspend fun toDomain(e: AutomationEntity): Automation {
        val decoded: Automation? =
            if (e.schemaVersion != SCHEMA_VERSION) null
            else runCatching { ArgusJson.decodeFromString(Automation.serializer(), e.json) }.getOrNull()

        // Le colonne piatte sono autoritative sugli scalari mutabili.
        val domain = decoded?.copy(
            id = AutomationId(e.id),
            name = e.name,
            status = e.status,
            enabled = e.enabled,
            priority = e.priority,
            cooldownMs = e.cooldownMs,
            schemaVersion = e.schemaVersion,
        )
        if (domain == null ||
            domain.status == AutomationStatus.ARMED &&
            (domain.approvalFingerprint == null ||
                domain.approvalFingerprint != ApprovalFingerprints.of(domain))
        ) {
            // Quarantena persistente: non basta restituire un placeholder in memoria.
            dao.markNeedsReview(e.id)
            return needsReview(e)
        }
        return domain
    }

    /** Placeholder inerte in NEEDS_REVIEW: conserva id/nome per la UI, non è mai armabile. */
    private fun needsReview(e: AutomationEntity): Automation = Automation(
        id = AutomationId(e.id),
        name = e.name,
        createdBy = CreatedBy.USER,
        status = AutomationStatus.NEEDS_REVIEW,
        trigger = PLACEHOLDER_TRIGGER,
        actions = emptyList(),
        conditions = null,
        enabled = false,
        priority = e.priority,
        cooldownMs = e.cooldownMs,
        schemaVersion = e.schemaVersion,
    )

    private companion object {
        // Trigger inerte (cron/at nulli): mai valido per armarsi, serve solo a costruire il placeholder.
        val PLACEHOLDER_TRIGGER = Trigger.Time(cron = null, at = null, tz = "UTC")
    }
}
