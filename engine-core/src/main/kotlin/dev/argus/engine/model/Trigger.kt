package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Transition { ENTER, EXIT, DWELL }
enum class PhoneEvent { INCOMING_CALL, CALL_ENDED, SMS_RECEIVED }
enum class ConnMedium { WIFI, BT, POWER }
/** Per POWER: CONNECTED = alimentazione collegata. */
enum class ConnState { CONNECTED, DISCONNECTED }
enum class TimePrecision { FLEXIBLE, EXACT }

@Serializable
sealed interface Trigger {
    @Serializable @SerialName("geofence")
    data class Geofence(
        val lat: Double = 0.0, val lng: Double = 0.0, val radiusM: Double,
        val transition: Transition, val loiteringDelayMs: Long = 0,
        /** true = coordinate risolte dall'app all'ARM con la posizione corrente (spec §7 rev 3). */
        val resolveCurrentLocation: Boolean = false,
    ) : Trigger

    /** Esattamente uno tra [cron] e [at] (enforced dal DraftValidator).
     *  [at] = datetime ISO locale ("2026-07-15T08:00") interpretato in [tz], one-shot. */
    @Serializable @SerialName("time")
    data class Time(
        val cron: String? = null,
        val at: String? = null,
        val tz: String,
        /** EXACT solo se l'utente richiede esplicitamente puntualità; altrimenti allarme inexact. */
        val precision: TimePrecision = TimePrecision.FLEXIBLE,
    ) : Trigger

    @Serializable @SerialName("notification")
    data class Notification(
        val pkg: String,
        /** Chiave stabile della conversazione (shortcutId/JID, spec E15) — preferita per l'identità. */
        val conversationId: String? = null,
        /** Display name: fallback SPOOFABILE, il validator lo marca WARNING. */
        val sender: String? = null,
        /** null = qualsiasi; false obbligatorio per le reply generative (spec §10.3). */
        val isGroup: Boolean? = null,
        val titleMatch: String? = null, val textMatch: String? = null,
    ) : Trigger

    @Serializable @SerialName("phone_state")
    data class PhoneState(
        val event: PhoneEvent,
        val number: String? = null,
        /** Filtro contains case-insensitive sul testo dell'SMS: valido solo con SMS_RECEIVED. */
        val textMatch: String? = null,
    ) : Trigger

    @Serializable @SerialName("connectivity")
    data class Connectivity(val medium: ConnMedium, val state: ConnState, val match: String? = null) : Trigger
}
