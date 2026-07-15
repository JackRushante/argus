package dev.argus.engine.phone

import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Trasforma eventi di telefonia (chiamate, SMS) in envelope fail-closed — pattern del
 * NotificationEventParser. Il testo SMS è VOLATILE: viaggia nell'evento solo in RAM per la
 * durata del dispatch (serve all'estrazione clipboard di P2-3); nell'event id entra soltanto
 * come digest, mai in chiaro, e lo stesso vale per il numero.
 */
class PhoneEventParser {
    fun parse(
        event: PhoneEvent,
        number: String?,
        smsText: String?,
        atMillis: Long,
    ): TriggerEnvelope? {
        val safeNumber = number
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(MAX_NUMBER_CHARS)
            ?.takeIf(String::isNotEmpty)
        // Come per le notifiche: multiriga legittimo negli SMS, ogni altro control char via.
        val safeText = smsText
            ?.filter { !it.isISOControl() || it == '\n' || it == '\t' }
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
            ?.takeIf(String::isNotEmpty)
        val identityFields = when (event) {
            PhoneEvent.SMS_RECEIVED -> arrayOf(
                event.name,
                safeNumber.orEmpty(),
                safeText.orEmpty(),
                atMillis.coerceAtLeast(0).toString(),
            )
            // PHONE_STATE può consegnare la stessa transizione due volte, prima anonima e poi
            // col numero. Il numero è payload per il matcher, non identità della chiamata.
            PhoneEvent.INCOMING_CALL, PhoneEvent.CALL_ENDED -> arrayOf(
                event.name,
                atMillis.coerceAtLeast(0).toString(),
            )
        }
        val id = TriggerEventId("phone:" + digest(*identityFields))
        return TriggerEnvelope(
            id,
            TriggerEvent.PhoneStateChanged(event = event, number = safeNumber, smsText = safeText),
        )
    }

    private fun digest(vararg values: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            messageDigest.update(value.toByteArray(StandardCharsets.UTF_8))
            messageDigest.update(0)
        }
        return messageDigest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_NUMBER_CHARS = 32
        const val MAX_TEXT_CHARS = 4_096
    }
}
