package dev.argus.automation.notification

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Se il listener delle notifiche è collegato ORA, in questo processo (0.7.2). Lo scrive il servizio a
 * ogni `onListenerConnected` / `onListenerDisconnected`; un processo appena nato parte da «non collegato»
 * finché il sistema non lo collega.
 */
object NotificationListenerConnection {
    private val connected = AtomicBoolean(false)

    val isConnected: Boolean get() = connected.get()

    fun onConnected() = connected.set(true)

    fun onDisconnected() = connected.set(false)
}

/**
 * Alcuni gestori batteria dei produttori scollegano il listener senza revocare l'accesso: le regole
 * notifica restano armate e non scattano più. Il servizio chiede un nuovo collegamento appena viene
 * scollegato; questo controllo periodico (stesso schema della manutenzione: al ritorno in primo piano e
 * a fine dispatch, al massimo una volta per intervallo) lo richiede quando serve davvero: regole
 * notifica armate, accesso concesso, listener non collegato.
 */
class NotificationListenerWatchdog(
    private val notificationRulesArmed: suspend () -> Boolean,
    private val accessGranted: () -> Boolean,
    private val connected: () -> Boolean,
    private val requestRebind: () -> Unit,
) {
    /** `true` se ha chiesto un nuovo collegamento. */
    suspend fun check(): Boolean {
        if (connected() || !accessGranted()) return false
        if (!notificationRulesArmed()) return false
        requestRebind()
        return true
    }
}
