package dev.argus.engine.runtime
import dev.argus.engine.model.*

/** Verifica che un evento runtime soddisfi lo spec del trigger.
 *  I trigger schedulati (Time/Geofence) portano già l'automationId, quindi il match sullo spec è banale. */
class TriggerMatcher {
    fun matches(spec: Trigger, event: TriggerEvent): Boolean = when {
        spec is Trigger.Time && event is TriggerEvent.TimeFired -> true
        // ImmediateFired accettato SOLO su una regola immediate (id/fingerprint verificati a monte
        // dall'Engine, come per TimeFired): un ImmediateFired su qualsiasi altro trigger non matcha.
        spec is Trigger.Immediate && event is TriggerEvent.ImmediateFired -> true
        spec is Trigger.Geofence && event is TriggerEvent.GeofenceTransitioned -> spec.transition == event.transition
        spec is Trigger.Notification && event is TriggerEvent.NotificationPosted -> matchesNotification(spec, event)
        spec is Trigger.PhoneState && event is TriggerEvent.PhoneStateChanged ->
            spec.event == event.event &&
                // Filtro SIM (0.4.1): slot dell'evento ignoto => nessun match (fail-closed).
                (spec.sim == null || spec.sim == event.sim) &&
                (spec.number == null || numbersMatch(spec.number, event.number)) &&
                (
                    spec.textMatch == null ||
                        event.smsText?.contains(spec.textMatch, ignoreCase = true) == true
                    )
        spec is Trigger.Connectivity && event is TriggerEvent.ConnectivityChanged ->
            spec.medium == event.medium && spec.state == event.state &&
                (spec.match == null || spec.match == event.name)
        spec is Trigger.Sensor && event is TriggerEvent.SensorChanged -> spec.kind == event.kind
        spec is Trigger.Screen && event is TriggerEvent.ScreenChanged -> spec.event == event.event
        spec is Trigger.Battery && event is TriggerEvent.BatteryLevelChanged -> crossesThreshold(spec, event)
        spec is Trigger.AudioOutput && event is TriggerEvent.AudioOutputChanged ->
            spec.state == event.state && (spec.kind == AudioOutputKind.ANY || spec.kind == event.kind)
        spec is Trigger.ModeChanged && event is TriggerEvent.DeviceModeChanged ->
            spec.what == event.what && (spec.to == null || spec.to == event.to)
        spec is Trigger.Media && event is TriggerEvent.MediaPlaybackChanged -> spec.state == event.state
        // Avvio manuale (0.4.1): id e impronta sono verificati a monte dall'Engine; un ManualFired su
        // qualsiasi altro trigger non matcha, e un trigger manual non matcha nessun altro evento.
        spec is Trigger.Manual && event is TriggerEvent.ManualFired -> true
        // App aperta/chiusa (0.5.1): stesso package (confronto esatto) e stessa direzione.
        spec is Trigger.AppEvent && event is TriggerEvent.AppForegroundChanged ->
            spec.pkg == event.pkg && spec.event == event.event
        else -> false
    }

    /**
     * Una soglia scatta solo all'ATTRAVERSAMENTO: `below` N = prima >= N e ora < N; `above` N = prima
     * <= N e ora > N. Un salto che scavalca il valore (21 -> 19 con soglia 20) conta. Uno spec con
     * entrambe le soglie o con nessuna è malformato (il validatore lo rifiuta) e non scatta mai.
     */
    private fun crossesThreshold(spec: Trigger.Battery, event: TriggerEvent.BatteryLevelChanged): Boolean {
        val below = spec.below
        val above = spec.above
        return when {
            below != null && above == null -> event.previous >= below && event.current < below
            above != null && below == null -> event.previous <= above && event.current > above
            else -> false
        }
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

    /**
     * Confronto numeri robusto ai formati (+39 / spazi / trattini): solo cifre. Cifre identiche
     * combaciano sempre; il match per suffisso vale solo con prefisso internazionale plausibile,
     * cioe' il numero corto ha almeno [MIN_SUFFIX_DIGITS] cifre e quello lungo ne ha in piu'
     * al massimo [MAX_PREFIX_DIGITS] davanti (es. `39`, `0039`). Un numero corto non combacia
     * piu' con qualunque numero lungo che finisce con le sue cifre.
     */
    private fun numbersMatch(spec: String, actual: String?): Boolean {
        if (actual == null) return false
        val a = spec.filter(Char::isDigit); val b = actual.filter(Char::isDigit)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (shorter.length < MIN_SUFFIX_DIGITS) return false
        if (longer.length - shorter.length > MAX_PREFIX_DIGITS) return false
        return longer.endsWith(shorter)
    }

    private companion object {
        const val MIN_SUFFIX_DIGITS = 9
        const val MAX_PREFIX_DIGITS = 4
    }
}
