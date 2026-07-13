package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.argus.data.entities.AutomationEntity
import dev.argus.engine.model.AutomationStatus

@Dao
interface AutomationDao {

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Query("SELECT * FROM automations ORDER BY priority ASC, id ASC")
    suspend fun getAll(): List<AutomationEntity>

    /**
     * Solo ARMED + enabled, in priorità CRESCENTE (spec §5: il più prioritario esegue ultimo e vince).
     * Il literal 'ARMED' combacia con [AutomationStatus.ARMED]`.name` scritto dal converter.
     */
    @Query("SELECT * FROM automations WHERE status = 'ARMED' AND enabled = 1 ORDER BY priority ASC, id ASC")
    suspend fun armed(): List<AutomationEntity>

    @Upsert
    suspend fun upsert(entity: AutomationEntity)

    /** Aggiorna solo lo status (il converter applica l'enum al bind param). */
    @Query("UPDATE automations SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: AutomationStatus)

    /** Registra l'ultimo scatto (cooldown); non tocca [AutomationEntity.json]. */
    @Query("UPDATE automations SET lastFiredAt = :atMillis WHERE id = :id")
    suspend fun recordFired(id: String, atMillis: Long)

    @Query("SELECT lastFiredAt FROM automations WHERE id = :id")
    suspend fun lastFiredAt(id: String): Long?
}
