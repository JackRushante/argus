package dev.argus.automation.connectivity

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.di.ApplicationScope
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ConnectivityIngressEntryPoint {
    fun connectivityEventIngress(): ConnectivityEventIngress

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}

private fun entryPoint(context: Context): ConnectivityIngressEntryPoint = EntryPointAccessors
    .fromApplication(context.applicationContext, ConnectivityIngressEntryPoint::class.java)

/** Riceve soltanto le transizioni ACL protette dal sistema; nome e address non entrano nei log. */
class BluetoothConnectivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> ConnState.CONNECTED
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> ConnState.DISCONNECTED
            else -> return
        }
        val device = intent.bluetoothDevice() ?: return
        val identity = try {
            device.address
        } catch (_: SecurityException) {
            null
        }?.takeIf { it.isNotBlank() } ?: return
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        Log.d(TAG, "bluetooth ACL: state=$state named=${name != null}")

        val entry = entryPoint(context)
        val pending = goAsync()
        entry.applicationScope().launch {
            try {
                entry.connectivityEventIngress().observe(
                    medium = ConnMedium.BT,
                    connectionState = state,
                    name = name,
                    sourceIdentity = identity,
                    atMillis = System.currentTimeMillis(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "dispatch bluetooth fallito: ${error::class.java.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val TAG = "ArgusConnectivity"
    }
}
