package dev.argus.automation.phone

import dev.argus.automation.SequentialLanes
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.TriggerEnvelope

/** Frammento di SMS come arriva dal framework: i multipart vanno ricomposti per mittente. */
data class SmsPart(val sender: String?, val body: String?, val atMillis: Long)

/** Ultima transizione chiamata: sopravvive al processo per dedupe e numero su CALL_ENDED. */
data class CallStateSnapshot(
    val state: String,
    val number: String?,
    val transitionAtMillis: Long,
    /** SIM 1|2 della chiamata in corso (0.4.1), `null` se non nota. */
    val sim: Int? = null,
)

interface CallStateStore {
    fun last(): CallStateSnapshot?

    /** Testa della coda di consegna (al più due eventi). */
    fun pending(): TriggerEnvelope?

    /**
     * Persiste lo snapshot con la coda di consegna in un'unica scrittura: [next] (MISSED_CALL dopo
     * CALL_ENDED, B12) richiede [pending] e ne prende il posto quando questo si completa.
     */
    fun record(snapshot: CallStateSnapshot, pending: TriggerEnvelope? = null, next: TriggerEnvelope? = null)

    /** Completa la testa della coda e promuove il successivo, se c'è. */
    fun complete(eventId: String)
}

fun interface PhoneEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

/**
 * Normalizza gli eventi telefonia prima dell'engine — pattern dell'ingress notifiche P1.
 * Il testo SMS resta volatile (RAM del dispatch); nessun contenuto nei log. La transizione
 * chiamata usa lo stato PRECEDENTE persistito: IDLE conta come fine chiamata solo dopo
 * RINGING/OFFHOOK, mai a freddo.
 */
class PhoneEventIngress(
    private val parser: PhoneEventParser,
    private val callState: CallStateStore,
    private val dispatcher: PhoneEventDispatcher,
) {

    /**
     * Coda sequenziale delle transizioni chiamata (B3): la consegna esegue l'intero programma della
     * regola, quindi le transizioni successive aspettano in ordine il completamento della
     * precedente (il pending è uno slot solo), ma `recoverPending` non si blocca dietro a un
     * dispatch lungo né lo riconsegna.
     */
    private val lanes = SequentialLanes<String>()

    suspend fun onSms(parts: List<SmsPart>, sim: Int? = null) {
        parts
            .filter { !it.body.isNullOrBlank() }
            .groupBy { it.sender }
            .forEach { (sender, senderParts) ->
                val text = senderParts.joinToString("") { it.body.orEmpty() }
                val atMillis = senderParts.minOf { it.atMillis }
                parser.parse(PhoneEvent.SMS_RECEIVED, sender, text, atMillis, sim)
                    ?.let { dispatcher.dispatch(it) }
            }
    }

    suspend fun onCallStateChanged(state: String, number: String?, atMillis: Long, sim: Int? = null) {
        lanes.run(CALL_LANE) {
            if (state !in VALID_STATES) return@run
            deliverQueued()
            val safeAtMillis = atMillis.coerceAtLeast(0)
            val stored = callState.last()
            val previous = stored?.takeUnless {
                val ageMillis = safeAtMillis - it.transitionAtMillis
                ageMillis < 0 || ageMillis > MAX_CALL_STATE_AGE_MILLIS
            }

            if (state == previous?.state) {
                // Su RINGING il framework può consegnare prima il broadcast anonimo e poi quello
                // col numero. Riemettiamo l'envelope arricchito con la STESSA identità: le regole
                // generiche restano idempotenti, quelle filtrate per numero possono finalmente
                // fare match.
                // Lo stesso vale per lo slot SIM, quando il primo broadcast non lo portava.
                if (state == STATE_RINGING &&
                    ((previous.number == null && number != null) || (previous.sim == null && sim != null))
                ) {
                    val enriched = previous.copy(
                        number = number ?: previous.number,
                        sim = sim ?: previous.sim,
                    )
                    val envelope = parser.parse(
                        PhoneEvent.INCOMING_CALL,
                        enriched.number,
                        smsText = null,
                        atMillis = enriched.transitionAtMillis,
                        sim = enriched.sim,
                    )
                    callState.record(enriched, envelope)
                    envelope?.let { deliverCall(it) }
                }
                return@run
            }

            val retainedNumber = number ?: previous?.number
            val retainedSim = sim ?: previous?.sim
            val snapshot = CallStateSnapshot(
                state = state,
                number = retainedNumber.takeUnless { state == STATE_IDLE },
                transitionAtMillis = safeAtMillis,
                sim = retainedSim.takeUnless { state == STATE_IDLE },
            )
            val envelope = when {
                state == STATE_RINGING -> parser.parse(
                    PhoneEvent.INCOMING_CALL,
                    retainedNumber,
                    smsText = null,
                    atMillis = safeAtMillis,
                    sim = retainedSim,
                )
                state == STATE_IDLE && previous?.state in setOf(STATE_RINGING, STATE_OFFHOOK) ->
                    parser.parse(
                        PhoneEvent.CALL_ENDED,
                        retainedNumber,
                        smsText = null,
                        atMillis = safeAtMillis,
                        sim = retainedSim,
                    )
                else -> null
            }
            // Chiamata persa: squillo -> idle SENZA passare da OFFHOOK (nessuna risposta). Anche un
            // rifiuto manuale finisce così: con le API pubbliche non si distinguono. Si recapita dopo
            // CALL_ENDED, che resta invariato per le regole esistenti. Entrambi si persistono PRIMA
            // di consegnare il primo (B12): se il processo muore durante CALL_ENDED, il recupero
            // consegna anche MISSED_CALL.
            val missed = if (state == STATE_IDLE && previous?.state == STATE_RINGING) {
                parser.parse(
                    PhoneEvent.MISSED_CALL,
                    retainedNumber,
                    smsText = null,
                    atMillis = safeAtMillis,
                    sim = retainedSim,
                )
            } else {
                null
            }
            val queue = listOfNotNull(envelope, missed)
            callState.record(snapshot, queue.getOrNull(0), queue.getOrNull(1))
            queue.forEach { deliverCall(it) }
        }
    }

    /** Salta se una consegna è già in corso o accodata: chi occupa la corsia la recapita già. */
    suspend fun recoverPending(): Boolean = lanes.runIfIdle(CALL_LANE) {
        deliverQueued()
    } ?: false

    /** Consegna la coda persistita in ordine; `true` se c'era qualcosa da consegnare. */
    private suspend fun deliverQueued(): Boolean {
        var delivered = false
        repeat(MAX_QUEUED) {
            val pending = callState.pending() ?: return delivered
            deliverCall(pending)
            delivered = true
        }
        return delivered
    }

    private suspend fun deliverCall(envelope: TriggerEnvelope) {
        dispatcher.dispatch(envelope)
        callState.complete(envelope.id.value)
    }

    private companion object {
        const val CALL_LANE = "call"
        /** CALL_ENDED + MISSED_CALL: la coda persistita non supera mai due eventi. */
        const val MAX_QUEUED = 2
        const val STATE_RINGING = "RINGING"
        const val STATE_OFFHOOK = "OFFHOOK"
        const val STATE_IDLE = "IDLE"
        val VALID_STATES = setOf(STATE_RINGING, STATE_OFFHOOK, STATE_IDLE)
        const val MAX_CALL_STATE_AGE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
