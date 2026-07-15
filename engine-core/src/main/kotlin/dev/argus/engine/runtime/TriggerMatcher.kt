package dev.argus.engine.runtime
import dev.argus.engine.model.*

/** Verifica che un evento runtime soddisfi lo spec del trigger.
 *  I trigger schedulati (Time/Geofence) portano già l'automationId, quindi il match sullo spec è banale. */
class TriggerMatcher {
    fun matches(spec: Trigger, event: TriggerEvent): Boolean = when {
        spec is Trigger.Time && event is TriggerEvent.TimeFired -> true
        spec is Trigger.Geofence && event is TriggerEvent.GeofenceTransitioned -> spec.transition == event.transition
        spec is Trigger.Notification && event is TriggerEvent.NotificationPosted -> matchesNotification(spec, event)
        spec is Trigger.PhoneState && event is TriggerEvent.PhoneStateChanged ->
            spec.event == event.event &&
                (spec.number == null || numbersMatch(spec.number, event.number)) &&
                (
                    spec.textMatch == null ||
                        event.smsText?.contains(spec.textMatch, ignoreCase = true) == true
                    )
        spec is Trigger.Connectivity && event is TriggerEvent.ConnectivityChanged ->
            spec.medium == event.medium && spec.state == event.state &&
                (spec.match == null || spec.match == event.name)
        else -> false
    }

    private fun matchesNotification(spec: Trigger.Notification, e: TriggerEvent.NotificationPosted): Boolean {
        if (spec.pkg != e.pkg) return false
        if (spec.isGroup != null && spec.isGroup != e.isGroup) return false
        // Identità: conversationId (chiave stabile, spec E15) ha precedenza sul display name spoofabile.
        val identityOk = when {
            spec.conversationId != null -> spec.conversationId == e.conversationId
            spec.sender != null -> spec.sender == e.sender
            else -> true
        }
        if (!identityOk) return false
        if (spec.titleMatch != null && e.title?.contains(spec.titleMatch, ignoreCase = true) != true) return false
        if (spec.textMatch != null && e.text?.contains(spec.textMatch, ignoreCase = true) != true) return false
        return true
    }

    /** Confronto numeri robusto ai formati (+39 / spazi / trattini): solo cifre, match per suffisso (min 7). */
    private fun numbersMatch(spec: String, actual: String?): Boolean {
        if (actual == null) return false
        val a = spec.filter(Char::isDigit); val b = actual.filter(Char::isDigit)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length < 7 || b.length < 7) return a == b
        return a.endsWith(b) || b.endsWith(a)
    }
}
