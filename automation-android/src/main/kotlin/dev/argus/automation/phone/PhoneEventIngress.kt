package dev.argus.automation.phone

import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.TriggerEnvelope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Frammento di SMS come arriva dal framework: i multipart vanno ricomposti per mittente. */
data class SmsPart(val sender: String?, val body: String?, val atMillis: Long)

/** Ultima transizione chiamata: sopravvive al processo per dedupe e numero su CALL_ENDED. */
data class CallStateSnapshot(
    val state: String,
    val number: String?,
    val transitionAtMillis: Long,
)

interface CallStateStore {
    fun last(): CallStateSnapshot?
    fun record(snapshot: CallStateSnapshot)
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
    private val callMutex = Mutex()

    suspend fun onSms(parts: List<SmsPart>) {
        parts
            .filter { !it.body.isNullOrBlank() }
            .groupBy { it.sender }
            .forEach { (sender, senderParts) ->
                val text = senderParts.joinToString("") { it.body.orEmpty() }
                val atMillis = senderParts.minOf { it.atMillis }
                parser.parse(PhoneEvent.SMS_RECEIVED, sender, text, atMillis)
                    ?.let { dispatcher.dispatch(it) }
            }
    }

    suspend fun onCallStateChanged(state: String, number: String?, atMillis: Long) {
        val envelope = callMutex.withLock {
            if (state !in VALID_STATES) return@withLock null
            val safeAtMillis = atMillis.coerceAtLeast(0)
            val stored = callState.last()
            val previous = stored?.takeUnless {
                safeAtMillis - it.transitionAtMillis > MAX_CALL_STATE_AGE_MILLIS
            }

            if (state == previous?.state) {
                // Su RINGING il framework può consegnare prima il broadcast anonimo e poi quello
                // col numero. Riemettiamo l'envelope arricchito con la STESSA identità: le regole
                // generiche restano idempotenti, quelle filtrate per numero possono finalmente
                // fare match.
                if (state == STATE_RINGING && previous.number == null && number != null) {
                    val enriched = previous.copy(number = number)
                    callState.record(enriched)
                    return@withLock parser.parse(
                        PhoneEvent.INCOMING_CALL,
                        number,
                        smsText = null,
                        atMillis = enriched.transitionAtMillis,
                    )
                }
                return@withLock null
            }

            val retainedNumber = number ?: previous?.number
            val snapshot = CallStateSnapshot(
                state = state,
                number = retainedNumber.takeUnless { state == STATE_IDLE },
                transitionAtMillis = safeAtMillis,
            )
            callState.record(snapshot)

            when {
                state == STATE_RINGING -> parser.parse(
                    PhoneEvent.INCOMING_CALL,
                    retainedNumber,
                    smsText = null,
                    atMillis = safeAtMillis,
                )
                state == STATE_IDLE && previous?.state in setOf(STATE_RINGING, STATE_OFFHOOK) ->
                    parser.parse(
                        PhoneEvent.CALL_ENDED,
                        retainedNumber,
                        smsText = null,
                        atMillis = safeAtMillis,
                    )
                else -> null
            }
        }
        envelope?.let { dispatcher.dispatch(it) }
    }

    private companion object {
        const val STATE_RINGING = "RINGING"
        const val STATE_OFFHOOK = "OFFHOOK"
        const val STATE_IDLE = "IDLE"
        val VALID_STATES = setOf(STATE_RINGING, STATE_OFFHOOK, STATE_IDLE)
        const val MAX_CALL_STATE_AGE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
