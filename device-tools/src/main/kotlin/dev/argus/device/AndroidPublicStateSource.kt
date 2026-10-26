package dev.argus.device

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * [PublicStateSource] reale: solo API pubbliche, nessun privilegio.
 * - Wi-Fi: `isWifiEnabled` richiede `ACCESS_WIFI_STATE` (permesso normal, dichiarato dal modulo).
 * - Bluetooth: solo su Android 12+ con `BLUETOOTH_CONNECT` concesso; prima servirebbe il permesso
 *   legacy `BLUETOOTH`, che l'app non dichiara, quindi la chiave resta a Shizuku.
 * - Gli altri valori non richiedono alcun permesso.
 * Un'eccezione di sistema non si gestisce qui: la intercetta [PublicStateReader] (valore assente).
 */
class AndroidPublicStateSource(context: Context) : PublicStateSource {
    private val appContext = context.applicationContext

    override fun battery(): BatteryReading? {
        // Intent sticky: con receiver nullo non si registra nulla e non serve alcun flag.
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        return BatteryReading(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
        )
    }

    override fun screenInteractive(): Boolean? =
        appContext.getSystemService(PowerManager::class.java)?.isInteractive

    override fun ringerMode(): Int? = appContext.getSystemService(AudioManager::class.java)?.ringerMode

    override fun interruptionFilter(): Int? =
        appContext.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter

    override fun airplaneMode(): Int? {
        val value = Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, MISSING)
        return value.takeIf { it != MISSING }
    }

    override fun wifiEnabled(): Boolean? =
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled

    override fun canReadBluetooth(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun bluetoothEnabled(): Boolean? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled

    private companion object {
        const val MISSING = -1
    }
}
