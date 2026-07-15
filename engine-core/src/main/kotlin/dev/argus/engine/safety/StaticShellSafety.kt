package dev.argus.engine.safety

import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.TriggerEvent

/**
 * Confine unico per la shell autonoma approvata. Il comando è sempre letterale nel fingerprint:
 * l'injection resta impossibile per costruzione, perché il contenuto del messaggio non sceglie
 * mai il comando — al massimo fa da interruttore. La domanda qui è dunque una sola: **di chi ci
 * fidiamo per premere l'interruttore?**
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
        is Trigger.Time, is Trigger.Geofence, is Trigger.Connectivity -> true
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
        is TriggerEvent.GeofenceTransitioned,
        is TriggerEvent.ConnectivityChanged,
        -> true
        is TriggerEvent.NotificationPosted -> verifiedContact(
            pkg = event.pkg,
            conversationId = event.conversationId,
            isGroup = event.isGroup,
            whitelistedConversationIds = whitelistedConversationIds,
        )
        is TriggerEvent.PhoneStateChanged -> false
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
