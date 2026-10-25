package dev.argus.automation.sensor

import dev.argus.automation.SequentialLanes
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SensorKind
import dev.argus.engine.runtime.SensorEventIds
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Detection pending per-regola: serve solo a rendere l'event id stabile su una redelivery. */
data class SensorDetection(val kind: SensorKind, val sequence: Long)

/**
 * Stato per-regola del canale sensori. L'unica cosa da persistere è il pending: se il processo
 * muore fra il dispatch e il completamento, la redelivery deve riusare la STESSA sequenza (stesso
 * event id, così l'ExecutionJournal deduplica). Il fingerprint fa parte della chiave: dopo un
 * edit, un pending della vecchia revisione non deve più corrispondere.
 */
interface SensorDetectionStore {
    fun knownIds(): Set<AutomationId>
    fun pending(id: AutomationId, fingerprint: ApprovalFingerprint): SensorDetection?
    fun beginDetection(id: AutomationId, fingerprint: ApprovalFingerprint, kind: SensorKind): SensorDetection
    fun completeDetection(id: AutomationId, fingerprint: ApprovalFingerprint, sequence: Long)
    fun forget(id: AutomationId)
}

/** Regola ancora eleggibile al momento del callback fisico. Snapshot bounded, mai il sample raw. */
data class EligibleSensorRule(
    val id: AutomationId,
    val fingerprint: ApprovalFingerprint,
    val kind: SensorKind,
)

fun interface SensorEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

/**
 * Traduce il callback fisico one-shot in envelope per-regola e ri-arma il sensore.
 *
 * Una registrazione fisica è condivisa da tutte le regole del kind (handoff §7.3): il callback fa
 * fan-out verso ognuna. Il rearm avviene PRIMA di ogni dispatch (B3): il dispatch esegue il
 * programma della regola, anche per ore, e un one-shot non riarmato nel frattempo è un sensore
 * morto in silenzio. Il rearm gira in `NonCancellable`; un suo errore non cancella le consegne e
 * viene rilanciato alla fine. Non chiama mai azioni direttamente: passa dall'Engine via
 * [dispatcher].
 *
 * Il pending per-regola si persiste e si conferma sotto [mutex] (sezioni brevi); la consegna
 * avviene FUORI da quel lock in una coda sequenziale per kind ([lanes]): due scatti dello stesso
 * sensore restano in ordine e non consegnano mai in parallelo lo stesso event id.
 */
class SensorEventIngress(
    private val store: SensorDetectionStore,
    private val dispatcher: SensorEventDispatcher,
    private val rearmer: SensorRearmer,
    private val eligibleRules: suspend (SensorKind) -> List<EligibleSensorRule>,
) {
    private val mutex = Mutex()
    private val lanes = SequentialLanes<SensorKind>()

    suspend fun onSensorTriggered(kind: SensorKind) {
        // Il one-shot si è già disattivato da solo: registralo prima di qualunque altra cosa,
        // così un rearm concorrente non crede il sensore ancora vivo.
        rearmer.markConsumed(kind)
        // Riarmo PRIMA del dispatch: il sensore torna vivo mentre il programma è in esecuzione.
        val rearmFailure: Exception? = withContext(NonCancellable) {
            try {
                rearmer.rearm()
                null
            } catch (error: Exception) {
                error
            }
        }
        var deliveryFailure: Exception? = null
        lanes.run(kind) {
            val batch = mutex.withLock {
                eligibleRules(kind).filter { it.kind == kind }.map { rule ->
                    rule to (
                        store.pending(rule.id, rule.fingerprint)
                            ?: store.beginDetection(rule.id, rule.fingerprint, rule.kind)
                        )
                }
            }
            // Una regola che fallisce non deve togliere la consegna alle altre dello stesso scatto:
            // il suo pending resta dovuto per il recovery.
            batch.forEach { (rule, pending) ->
                try {
                    dispatch(rule, pending)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (deliveryFailure == null) deliveryFailure = error
                }
            }
        }
        (deliveryFailure ?: rearmFailure)?.let { throw it }
    }

    /**
     * Redelivery dopo process death: ripete i pending con lo stesso event id. Un kind con una
     * consegna già in corso (o accodata) viene saltato: chi occupa la corsia recapita già i suoi
     * pending, e il recovery non deve bloccarsi dietro a un dispatch lungo.
     */
    suspend fun recoverPending(allowed: Set<AutomationId>): List<AutomationId> {
        val recovered = mutableListOf<AutomationId>()
        var firstFailure: Exception? = null
        val known = mutex.withLock { store.knownIds() }
        known.sortedBy { it.value }.forEach { id ->
            if (id !in allowed) return@forEach
            val rule = eligibleRules.snapshotFor(id) ?: return@forEach
            try {
                val done = lanes.runIfIdle(rule.kind) {
                    val pending = mutex.withLock { store.pending(id, rule.fingerprint) }
                        ?: return@runIfIdle false
                    dispatch(rule, pending)
                    true
                }
                if (done == true) recovered += id
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
        return recovered
    }

    /** Fuori dal lock di stato: dispatch (può durare ore), poi conferma sotto lock. */
    private suspend fun dispatch(rule: EligibleSensorRule, pending: SensorDetection) {
        dispatcher.dispatch(
            TriggerEnvelope(
                id = SensorEventIds.create(rule.id, rule.fingerprint, pending.kind, pending.sequence),
                event = TriggerEvent.SensorChanged(
                    automationId = rule.id,
                    kind = pending.kind,
                    approvalFingerprint = rule.fingerprint,
                ),
            ),
        )
        mutex.withLock { store.completeDetection(rule.id, rule.fingerprint, pending.sequence) }
    }

    /** Cerca la revisione ARMED corrente per [id], di qualunque kind eleggibile. */
    private suspend fun (suspend (SensorKind) -> List<EligibleSensorRule>).snapshotFor(
        id: AutomationId,
    ): EligibleSensorRule? {
        SensorKind.entries.forEach { kind ->
            this(kind).firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }
}
