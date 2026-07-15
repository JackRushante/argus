package dev.argus.automation.geofence

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface GeofenceEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

/**
 * Seconda opinione sul bordo annunciato dal framework, che può contraddire la posizione reale
 * (osservato sul campo il 2026-07-15: EXIT ripetuti con il device fermo al centro dell'area).
 * Il design prometteva isteresi contro il rumore, ma era implementata solo nel recupero
 * post-crash: qui la stessa difesa copre anche il percorso normale.
 *
 * **Fail-open**: senza posizione leggibile il bordo si accetta. Perdere un attraversamento vero
 * è peggio che accettarne uno spurio, e senza posizione non abbiamo elementi per smentire il
 * framework — che resta il segnale primario, non un sospettato.
 */
fun interface GeofenceTransitionVerifier {
    suspend fun accepts(automationId: AutomationId, transition: Transition): Boolean
}

/**
 * Dedup persistente dei callback framework. La sequenza sopravvive alle ri-registrazioni della
 * stessa revisione, quindi anche l'ENTER iniziale ripetuto dopo process death resta idempotente.
 */
class GeofenceEventIngress(
    private val state: GeofenceStateStore,
    private val dispatcher: GeofenceEventDispatcher,
    private val verifier: GeofenceTransitionVerifier = GeofenceTransitionVerifier { _, _ -> true },
) {
    private val mutex = Mutex()

    suspend fun onTransition(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        transition: Transition,
    ): Boolean = mutex.withLock {
        val previousPending = state.pending(automationId, approvalFingerprint)
        if (previousPending != null) {
            // Ritentativo di un bordo già accettato: non va ri-verificato, va solo consegnato.
            deliver(automationId, approvalFingerprint, previousPending)
            if (previousPending.transition == transition) return@withLock true
        }
        // Verifica prima di toccare lo stato: un bordo smentito dalla posizione non deve
        // avanzare la sequenza, altrimenti il dedup lo tratterebbe come realmente avvenuto.
        if (!verifier.accepts(automationId, transition)) return@withLock previousPending != null
        val pending = state.beginTransition(
            automationId,
            approvalFingerprint,
            transition,
        ) ?: return@withLock previousPending != null
        deliver(automationId, approvalFingerprint, pending)
        true
    }

    /** Ripete lo stesso event-id dopo un crash; l'ExecutionJournal dell'Engine lo deduplica. */
    suspend fun recoverPending(allowedIds: Set<AutomationId>): List<AutomationId> =
        mutex.withLock {
            val recovered = mutableListOf<AutomationId>()
            var firstFailure: Exception? = null
            state.knownIds().sortedBy { it.value }.forEach { id ->
                if (id !in allowedIds) return@forEach
                val registration = state.get(id) ?: return@forEach
                val pending = state.pending(id, registration.approvalFingerprint)
                    ?: return@forEach
                try {
                    deliver(id, registration.approvalFingerprint, pending)
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

    private suspend fun deliver(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        pending: PendingGeofenceTransition,
    ) {
        dispatcher.dispatch(
            TriggerEnvelope(
                id = TriggerEventId(
                    "geofence:${approvalFingerprint.value}:${pending.sequence}",
                ),
                event = TriggerEvent.GeofenceTransitioned(
                    automationId = automationId,
                    transition = pending.transition,
                    approvalFingerprint = approvalFingerprint,
                ),
            ),
        )
        state.completeTransition(automationId, approvalFingerprint, pending.sequence)
    }
}
