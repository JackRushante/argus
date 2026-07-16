package dev.argus.automation.sensor

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
 * fan-out verso ognuna. Il rearm avviene SEMPRE — anche se il dispatch fallisce o viene cancellato
 * — ma in `NonCancellable`, senza inghiottire la [CancellationException] originale: un one-shot non
 * riarmato è un sensore morto in silenzio. Non chiama mai azioni direttamente: passa dall'Engine
 * via [dispatcher].
 */
class SensorEventIngress(
    private val store: SensorDetectionStore,
    private val dispatcher: SensorEventDispatcher,
    private val rearmer: SensorRearmer,
    private val eligibleRules: suspend (SensorKind) -> List<EligibleSensorRule>,
) {
    private val mutex = Mutex()

    suspend fun onSensorTriggered(kind: SensorKind) {
        // Il one-shot si è già disattivato da solo: registralo prima di qualunque altra cosa,
        // così un rearm concorrente non crede il sensore ancora vivo.
        rearmer.markConsumed(kind)
        try {
            mutex.withLock {
                eligibleRules(kind).filter { it.kind == kind }.forEach { rule ->
                    deliver(rule)
                }
            }
        } finally {
            withContext(NonCancellable) { rearmer.rearm() }
        }
    }

    /** Redelivery dopo process death: ripete i pending con lo stesso event id. */
    suspend fun recoverPending(allowed: Set<AutomationId>): List<AutomationId> = mutex.withLock {
        val recovered = mutableListOf<AutomationId>()
        var firstFailure: Exception? = null
        store.knownIds().sortedBy { it.value }.forEach { id ->
            if (id !in allowed) return@forEach
            val rule = eligibleRules.snapshotFor(id) ?: return@forEach
            val pending = store.pending(id, rule.fingerprint) ?: return@forEach
            try {
                dispatch(rule, pending)
                recovered += id
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
        recovered
    }

    private suspend fun deliver(rule: EligibleSensorRule) {
        val pending = store.pending(rule.id, rule.fingerprint)
            ?: store.beginDetection(rule.id, rule.fingerprint, rule.kind)
        dispatch(rule, pending)
    }

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
        store.completeDetection(rule.id, rule.fingerprint, pending.sequence)
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
