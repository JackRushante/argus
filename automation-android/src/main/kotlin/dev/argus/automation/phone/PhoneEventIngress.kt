package dev.argus.automation.phone

import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.TriggerEnvelope

/** Frammento di SMS come arriva dal framework: i multipart vanno ricomposti per mittente. */
data class SmsPart(val sender: String?, val body: String?, val atMillis: Long)

/** Ultimo stato chiamata visto: i receiver manifest possono rinascere in un processo nuovo. */
interface CallStateStore {
    fun lastState(): String?
    fun record(state: String)
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
        val previous = callState.lastState()
        when (state) {
            STATE_RINGING, STATE_OFFHOOK, STATE_IDLE -> {
                // Il framework consegna lo stesso stato più volte (broadcast con/senza numero
                // a seconda dei grant): conta la TRANSIZIONE, non la consegna.
                if (state == previous) return
                callState.record(state)
            }
            else -> return
        }
        val event = when {
            state == STATE_RINGING -> PhoneEvent.INCOMING_CALL
            state == STATE_IDLE && (previous == STATE_RINGING || previous == STATE_OFFHOOK) ->
                PhoneEvent.CALL_ENDED
            else -> return
        }
        parser.parse(event, number, smsText = null, atMillis = atMillis)
            ?.let { dispatcher.dispatch(it) }
    }

    private companion object {
        const val STATE_RINGING = "RINGING"
        const val STATE_OFFHOOK = "OFFHOOK"
        const val STATE_IDLE = "IDLE"
    }
}
