// TriggerEvent.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Transition

sealed interface TriggerEvent {
    /** Eventi registrati presso l'OS: restano legati alla versione approvata che li ha creati. */
    sealed interface Registered : TriggerEvent {
        val automationId: AutomationId
        val approvalFingerprint: ApprovalFingerprint
    }

    /** Trigger schedulati per-automazione: portano l'id. */
    data class TimeFired(
        override val automationId: AutomationId,
        override val approvalFingerprint: ApprovalFingerprint,
    ) : Registered

    /** Fire una-tantum all'arm della regola immediate: mirror esatto di [TimeFired], porta l'id
     *  e il fingerprint approvato così il match e la revalidazione seguono lo stesso percorso. */
    data class ImmediateFired(
        override val automationId: AutomationId,
        override val approvalFingerprint: ApprovalFingerprint,
    ) : Registered

    data class GeofenceTransitioned(
        override val automationId: AutomationId,
        val transition: Transition,
        override val approvalFingerprint: ApprovalFingerprint,
    ) : Registered

    /** Evento già ridotto a transizione approvata: nessun sample o counter raw attraversa il core. */
    data class SensorChanged(
        override val automationId: AutomationId,
        val kind: SensorKind,
        override val approvalFingerprint: ApprovalFingerprint,
    ) : Registered
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
    data class PhoneStateChanged(
        val event: PhoneEvent,
        val number: String?,
        /** Testo SMS VOLATILE: vive in RAM per la durata del dispatch (serve all'azione
         *  clipboard P2-3). MAI persistito, MAI loggato, MAI in chiaro nell'event id. */
        val smsText: String? = null,
        /**
         * Slot SIM 1|2 da cui arriva l'evento (0.4.1), `null` = non determinabile. Una regola con
         * filtro `sim` NON scatta se qui è null (fail-closed).
         */
        val sim: Int? = null,
    ) : TriggerEvent
    data class ConnectivityChanged(val medium: ConnMedium, val state: ConnState, val name: String?) : TriggerEvent

    // --- 0.4.1: trigger senza Shizuku. Nessun dato personale: solo valori di sistema ridotti. ---

    data class ScreenChanged(val event: ScreenEvent) : TriggerEvent

    /** Cambio di livello batteria: il matcher decide se la soglia è stata ATTRAVERSATA. */
    data class BatteryLevelChanged(val previous: Int, val current: Int) : TriggerEvent

    /** [kind] è WIRED o BLUETOOTH (mai ANY): ANY esiste solo come filtro del trigger. */
    data class AudioOutputChanged(val kind: AudioOutputKind, val state: ConnState) : TriggerEvent

    /** [to] e [from] usano i valori di `StateKeys`; [from] null = valore precedente non noto. */
    data class DeviceModeChanged(val what: ModeKind, val to: String, val from: String?) : TriggerEvent

    data class MediaPlaybackChanged(val state: MediaPlayState) : TriggerEvent

    // --- 0.4.1: avvio manuale. ---

    /**
     * L'utente ha invocato la regola a mano. Come [TimeFired] porta id e impronta approvata, così il
     * motore verifica che la regola esista, sia armata e sia ancora quella approvata; il matcher lo
     * accetta SOLO su un trigger `manual`.
     */
    data class ManualFired(
        override val automationId: AutomationId,
        override val approvalFingerprint: ApprovalFingerprint,
    ) : Registered
}
