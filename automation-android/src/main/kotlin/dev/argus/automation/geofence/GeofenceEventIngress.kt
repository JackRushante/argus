package dev.argus.automation.geofence

import dev.argus.automation.SequentialLanes
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Una sveglia esatta può arrivare qualche istante prima: entro questo margine il ricontrollo vale. */
private const val EARLY_TOLERANCE_MILLIS = 5_000L

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
 *
 * Il pending si persiste e si conferma sotto [mutex] (sezioni brevi); la consegna, che esegue il
 * programma della regola, avviene FUORI da quel lock in una coda sequenziale per regola
 * ([lanes], B3): i bordi della stessa regola restano in ordine, regole diverse e i recovery non
 * si aspettano.
 */
class GeofenceEventIngress(
    private val state: GeofenceStateStore,
    private val dispatcher: GeofenceEventDispatcher,
    private val verifier: GeofenceTransitionVerifier = GeofenceTransitionVerifier { _, _ -> true },
    /**
     * 0.8, DWELL: per una regola DWELL ENTER ed EXIT non vanno al motore. L'ENTER verificato mette in
     * attesa un ricontrollo dopo l'attesa della regola, l'EXIT lo annulla; al ricontrollo la regola scatta
     * solo se è ancora dentro (nessun bordo nel frattempo e la posizione non lo smentisce). `null` = DWELL
     * non collegato (test e caller senza Android): ogni bordo si consegna come prima.
     */
    private val dwell: GeofenceDwellSupport? = null,
) {
    private val mutex = Mutex()
    private val lanes = SequentialLanes<AutomationId>()

    suspend fun onTransition(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        transition: Transition,
    ): Boolean = lanes.run(automationId) {
        val previousPending = mutex.withLock { state.pending(automationId, approvalFingerprint) }
        if (previousPending != null) {
            // Ritentativo di un bordo già accettato: non va ri-verificato, va solo consegnato.
            deliver(automationId, approvalFingerprint, previousPending)
            if (previousPending.transition == transition) return@run true
        }
        // Verifica prima di toccare lo stato: un bordo smentito dalla posizione non deve
        // avanzare la sequenza, altrimenti il dedup lo tratterebbe come realmente avvenuto.
        if (!verifier.accepts(automationId, transition)) return@run previousPending != null
        val pending = mutex.withLock {
            state.beginTransition(automationId, approvalFingerprint, transition)
        } ?: return@run previousPending != null
        deliver(automationId, approvalFingerprint, pending)
        true
    }

    /**
     * Ripete lo stesso event-id dopo un crash; l'ExecutionJournal dell'Engine lo deduplica. Una
     * regola con una consegna già in corso (o accodata) viene saltata: chi occupa la corsia
     * recapita già il suo pending, e il recovery non deve bloccarsi dietro a un dispatch lungo.
     */
    suspend fun recoverPending(allowedIds: Set<AutomationId>): List<AutomationId> {
        val recovered = mutableListOf<AutomationId>()
        var firstFailure: Exception? = null
        val known = mutex.withLock { state.knownIds() }
        known.sortedBy { it.value }.forEach { id ->
            if (id !in allowedIds) return@forEach
            try {
                val done = lanes.runIfIdle(id) {
                    val registration = mutex.withLock { state.get(id) }
                        ?: return@runIfIdle false
                    val pending = mutex.withLock {
                        state.pending(id, registration.approvalFingerprint)
                    } ?: return@runIfIdle false
                    deliver(id, registration.approvalFingerprint, pending)
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

    /**
     * Il ricontrollo di un DWELL è dovuto (sveglia o recupero). Scatta solo se la regola risulta ancora
     * dentro: stesso ingresso di quando è partita l'attesa, nessun bordo in sospeso e la posizione, se
     * leggibile con precisione, non dice «fuori». L'id dell'evento è legato a quell'ingresso: una seconda
     * consegna (riavvio a metà) la deduplica il motore. Il record si toglie dopo la consegna.
     */
    suspend fun onDwellDue(automationId: AutomationId): Boolean {
        val support = dwell ?: return false
        return lanes.run(automationId) {
            val pending = mutex.withLock { support.store.get(automationId) } ?: return@run false
            val now = support.nowMillis()
            if (now < pending.dueAtMillis - EARLY_TOLERANCE_MILLIS) {
                // Sveglia anticipata (orologio cambiato): si riprogramma all'istante giusto.
                support.timer.schedule(automationId, pending.dueAtMillis)
                return@run false
            }
            val registration = mutex.withLock { state.get(automationId) }
            val stillInside = registration != null &&
                registration.approvalFingerprint == pending.fingerprint &&
                registration.lastTransition == Transition.ENTER &&
                registration.sequence == pending.enterSequence &&
                registration.pendingTransition == null &&
                support.delayOf(automationId) != null
            if (!stillInside || !verifier.accepts(automationId, Transition.DWELL)) {
                forgetDwell(support, automationId)
                return@run false
            }
            dispatcher.dispatch(
                TriggerEnvelope(
                    id = TriggerEventId("geofence:${pending.fingerprint.value}:dwell:${pending.enterSequence}"),
                    event = TriggerEvent.GeofenceTransitioned(
                        automationId = automationId,
                        transition = Transition.DWELL,
                        approvalFingerprint = pending.fingerprint,
                    ),
                ),
            )
            forgetDwell(support, automationId)
            true
        }
    }

    /**
     * Dopo un riavvio: i ricontrolli già dovuti si fanno subito, gli altri riprendono la loro sveglia (un
     * riavvio del telefono le cancella). I DWELL di regole non più armate si dimenticano.
     */
    suspend fun recoverDwells(allowedIds: Set<AutomationId>): List<AutomationId> {
        val support = dwell ?: return emptyList()
        val recovered = mutableListOf<AutomationId>()
        val pendings = mutex.withLock { support.store.all() }
        pendings.sortedBy { it.automationId.value }.forEach { pending ->
            val id = pending.automationId
            when {
                id !in allowedIds -> forgetDwell(support, id)
                pending.dueAtMillis <= support.nowMillis() -> if (onDwellDue(id)) recovered += id
                else -> support.timer.schedule(id, pending.dueAtMillis)
            }
        }
        return recovered
    }

    /** Toglie i DWELL in attesa delle regole non più armate (a ogni reconcile, senza consegne). */
    suspend fun pruneDwells(allowedIds: Set<AutomationId>) {
        val support = dwell ?: return
        val stale = mutex.withLock { support.store.all() }.map { it.automationId }.filter { it !in allowedIds }
        stale.forEach { forgetDwell(support, it) }
    }

    private suspend fun forgetDwell(support: GeofenceDwellSupport, automationId: AutomationId) {
        support.timer.cancel(automationId)
        mutex.withLock { support.store.remove(automationId) }
    }

    /**
     * Regola DWELL: l'ENTER arma il ricontrollo (un ritentativo dello stesso ingresso non sposta la scadenza),
     * l'EXIT lo annulla. Niente al motore.
     */
    private suspend fun holdForDwell(
        support: GeofenceDwellSupport,
        delayMs: Long,
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        pending: PendingGeofenceTransition,
    ) {
        when (pending.transition) {
            Transition.ENTER -> {
                val existing = mutex.withLock { support.store.get(automationId) }
                val dwell = existing?.takeIf {
                    it.fingerprint == approvalFingerprint && it.enterSequence == pending.sequence
                } ?: PendingDwell(automationId, approvalFingerprint, pending.sequence, support.nowMillis() + delayMs)
                mutex.withLock { support.store.put(dwell) }
                support.timer.schedule(automationId, dwell.dueAtMillis)
            }
            else -> forgetDwell(support, automationId)
        }
    }

    /** Fuori dal lock di stato: dispatch (può durare ore), poi conferma sotto lock. */
    private suspend fun deliver(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        pending: PendingGeofenceTransition,
    ) {
        val support = dwell
        val dwellDelay = support?.delayOf?.invoke(automationId)
        if (support != null && dwellDelay != null) {
            holdForDwell(support, dwellDelay, automationId, approvalFingerprint, pending)
            mutex.withLock {
                state.completeTransition(automationId, approvalFingerprint, pending.sequence)
            }
            return
        }
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
        mutex.withLock {
            state.completeTransition(automationId, approvalFingerprint, pending.sequence)
        }
    }
}
