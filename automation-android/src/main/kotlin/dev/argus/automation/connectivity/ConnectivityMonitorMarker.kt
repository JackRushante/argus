package dev.argus.automation.connectivity

import android.content.Context

/**
 * Marker persistente «il monitor Connectivity è attivo in continuo» (D11).
 *
 * Il servizio lo legge in `onCreate`: se è `true` il monitor precedente è morto senza uno stop
 * esplicito (kill, update, force-stop), quindi un cambio rispetto allo stato salvato è una
 * transizione realmente persa e va recuperata. Se è `false` il monitor parte da spento (prima
 * regola armata, oppure riavvio dopo uno stop): lo stato salvato può essere vecchio di giorni e
 * la prima osservazione è solo un RIFERIMENTO, mai un dispatch.
 *
 * Lo azzera solo lo stop esplicito della domanda (backend `stop()`), non `onDestroy` del servizio:
 * una distruzione decisa dal sistema equivale a un kill.
 */
interface ConnectivityMonitorMarker {
    fun wasActive(): Boolean
    fun setActive(active: Boolean)
}

/** `commit()` sincrono: il processo può morire subito dopo l'avvio del monitor. */
class PrefsConnectivityMonitorMarker(context: Context) : ConnectivityMonitorMarker {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun wasActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    override fun setActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_ACTIVE, active).commit()
    }

    private companion object {
        const val PREFS_NAME = "argus_connectivity_monitor"
        const val KEY_ACTIVE = "monitor_active"
    }
}
