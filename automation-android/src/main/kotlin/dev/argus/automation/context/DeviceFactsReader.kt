package dev.argus.automation.context

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.argus.automation.calendar.CalendarInstanceSource
import dev.argus.automation.connectivity.wifiSsidFrom
import dev.argus.automation.triggers.AudioOutputClassifier
import dev.argus.automation.triggers.AudioPort
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.runtime.BluetoothLink
import dev.argus.engine.runtime.BluetoothLinks
import dev.argus.engine.runtime.CalendarInstance
import dev.argus.engine.runtime.DeviceFact
import dev.argus.engine.runtime.DeviceFacts
import dev.argus.engine.runtime.WifiLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Legge i [DeviceFact] chiesti da una regola. Un fatto non leggibile resta null (UNKNOWN). */
fun interface DeviceFactsReader {
    suspend fun read(facts: Set<DeviceFact>): DeviceFacts
}

/**
 * Letture separate, ognuna fallisce da sola:
 * - Wi-Fi: reti con trasporto Wi-Fi (`ACCESS_NETWORK_STATE`, nessun permesso runtime). Il nome solo se
 *   chiesto, con la stessa callback `FLAG_INCLUDE_LOCATION_INFO` della sentinella del trigger connectivity
 *   (serve la posizione precisa); senza, il nome resta null.
 * - Bluetooth: `BLUETOOTH_CONNECT` (Android 12+); adattatore spento = nessun dispositivo; poi i profili
 *   audio, apparecchi acustici e GATT dell'adattatore più i collegamenti ACL visti dal receiver del trigger.
 * - Cuffie: `AudioManager.getDevices`, classificate come il trigger `audio_output`. Nessun permesso.
 * - Calendario: le istanze in corso adesso, con `READ_CALENDAR`.
 */
class AndroidDeviceFactsReader(
    context: Context,
    private val calendar: CalendarInstanceSource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DeviceFactsReader {
    private val appContext = context.applicationContext

    override suspend fun read(facts: Set<DeviceFact>): DeviceFacts = DeviceFacts(
        wifi = if (DeviceFact.WIFI in facts || DeviceFact.WIFI_SSID in facts) {
            safely { readWifi(withName = DeviceFact.WIFI_SSID in facts) }
        } else {
            null
        },
        bluetooth = if (DeviceFact.BLUETOOTH in facts) safely { readBluetooth() } else null,
        audioOutputs = if (DeviceFact.AUDIO_OUTPUTS in facts) safely { readAudioOutputs() } else null,
        calendarNow = if (DeviceFact.CALENDAR_NOW in facts) safely { readCalendarNow() } else null,
    )

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    // --- Wi-Fi -----------------------------------------------------------------------------------

    private suspend fun readWifi(withName: Boolean): WifiLink? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val connected = manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (!connected || !withName) return WifiLink(connected)
        return WifiLink(true, readSsid(manager))
    }

    private suspend fun readSsid(manager: ConnectivityManager): String? {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return legacySsid()
        var registered: ConnectivityManager.NetworkCallback? = null
        return try {
            withTimeoutOrNull(SSID_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                            val name = wifiSsidFrom(capabilities) ?: return
                            if (continuation.isActive) continuation.resume(name)
                        }
                    }
                    registered = callback
                    manager.registerNetworkCallback(
                        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                        callback,
                    )
                }
            }
        } finally {
            registered?.let { callback -> runCatching { manager.unregisterNetworkCallback(callback) } }
        }
    }

    /** Android 11: `getConnectionInfo` porta ancora il nome con la posizione concessa. */
    @Suppress("DEPRECATION")
    private fun legacySsid(): String? =
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
            ?.removeSurrounding("\"")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }

    // --- Bluetooth -------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun readBluetooth(): BluetoothLinks? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            return null
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: return BluetoothLinks(emptyList(), complete = true)
        val adapter = manager.adapter ?: return BluetoothLinks(emptyList(), complete = true)
        if (!adapter.isEnabled) return BluetoothLinks(emptyList(), complete = true)
        var complete = true
        val devices = linkedMapOf<String, BluetoothLink>()
        fun add(device: BluetoothDevice) {
            val address = device.address?.uppercase() ?: return
            devices[address] = BluetoothLink(address, runCatching { device.name }.getOrNull())
        }
        PROFILES.forEach { profile ->
            // Lo stato del profilo dice già «nessun dispositivo collegato» senza aprire il proxy. Serve: su un
            // telefono che non attiva un profilo (LE Audio, apparecchi acustici) il proxy non risponde mai e il
            // fatto restava UNKNOWN anche senza nessun dispositivo collegato. Ogni altro stato chiede al proxy.
            if (runCatching { adapter.getProfileConnectionState(profile) }.getOrNull() ==
                BluetoothProfile.STATE_DISCONNECTED
            ) return@forEach
            val connected = profileDevices(adapter, profile)
            if (connected == null) complete = false else connected.forEach(::add)
        }
        runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }
            .onSuccess { it.forEach(::add) }
            .onFailure { complete = false }
        BluetoothConnectionTracker.connected().forEach { link ->
            val address = link.address ?: return@forEach
            if (address !in devices) devices[address] = link
        }
        return BluetoothLinks(devices.values.toList(), complete)
    }

    /** I dispositivi collegati su un profilo; vuota se il profilo non c'è, null se non risponde. */
    @SuppressLint("MissingPermission")
    private suspend fun profileDevices(adapter: BluetoothAdapter, profile: Int): List<BluetoothDevice>? {
        var proxy: BluetoothProfile? = null
        return try {
            withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<BluetoothDevice>?> { continuation ->
                    val listener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(connectedProfile: Int, service: BluetoothProfile) {
                            proxy = service
                            val result = runCatching { service.connectedDevices.orEmpty() }.getOrNull()
                            if (continuation.isActive) {
                                continuation.resume(result)
                            } else {
                                // Arrivato dopo il timeout: il finally è già passato.
                                runCatching { adapter.closeProfileProxy(connectedProfile, service) }
                            }
                        }

                        override fun onServiceDisconnected(disconnectedProfile: Int) = Unit
                    }
                    if (!adapter.getProfileProxy(appContext, listener, profile)) {
                        // Profilo non supportato dal telefono: nessun dispositivo può esserci collegato.
                        continuation.resume(emptyList())
                    }
                }
            }
        } finally {
            proxy?.let { service -> runCatching { adapter.closeProfileProxy(profile, service) } }
        }
    }

    // --- Cuffie ----------------------------------------------------------------------------------

    private fun readAudioOutputs(): Set<AudioOutputKind>? {
        val manager = appContext.getSystemService(AudioManager::class.java) ?: return null
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNullTo(linkedSetOf()) { device ->
                AudioOutputClassifier.kindOf(AudioPort(device.id, device.type, device.isSink))
            }
    }

    // --- Calendario ------------------------------------------------------------------------------

    private fun readCalendarNow(): List<CalendarInstance>? {
        val now = nowMillis()
        return calendar.read(now, now + 1)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SSID_TIMEOUT_MS = 1_500L
        const val PROFILE_TIMEOUT_MS = 1_500L

        /** A2DP, cuffie con microfono, apparecchi acustici e LE Audio (valori di `BluetoothProfile`). */
        val PROFILES: List<Int> = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            add(BluetoothProfile.HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
        }
    }
}
