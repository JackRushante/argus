package dev.argus.engine.safety

import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher

/**
 * Confine unico per la shell autonoma approvata. Il template del comando è letterale nel
 * fingerprint: il contenuto del messaggio non sceglie il comando — al massimo fa da interruttore e,
 * se la regola interpola una variabile esterna, fornisce un ARGOMENTO già quotato
 * ([dev.argus.engine.model.ShellQuoting]), mai sintassi della shell. La domanda qui è dunque una
 * sola: **di chi ci fidiamo per premere l'interruttore?**
 *
 * Decisione di Lorenzo (2026-07-15), che revoca il proprio divieto precedente: un contatto
 * whitelistato può innescare un comando che lui stesso ha approvato alla lettera.
 *
 * Restano esclusi i canali la cui identità è **falsificabile**, e non per prudenza ma per un
 * limite reale: mittente SMS e caller ID si spoofano banalmente, mentre il `conversationId`
 * WhatsApp è una chiave stabile (E15). Esclusi anche i package non-WhatsApp (qualunque app può
 * postare una notifica) e i gruppi (identità del mittente ambigua, §10.3).
 *
 * I `when` sono deliberatamente **esaustivi**: una nuova famiglia di trigger non compila finché
 * qualcuno non decide, esplicitamente, se può innescare la shell.
 */
object StaticShellSafety {
    fun allows(trigger: Trigger, whitelistedConversationIds: Set<String>): Boolean = when (trigger) {
        // Immediate fira on-arm senza attore esterno: nessuno "preme l'interruttore" al posto
        // dell'utente che ha armato la regola, quindi è fidato come Time/Geofence/Connectivity/Sensor.
        is Trigger.Time, is Trigger.Immediate,
        is Trigger.Geofence, is Trigger.Connectivity, is Trigger.Sensor,
        // 0.4.1: eventi di sistema che solo l'utente o l'hardware locale provocano. `media` NO:
        // qualunque app o pagina web può far partire una riproduzione, quindi non è un'identità.
        is Trigger.Screen, is Trigger.Battery, is Trigger.AudioOutput, is Trigger.ModeChanged,
        // 0.4.1: l'avvio manuale è un gesto dell'utente sulla propria regola approvata (con token per
        // regola sulle scorciatoie e sui widget): nessun attore esterno preme l'interruttore.
        is Trigger.Manual,
        -> true
        is Trigger.Media -> false
        // 0.5.1: qualunque app può aprirne un'altra (un link, un'intent, uno schermo bloccato), quindi
        // «app aperta/chiusa» non è un'identità fidata e non può avviare la shell.
        is Trigger.AppEvent -> false
        is Trigger.Notification -> verifiedContact(
            pkg = trigger.pkg,
            conversationId = trigger.conversationId,
            isGroup = trigger.isGroup,
            whitelistedConversationIds = whitelistedConversationIds,
        )
        // Mittente SMS e caller ID sono spoofabili: nessuna whitelist può renderli un'identità.
        is Trigger.PhoneState -> false
    }

    fun allows(event: TriggerEvent, whitelistedConversationIds: Set<String>): Boolean = when (event) {
        is TriggerEvent.TimeFired,
        is TriggerEvent.ImmediateFired,
        is TriggerEvent.GeofenceTransitioned,
        is TriggerEvent.ConnectivityChanged,
        is TriggerEvent.SensorChanged,
        is TriggerEvent.ScreenChanged,
        is TriggerEvent.BatteryLevelChanged,
        is TriggerEvent.AudioOutputChanged,
        is TriggerEvent.DeviceModeChanged,
        is TriggerEvent.ManualFired,
        -> true
        is TriggerEvent.MediaPlaybackChanged -> false
        is TriggerEvent.AppForegroundChanged -> false
        is TriggerEvent.NotificationPosted -> verifiedContact(
            pkg = event.pkg,
            conversationId = event.conversationId,
            isGroup = event.isGroup,
            whitelistedConversationIds = whitelistedConversationIds,
        )
        is TriggerEvent.PhoneStateChanged -> false
    }

    /**
     * Verifica anche il legame fra trigger approvato ed evento live. Non basta che entrambi siano
     * separatamente fidati: una regressione di routing non deve permettere alla chat B di premere
     * l'interruttore congelato per la chat A.
     */
    fun allows(
        trigger: Trigger,
        event: TriggerEvent,
        whitelistedConversationIds: Set<String>,
    ): Boolean {
        if (!allows(trigger, whitelistedConversationIds) ||
            !allows(event, whitelistedConversationIds)
        ) return false
        return when {
            trigger is Trigger.Time && event is TriggerEvent.TimeFired -> true
            trigger is Trigger.Immediate && event is TriggerEvent.ImmediateFired -> true
            trigger is Trigger.Manual && event is TriggerEvent.ManualFired -> true
            trigger is Trigger.Geofence && event is TriggerEvent.GeofenceTransitioned ->
                trigger.transition == event.transition
            trigger is Trigger.Connectivity && event is TriggerEvent.ConnectivityChanged ->
                trigger.medium == event.medium && trigger.state == event.state &&
                    (trigger.match == null || trigger.match == event.name)
            trigger is Trigger.Sensor && event is TriggerEvent.SensorChanged ->
                trigger.kind == event.kind
            trigger is Trigger.Screen && event is TriggerEvent.ScreenChanged ->
                trigger.event == event.event
            trigger is Trigger.Battery && event is TriggerEvent.BatteryLevelChanged ->
                TriggerMatcher().matches(trigger, event)
            trigger is Trigger.AudioOutput && event is TriggerEvent.AudioOutputChanged ->
                TriggerMatcher().matches(trigger, event)
            trigger is Trigger.ModeChanged && event is TriggerEvent.DeviceModeChanged ->
                TriggerMatcher().matches(trigger, event)
            trigger is Trigger.Notification && event is TriggerEvent.NotificationPosted ->
                trigger.pkg == event.pkg &&
                    trigger.conversationId != null &&
                    trigger.conversationId == event.conversationId &&
                    trigger.isGroup == false && event.isGroup == false
            else -> false
        }
    }

    /** `isGroup` null = metadata non determinabile ⇒ non autorizzato (fail-closed, §10.3). */
    private fun verifiedContact(
        pkg: String,
        conversationId: String?,
        isGroup: Boolean?,
        whitelistedConversationIds: Set<String>,
    ): Boolean = pkg in DraftValidator.WHATSAPP_PACKAGES &&
        isGroup == false &&
        conversationId != null &&
        conversationId in whitelistedConversationIds
}
