package dev.argus.engine.model
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Transition { ENTER, EXIT, DWELL }
/** MISSED_CALL: squillo → idle senza risposta (0.4.1); anche un rifiuto manuale finisce così. */
enum class PhoneEvent { INCOMING_CALL, CALL_ENDED, SMS_RECEIVED, MISSED_CALL }
enum class ConnMedium { WIFI, BT, POWER }
/** Per POWER: CONNECTED = alimentazione collegata. */
enum class ConnState { CONNECTED, DISCONNECTED }
enum class TimePrecision { FLEXIBLE, EXACT }

/** Eventi dello schermo (0.4.1): acceso, spento, sbloccato (`ACTION_USER_PRESENT`). */
enum class ScreenEvent { ON, OFF, UNLOCKED }

/**
 * Tipo di uscita audio personale (0.4.1). WIRED = cuffie/auricolari a filo o USB headset;
 * BLUETOOTH = A2DP o LE audio; ANY = una qualsiasi delle due. L'altoparlante interno non conta mai.
 */
enum class AudioOutputKind { WIRED, BLUETOOTH, ANY }

/** Cosa osserva un trigger `mode_changed` (0.4.1): Non disturbare o suoneria. */
enum class ModeKind {
    DND, RINGER;

    /** Valori ammessi per `mode_changed.to` / evento: stessi di [StateKeys] (dnd e ringer). */
    val allowedValues: Set<String>
        get() = when (this) {
            DND -> setOf("off", "priority", "total")
            RINGER -> setOf("normal", "vibrate", "silent")
        }
}

/** Riproduzione multimediale (uso MEDIA): in corso o terminata (0.4.1). */
enum class MediaPlayState { PLAYING, STOPPED }

/** Limiti del trigger `battery` (0.4.1): la soglia è un livello percentuale intero. */
object BatteryTriggerPolicy {
    const val MIN_THRESHOLD = 1
    const val MAX_THRESHOLD = 99
    fun validThreshold(value: Int): Boolean = value in MIN_THRESHOLD..MAX_THRESHOLD
}

/** Slot SIM ammessi dal filtro `sim` di `phone_state` (0.4.1): 1 o 2. */
object SimSlotPolicy {
    const val MIN_SLOT = 1
    const val MAX_SLOT = 2
    fun validSlot(value: Int): Boolean = value in MIN_SLOT..MAX_SLOT
}

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

    /** Esattamente uno tra [cron], [at] e [afterMs] (enforced dal DraftValidator).
     *  [at] = datetime ISO locale ("2026-07-15T08:00") interpretato in [tz], one-shot.
     *  [afterMs] = ritardo relativo in ms, one-shot, ancorato all'arm (ri-armabile). */
    @Serializable @SerialName("time")
    data class Time(
        val cron: String? = null,
        val at: String? = null,
        // @EncodeDefault(NEVER): quando null è OMESSO dal wire, così i Time cron/at esistenti
        // restano byte-for-byte identici e i fingerprint v1 pinnati non si rompono.
        @EncodeDefault(EncodeDefault.Mode.NEVER) val afterMs: Long? = null,
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
        /**
         * Filtro slot SIM 1|2 (0.4.1). @EncodeDefault(NEVER): quando null è OMESSO dal wire, così i
         * phone_state esistenti restano byte-for-byte identici e le impronte non cambiano. Se lo slot
         * dell'evento non è determinabile la regola NON scatta (fail-closed).
         */
        @EncodeDefault(EncodeDefault.Mode.NEVER) val sim: Int? = null,
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

    /** Schermo acceso/spento/sbloccato (0.4.1). Receiver dinamico, nessun permesso. */
    @Serializable @SerialName("screen")
    data class Screen(val event: ScreenEvent) : Trigger

    /**
     * Soglia batteria (0.4.1): esattamente uno fra [below] e [above], 1..99. Scatta una volta per
     * ATTRAVERSAMENTO: `below` N = il livello precedente era >= N e l'attuale è < N; `above` N =
     * il precedente era <= N e l'attuale è > N.
     */
    @Serializable @SerialName("battery")
    data class Battery(val below: Int? = null, val above: Int? = null) : Trigger

    /** Uscita audio personale collegata/scollegata (0.4.1); lo stato riusa [ConnState]. */
    @Serializable @SerialName("audio_output")
    data class AudioOutput(val kind: AudioOutputKind, val state: ConnState) : Trigger

    /**
     * Cambio di modalità (0.4.1): Non disturbare o suoneria. [to] usa i valori di
     * [StateKeys] (dnd `off|priority|total`, ringer `normal|vibrate|silent`); null = qualsiasi cambio.
     */
    @Serializable @SerialName("mode_changed")
    data class ModeChanged(val what: ModeKind, val to: String? = null) : Trigger

    /** Riproduzione multimediale iniziata/finita (0.4.1), con antirimbalzo fra un brano e l'altro. */
    @Serializable @SerialName("media")
    data class Media(val state: MediaPlayState) : Trigger
}

/** One-shot = fira una volta e si auto-consuma: `at` assoluto o `afterMs` relativo (mai cron ricorrente). */
fun Trigger.Time.isOneShot(): Boolean = at != null || afterMs != null
