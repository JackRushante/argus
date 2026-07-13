package dev.argus.data

import dev.argus.data.dao.AutomationDao
import dev.argus.data.entities.AutomationEntity
import dev.argus.data.entities.FireClaimEntity
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.SCHEMA_VERSION
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult

/**
 * [AutomationStore] su Room. Le query girano sui DAO suspend, quindi Room le esegue fuori dal
 * main thread (nessun blocco UI).
 *
 * Mapping entity<->dominio:
 *  - save: serializza l'intera [Automation] in `json` + copia le colonne piatte per query/ordine.
 *  - read: deserializza `json`, poi sovrascrive gli scalari con le colonne piatte (autoritative:
 *    status via setStatus, ecc.).
 *  - **Decode fallito O schemaVersion incompatibile -> [AutomationStatus.NEEDS_REVIEW]** (spec E8):
 *    mai eccezione, mai drop silenzioso; l'id/nome dalle colonne piatte restano per la UI.
 */
class RoomAutomationStore(private val dao: AutomationDao) : AutomationStore {

    override suspend fun get(id: AutomationId): Automation? =
        dao.getById(id.value)?.let(::toDomain)

    override suspend fun armed(): List<Automation> =
        dao.armed().map(::toDomain)

    override suspend fun save(a: Automation) {
        dao.upsertPreservingLastFired(toEntity(a, lastFiredAt = null))
    }

    override suspend fun setStatus(id: AutomationId, status: AutomationStatus) =
        dao.updateStatus(id.value, status)

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

    private fun toEntity(a: Automation, lastFiredAt: Long?) = AutomationEntity(
        id = a.id.value,
        name = a.name,
        status = a.status,
        enabled = a.enabled,
        priority = a.priority,
        cooldownMs = a.cooldownMs,
        schemaVersion = a.schemaVersion,
        lastFiredAt = lastFiredAt,
        json = ArgusJson.encodeToString(Automation.serializer(), a),
    )

    private fun toDomain(e: AutomationEntity): Automation {
        val decoded: Automation? =
            if (e.schemaVersion != SCHEMA_VERSION) null
            else runCatching { ArgusJson.decodeFromString(Automation.serializer(), e.json) }.getOrNull()

        // Le colonne piatte sono autoritative sugli scalari (status via setStatus, ecc.).
        return decoded?.copy(
            id = AutomationId(e.id),
            name = e.name,
            status = e.status,
            enabled = e.enabled,
            priority = e.priority,
            cooldownMs = e.cooldownMs,
            schemaVersion = e.schemaVersion,
        ) ?: needsReview(e)
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
