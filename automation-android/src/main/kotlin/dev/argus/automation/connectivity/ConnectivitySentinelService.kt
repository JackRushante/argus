package dev.argus.automation.connectivity

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.R
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ConnectivitySentinelEntryPoint {
    fun connectivityEventIngress(): ConnectivityEventIngress
    fun connectivitySentinelStatus(): ConnectivitySentinelStatus
}

/**
 * Unica sentinella persistente per i trigger che Android non può consegnare a processo spento:
 * Wi-Fi (NetworkCallback) e alimentazione (receiver dinamico). Non conserva né logga SSID.
 */
class ConnectivitySentinelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wifiLock = Any()
    private val activeWifiNetworks = linkedSetOf<Network>()

    private lateinit var ingress: ConnectivityEventIngress
    private lateinit var status: ConnectivitySentinelStatus
    private lateinit var connectivityManager: ConnectivityManager
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var powerReceiverRegistered = false
    private var lastWifiName: String? = null
    private var startupFailed = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> ConnState.CONNECTED
                Intent.ACTION_POWER_DISCONNECTED -> ConnState.DISCONNECTED
                else -> return
            }
            observePower(state, initial = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            ConnectivitySentinelEntryPoint::class.java,
        )
        ingress = entry.connectivityEventIngress()
        status = entry.connectivitySentinelStatus()

        try {
            startAsForegroundService()
            registerPowerMonitoring()
            registerWifiMonitoring()
            status.setActive(true)
            Log.i(TAG, "sentinella attiva")
        } catch (error: SecurityException) {
            failStartup(error)
        } catch (error: RuntimeException) {
            failStartup(error)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (startupFailed) START_NOT_STICKY else START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanupRegistrations()
        if (::status.isInitialized) status.setActive(false)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "sentinella arrestata")
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.argus_connectivity_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.argus_connectivity_channel_description)
                setShowBadge(false)
            },
        )
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setContentTitle(getString(R.string.argus_connectivity_notification_title))
            .setContentText(getString(R.string.argus_connectivity_notification_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun registerPowerMonitoring() {
        currentPowerState()?.let { initial -> observePower(initial, initial = true) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        powerReceiverRegistered = true

        // Chiude la finestra tra snapshot e registrazione; l'ingress deduplica lo stesso stato.
        currentPowerState()?.let { current -> observePower(current, initial = false) }
    }

    private fun currentPowerState(): ConnState? = powerConnectionState(
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)),
    )

    private fun observePower(state: ConnState, initial: Boolean) {
        dispatchObservation(
            medium = ConnMedium.POWER,
            state = state,
            name = null,
            sourceIdentity = POWER_SOURCE_ID,
            initial = initial,
        )
    }

    private fun registerWifiMonitoring() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        val before = currentWifiNetworks()
        val beforeName = before.asSequence().mapNotNull(::ssidOf).firstOrNull()
        synchronized(wifiLock) {
            activeWifiNetworks.clear()
            activeWifiNetworks.addAll(before)
            lastWifiName = beforeName
        }
        observeWifi(
            state = if (before.isEmpty()) ConnState.DISCONNECTED else ConnState.CONNECTED,
            name = beforeName,
            initial = true,
        )

        val callback = createWifiCallback()
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            callback,
        )
        wifiCallback = callback

        // Se la rete cambia fra snapshot e callback, questo secondo snapshot recupera il bordo.
        reconcileWifiSnapshot(currentWifiNetworks())
    }

    private fun createWifiCallback(): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = handleWifiAvailable(network)

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = handleWifiCapabilities(network, networkCapabilities)

                override fun onLost(network: Network) = handleWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = handleWifiAvailable(network)

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = handleWifiCapabilities(network, networkCapabilities)

                override fun onLost(network: Network) = handleWifiLost(network)
            }
        }

    private fun currentWifiNetworks(): Set<Network> = connectivityManager.allNetworks
        .filterTo(linkedSetOf()) { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    private fun reconcileWifiSnapshot(current: Set<Network>) {
        val name = current.asSequence().mapNotNull(::ssidOf).firstOrNull()
        val previousConnected: Boolean
        synchronized(wifiLock) {
            previousConnected = activeWifiNetworks.isNotEmpty()
            activeWifiNetworks.clear()
            activeWifiNetworks.addAll(current)
            lastWifiName = name
        }
        val connected = current.isNotEmpty()
        if (connected != previousConnected || connected && name != null) {
            observeWifi(
                state = if (connected) ConnState.CONNECTED else ConnState.DISCONNECTED,
                name = name,
                initial = false,
            )
        }
    }

    private fun handleWifiAvailable(network: Network) {
        val name = ssidOf(network)
        val wasDisconnected: Boolean
        synchronized(wifiLock) {
            wasDisconnected = activeWifiNetworks.isEmpty()
            activeWifiNetworks += network
            if (name != null) lastWifiName = name
        }
        if (wasDisconnected || name != null) {
            observeWifi(ConnState.CONNECTED, name, initial = false)
        }
    }

    private fun handleWifiCapabilities(network: Network, capabilities: NetworkCapabilities) {
        val name = ssidFrom(capabilities)
        val wasDisconnected: Boolean
        synchronized(wifiLock) {
            wasDisconnected = activeWifiNetworks.isEmpty()
            activeWifiNetworks += network
            if (name != null) lastWifiName = name
        }
        if (wasDisconnected || name != null) {
            observeWifi(ConnState.CONNECTED, name, initial = false)
        }
    }

    private fun handleWifiLost(network: Network) {
        val disconnected: Boolean
        val disconnectedName: String?
        synchronized(wifiLock) {
            activeWifiNetworks -= network
            disconnected = activeWifiNetworks.isEmpty()
            disconnectedName = if (disconnected) lastWifiName else null
            if (disconnected) lastWifiName = null
        }
        if (disconnected) {
            observeWifi(ConnState.DISCONNECTED, disconnectedName, initial = false)
        }
    }

    private fun ssidOf(network: Network): String? =
        connectivityManager.getNetworkCapabilities(network)?.let(::ssidFrom)

    private fun ssidFrom(capabilities: NetworkCapabilities): String? =
        (capabilities.transportInfo as? WifiInfo)?.ssid
            ?.removeSurrounding("\"")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }

    private fun observeWifi(state: ConnState, name: String?, initial: Boolean) {
        dispatchObservation(
            medium = ConnMedium.WIFI,
            state = state,
            name = name,
            sourceIdentity = WIFI_SOURCE_ID,
            initial = initial,
        )
    }

    private fun dispatchObservation(
        medium: ConnMedium,
        state: ConnState,
        name: String?,
        sourceIdentity: String,
        initial: Boolean,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ingress.observe(
                    medium = medium,
                    connectionState = state,
                    name = name,
                    sourceIdentity = sourceIdentity,
                    atMillis = System.currentTimeMillis(),
                    initial = initial,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "dispatch $medium fallito: ${error::class.java.simpleName}")
            }
        }
    }

    private fun failStartup(error: RuntimeException) {
        startupFailed = true
        Log.e(TAG, "avvio sentinella fallito: ${error::class.java.simpleName}")
        cleanupRegistrations()
        status.setActive(false)
        stopSelf()
    }

    private fun cleanupRegistrations() {
        wifiCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        wifiCallback = null
        if (powerReceiverRegistered) {
            runCatching { unregisterReceiver(powerReceiver) }
            powerReceiverRegistered = false
        }
    }

    private companion object {
        const val TAG = "ArgusConnectivity"
        const val CHANNEL_ID = "argus_connectivity_sentinel"
        const val NOTIFICATION_ID = 0x41524743
        const val WIFI_SOURCE_ID = "device-wifi"
        const val POWER_SOURCE_ID = "device-power"
    }
}

/** Uno sticky snapshot assente/incompleto è ignoto: non va inventato come DISCONNECTED. */
internal fun powerConnectionState(battery: Intent?): ConnState? {
    if (battery == null || !battery.hasExtra(BatteryManager.EXTRA_PLUGGED)) return null
    return if (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) {
        ConnState.DISCONNECTED
    } else {
        ConnState.CONNECTED
    }
}
