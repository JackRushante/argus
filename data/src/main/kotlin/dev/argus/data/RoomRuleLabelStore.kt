package dev.argus.data

import androidx.room.withTransaction
import dev.argus.data.entities.AutomationLabelEntity
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.RuleLabels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Colonna `pending_drafts.labels` (0.7.2): le etichette di una bozza, una per riga. Le etichette valide non
 * contengono a capo ([RuleLabels.clean] li trasforma in spazi), quindi la riga è un separatore sicuro.
 */
object DraftLabels {
    fun encode(labels: List<String>): String? = RuleLabels.sanitize(labels).takeIf { it.isNotEmpty() }?.joinToString("\n")

    fun decode(column: String?): List<String> = column?.let { RuleLabels.sanitize(it.split('\n')) }.orEmpty()
}

/** Esito di «aggiungi etichetta». Vocabolario chiuso: il testo lo scrive la UI. */
sealed interface LabelAddResult {
    /** Aggiunta con la grafia [label] (quella già in uso, se l'etichetta esisteva). [isNewLabel]: prima non c'era. */
    data class Added(val label: String, val isNewLabel: Boolean) : LabelAddResult

    /** Vuota, troppo lunga o con caratteri non ammessi. */
    data object Invalid : LabelAddResult

    /** La regola ha già questa etichetta. */
    data object AlreadyPresent : LabelAddResult

    /** La regola ha già [RuleLabels.MAX_PER_RULE] etichette. */
    data object RuleFull : LabelAddResult

    /** Ci sono già [RuleLabels.MAX_DISTINCT] etichette distinte: si può solo riusarne una. */
    data object TooManyLabels : LabelAddResult

    /** La regola non c'è (più). */
    data object MissingRule : LabelAddResult
}

/**
 * Etichette delle regole (0.7.2). Le regole salvate le tengono in `automation_labels`; le bozze di regole nuove
 * (import) nella colonna `pending_drafts.labels`. I limiti (5 per regola, 20 distinte) contano entrambe.
 *
 * Nessuna di queste scritture tocca la regola: niente nuova revisione, niente impronta, niente approvazione.
 */
class RoomRuleLabelStore(
    private val db: ArgusDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val labels = db.labelDao()
    private val automations = db.automationDao()

    /**
     * Etichette per id di regola, nell'ordine di aggiunta: regole salvate e bozze di regole nuove (che hanno già
     * il loro id). Una regola senza etichette non compare.
     */
    fun observe(): Flow<Map<String, List<String>>> =
        combine(labels.observeAll(), db.draftDao().observeLabels()) { rows, draftRows ->
            merge(rows, draftRows.associate { it.automationId to DraftLabels.decode(it.labels) })
        }

    /** Le stesse etichette, lette una volta (export del backup). */
    suspend fun snapshot(): Map<String, List<String>> =
        merge(labels.all(), db.draftDao().allLabels().associate { it.automationId to DraftLabels.decode(it.labels) })

    /** Chiavi delle etichette di UNA regola salvata: è la lettura che fa il motore quando un gruppo è in pausa. */
    suspend fun keysOf(automationId: AutomationId): Set<String> = labels.keysOf(automationId.value).toSet()

    suspend fun add(automationId: AutomationId, raw: String): LabelAddResult = db.withTransaction {
        val label = RuleLabels.clean(raw) ?: return@withTransaction LabelAddResult.Invalid
        if (automations.getById(automationId.value) == null) return@withTransaction LabelAddResult.MissingRule
        val key = RuleLabels.key(label)
        val current = labels.keysOf(automationId.value)
        if (key in current) return@withTransaction LabelAddResult.AlreadyPresent
        if (current.size >= RuleLabels.MAX_PER_RULE) return@withTransaction LabelAddResult.RuleFull
        val spellings = labelSpellings(db)
        val isNew = key !in spellings
        if (isNew && spellings.size >= RuleLabels.MAX_DISTINCT) return@withTransaction LabelAddResult.TooManyLabels
        // La stessa etichetta si scrive sempre allo stesso modo: si riusa la grafia già in uso.
        val spelling = spellings[key] ?: label
        labels.insert(AutomationLabelEntity(automationId.value, key, spelling, nowMillis()))
        LabelAddResult.Added(spelling, isNew)
    }

    /** Toglie l'etichetta dalla regola (con qualunque maiuscola). `false` se non c'era. */
    suspend fun remove(automationId: AutomationId, label: String): Boolean =
        labels.delete(automationId.value, RuleLabels.key(label)) > 0

    private fun merge(
        rows: List<AutomationLabelEntity>,
        drafts: Map<String, List<String>>,
    ): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        rows.groupBy { it.automationId }.forEach { (id, entries) -> result[id] = entries.map { it.label } }
        drafts.forEach { (id, draftLabels) ->
            if (draftLabels.isNotEmpty() && id !in result) result[id] = draftLabels
        }
        return result
    }
}

/**
 * Grafia di ogni etichetta in uso (chiave → etichetta), dalle regole salvate e poi dalle bozze. Le sue chiavi
 * sono le etichette distinte che contano per [RuleLabels.MAX_DISTINCT]. Va letta dentro la transazione che scrive.
 */
internal suspend fun labelSpellings(db: ArgusDatabase): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    db.labelDao().all().sortedBy { it.addedAtMillis }.forEach { result.putIfAbsent(it.labelKey, it.label) }
    db.draftDao().allLabels().forEach { row ->
        DraftLabels.decode(row.labels).forEach { result.putIfAbsent(RuleLabels.key(it), it) }
    }
    return result
}
