// TriggerEvent.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Transition

sealed interface TriggerEvent {
    /** Trigger schedulati per-automazione: portano l'id. */
    data class TimeFired(val automationId: AutomationId) : TriggerEvent
    data class GeofenceTransitioned(val automationId: AutomationId, val transition: Transition) : TriggerEvent
    /** Trigger broadcast: nessun id, richiedono match sullo spec. */
    data class NotificationPosted(
        val pkg: String,
        val conversationId: String? = null,   // chiave stabile estratta dalla notifica (E15)
        val sender: String? = null,           // display name
        val title: String? = null,
        val text: String? = null,
        /** null = metadata non determinabile. Le policy di reply devono trattarlo come non autorizzato. */
        val isGroup: Boolean? = null,
        /** Chiave della StatusBarNotification: l'executor la usa per recuperare il RemoteInput
         *  al momento della reply (P1). Senza, WhatsAppReply non è eseguibile. */
        val notificationKey: String? = null,
    ) : TriggerEvent
    data class PhoneStateChanged(val event: PhoneEvent, val number: String?) : TriggerEvent
    data class ConnectivityChanged(val medium: ConnMedium, val state: ConnState, val name: String?) : TriggerEvent
}
