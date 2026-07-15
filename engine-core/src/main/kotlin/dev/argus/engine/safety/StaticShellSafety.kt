package dev.argus.engine.safety

import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.TriggerEvent

/**
 * Confine unico per la shell autonoma approvata. Il comando è sempre letterale nel fingerprint;
 * restano esclusi tutti i trigger che trasportano contenuto controllabile da un mittente esterno.
 */
object StaticShellSafety {
    fun allows(trigger: Trigger): Boolean = when (trigger) {
        is Trigger.Time, is Trigger.Geofence, is Trigger.Connectivity -> true
        is Trigger.Notification, is Trigger.PhoneState -> false
    }

    fun allows(event: TriggerEvent): Boolean = when (event) {
        is TriggerEvent.TimeFired,
        is TriggerEvent.GeofenceTransitioned,
        is TriggerEvent.ConnectivityChanged,
        -> true
        is TriggerEvent.NotificationPosted, is TriggerEvent.PhoneStateChanged -> false
    }
}
