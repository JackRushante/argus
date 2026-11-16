package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Etichetta di una regola salvata (0.7.2). Tabella a parte, fuori da [AutomationEntity.json]: un'etichetta è
 * organizzazione, non comportamento, e non entra nell'impronta di approvazione. Eliminare la regola elimina
 * le sue etichette (CASCADE): un'etichetta senza regole sparisce da sola.
 *
 * [labelKey] è la chiave di confronto ([dev.argus.engine.model.RuleLabels.key]): la stessa etichetta non
 * compare due volte sulla stessa regola, con qualunque maiuscola. [label] è la grafia mostrata.
 */
@Entity(
    tableName = "automation_labels",
    primaryKeys = ["automationId", "labelKey"],
    foreignKeys = [
        ForeignKey(
            entity = AutomationEntity::class,
            parentColumns = ["id"],
            childColumns = ["automationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["labelKey"])],
)
data class AutomationLabelEntity(
    val automationId: String,
    val labelKey: String,
    val label: String,
    /** Ordine di aggiunta: le etichette di una regola si mostrano in quest'ordine. */
    val addedAtMillis: Long,
)
