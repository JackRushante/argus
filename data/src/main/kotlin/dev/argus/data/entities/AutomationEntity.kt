package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.argus.engine.model.AutomationStatus

/**
 * Riga di automazione. Le colonne piatte servono a query/ordinamento senza deserializzare
 * (armed(), priorità, cooldown); [json] porta l'intera [dev.argus.engine.model.Automation]
 * serializzata via ArgusJson — l'unica fonte per lo schema polimorfico Trigger/Condition/Action.
 *
 * Le colonne piatte sono AUTORITATIVE per gli scalari mutabili (status via setStatus,
 * lastFiredAt via recordFired): in lettura sovrascrivono i valori snapshot dentro [json].
 */
@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val status: AutomationStatus,
    val enabled: Boolean,
    val priority: Int,
    val cooldownMs: Long,
    val schemaVersion: Int,
    /** Epoch-millis dell'ultimo scatto (cooldown, spec §5); null = mai scattata. */
    val lastFiredAt: Long? = null,
    val json: String,
)
