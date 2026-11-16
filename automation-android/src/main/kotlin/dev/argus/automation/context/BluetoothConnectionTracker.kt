package dev.argus.automation.context

import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.BluetoothLink
import java.util.concurrent.ConcurrentHashMap

/**
 * I collegamenti ACL visti da [dev.argus.automation.connectivity.BluetoothConnectivityReceiver] (lo stesso
 * broadcast del trigger connectivity Bluetooth) durante la vita del processo. Solo in RAM, mai persistiti:
 * un processo nuovo riparte vuoto e la condizione si appoggia alla query dei profili dell'adattatore.
 */
object BluetoothConnectionTracker {
    private val connected = ConcurrentHashMap<String, Named>()

    private data class Named(val name: String?)

    fun observe(address: String, name: String?, state: ConnState) {
        val key = address.uppercase()
        when (state) {
            ConnState.CONNECTED -> connected[key] = Named(name)
            ConnState.DISCONNECTED -> connected.remove(key)
        }
    }

    fun connected(): List<BluetoothLink> = connected.map { (address, named) -> BluetoothLink(address, named.name) }

    /** Solo per i test. */
    internal fun clear() = connected.clear()
}
