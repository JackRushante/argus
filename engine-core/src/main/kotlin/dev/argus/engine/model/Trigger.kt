package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Transition { ENTER, EXIT, DWELL }
enum class PhoneEvent { INCOMING_CALL, CALL_ENDED, SMS_RECEIVED }
enum class ConnMedium { WIFI, BT, POWER }
/** Per POWER: CONNECTED = alimentazione collegata. */
enum class ConnState { CONNECTED, DISCONNECTED }
enum class TimePrecision { FLEXIBLE, EXACT }

/** Famiglie sensore ammesse nel dominio. I sensori raw/high-rate non sono rappresentabili. */
@Serializable
enum class SensorKind(val wireName: String) {
    @SerialName("significant_motion") SIGNIFICANT_MOTION("significant_motion"),
    @SerialName("stationary_detect") STATIONARY_DETECT("stationary_detect"),
    @SerialName("motion_detect") MOTION_DETECT("motion_detect"),
    @SerialName("step_detector") STEP_DETECTOR("step_detector"),
    @SerialName("step_counter") STEP_COUNTER("step_counter"),
}

object SensorTriggerPolicy {
    const val MIN_COOLDOWN_MS = 60_000L
    const val MAX_COOLDOWN_MS = 7 * 24 * 60 * 60 * 1_000L
    const val MAX_EVENT_COUNT = 100_000

    fun validEventCount(kind: SensorKind, minimumEventCount: Int): Boolean = when (kind) {
        SensorKind.SIGNIFICANT_MOTION,
        SensorKind.STATIONARY_DETECT,
        SensorKind.MOTION_DETECT,
        -> minimumEventCount == 1
        SensorKind.STEP_DETECTOR,
        SensorKind.STEP_COUNTER,
        -> minimumEventCount in 1..MAX_EVENT_COUNT
    }
}

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

    /**
     * Fire UNA VOLTA all'arm della regola: nessun orario, nessun cron/at, nessuna corsa contro
     * l'orologio. Serve quando un one-shot "adesso" non è schedulabile (l'istante scappa nel
     * passato tra bozza→approva→schedula, generando "pianificazione non riuscita"). L'immediato
     * fira on-arm, deterministico. L'orario dell'eventuale sveglia/timer sta nell'AZIONE
     * (SetAlarm/SetTimer), NON nel trigger.
     */
    @Serializable @SerialName("immediate")
    data object Immediate : Trigger

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

    /**
     * Il runtime emette soltanto l'evento aggregato: nessun valore raw del sensore entra nel
     * TriggerEvent, nel journal o nell'event id. [minimumEventCount] vale solo per le famiglie step.
     */
    @Serializable @SerialName("sensor")
    data class Sensor(
        val kind: SensorKind,
        val minimumEventCount: Int = 1,
        /** Riservati a una futura slice batched; P3-2A li rifiuta invece di fingere supporto. */
        val samplingPeriodUs: Int? = null,
        val maxReportLatencyUs: Int? = null,
    ) : Trigger
}
