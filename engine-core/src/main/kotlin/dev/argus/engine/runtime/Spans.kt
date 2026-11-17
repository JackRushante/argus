package dev.argus.engine.runtime

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SpanRecord
import dev.argus.engine.model.SpanSlot
import dev.argus.engine.model.SpanValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 0.8: a quale parte di una regola-intervallo appartiene uno scatto. Le regole normali hanno solo [START]. */
enum class SpanPhase { START, EXIT }

/**
 * Una regola-intervallo in corso: è cominciata con la revisione approvata [approvalFingerprint] e non è ancora
 * finita. [records] sono i valori di prima, in ordine di registrazione (la fine li rimette al contrario);
 * [handled] sono le chiavi già viste, registrate o illeggibili: una chiave vista non si registra più.
 */
data class ActiveSpan(
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    val startedAtMillis: Long,
    val records: List<SpanRecord> = emptyList(),
    val handled: Set<String> = emptySet(),
)

/**
 * Stato persistito delle regole-intervallo (Android: preferenze, come gli altri `Prefs*StateStore`). Sopravvive
 * alla morte del processo e al riavvio. Ogni scrittura è atomica per regola.
 */
interface SpanStore {
    suspend fun get(id: AutomationId): ActiveSpan?
    suspend fun all(): List<ActiveSpan>

    /** Apre l'intervallo se non ce n'è uno per [id]; ritorna quello aperto (nuovo o già in corso, anche stantio). */
    suspend fun open(id: AutomationId, fingerprint: ApprovalFingerprint, atMillis: Long): ActiveSpan

    /**
     * Registra [record] se l'intervallo di [fingerprint] è aperto e la sua chiave non è ancora stata vista. Con
     * [record] null segna la chiave [key] come vista senza valore (non leggibile). false = niente registrato.
     */
    suspend fun record(id: AutomationId, fingerprint: ApprovalFingerprint, key: String, record: SpanRecord?): Boolean

    /** Chiude l'intervallo se è ancora quello di [fingerprint]. */
    suspend fun close(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean

    /**
     * Pausa propria delle azioni di fine (separata da quella dell'inizio): null se le azioni di fine possono
     * partire adesso (e l'istante resta registrato), altrimenti l'istante da cui potranno.
     */
    suspend fun claimExitCooldown(id: AutomationId, atMillis: Long, cooldownMs: Long): Long?
}

/** In memoria: test e caller senza Android. Stessa semantica della versione persistita. */
class MemorySpanStore : SpanStore {
    private val mutex = Mutex()
    private val spans = linkedMapOf<AutomationId, ActiveSpan>()
    private val lastExit = mutableMapOf<AutomationId, Long>()

    override suspend fun get(id: AutomationId): ActiveSpan? = mutex.withLock { spans[id] }

    override suspend fun all(): List<ActiveSpan> = mutex.withLock { spans.values.toList() }

    override suspend fun open(id: AutomationId, fingerprint: ApprovalFingerprint, atMillis: Long): ActiveSpan =
        mutex.withLock { spans.getOrPut(id) { ActiveSpan(id, fingerprint, atMillis) } }

    override suspend fun record(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
        key: String,
        record: SpanRecord?,
    ): Boolean = mutex.withLock {
        val current = spans[id]?.takeIf { it.approvalFingerprint == fingerprint } ?: return@withLock false
        if (key in current.handled) return@withLock false
        spans[id] = current.copy(
            records = if (record == null) current.records else current.records + record,
            handled = current.handled + key,
        )
        record != null
    }

    override suspend fun close(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean = mutex.withLock {
        if (spans[id]?.approvalFingerprint != fingerprint) return@withLock false
        spans.remove(id)
        true
    }

    override suspend fun claimExitCooldown(id: AutomationId, atMillis: Long, cooldownMs: Long): Long? =
        mutex.withLock {
            val last = lastExit[id]
            // Un istante nel futuro (orologio tornato indietro) è una pausa già scaduta, come nel claim Room.
            if (cooldownMs > 0 && last != null && last <= atMillis && atMillis - last < cooldownMs) {
                return@withLock if (last > Long.MAX_VALUE - cooldownMs) Long.MAX_VALUE else last + cooldownMs
            }
            lastExit[id] = atMillis
            null
        }
}

/**
 * Porta verso il telefono per gli intervalli: legge il valore che un'azione sta per cambiare e rimette «nessuna
 * modalità», l'unico valore che non ha un'azione tipata. Un valore che non si legge è null: mai un default.
 */
interface SpanDevice {
    suspend fun read(slot: SpanSlot): SpanValue?
    suspend fun clearUserMode(context: FireContext): ActionResult

    companion object {
        /** Nessun telefono: niente si legge, quindi niente si registra (e la fine non rimette nulla). */
        val UNAVAILABLE: SpanDevice = object : SpanDevice {
            override suspend fun read(slot: SpanSlot): SpanValue? = null
            override suspend fun clearUserMode(context: FireContext): ActionResult =
                ActionResult.Failure(SpanCodes.RESTORE_FAILED)
        }
    }
}

/** Codici chiusi degli intervalli (Log e journal). */
object SpanCodes {
    /** Uno scatto di fine: le azioni di fine e il ripristino sono andati (audit FIRED). */
    const val EXIT = "span_exit"

    /** Un valore che l'inizio stava per cambiare non si è potuto leggere: alla fine non si rimette. */
    const val VALUE_UNREADABLE = "span_value_unreadable"

    /** Un valore registrato non si è potuto rimettere (permesso, Shizuku, errore del telefono). */
    const val RESTORE_FAILED = "span_restore_failed"

    /** La capacità che serve a rimettere il valore manca: quel valore non si rimette, gli altri sì. */
    const val RESTORE_CAPABILITY_UNAVAILABLE = "span_restore_capability_unavailable"

    /** La regola è stata spenta, cancellata, modificata o messa da rivedere mentre era in corso: rimessi i valori. */
    const val RESTORED_RULE_CHANGED = "span_restored_rule_changed"

    /** La fine è arrivata nella pausa delle azioni di fine: valori rimessi, azioni di fine saltate. */
    const val EXIT_ACTIONS_COOLDOWN = "span_exit_actions_cooldown"
}
