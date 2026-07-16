package dev.argus.automation.vm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.BgLocationState
import dev.argus.ui.model.ShizukuStatus

internal data class AndroidUiHealth(
    val batteryExempt: Boolean,
    val notificationListenerGranted: Boolean,
    val notificationsGranted: Boolean,
    val foregroundLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val receiveSmsGranted: Boolean = false,
    val readPhoneStateGranted: Boolean = false,
    val readCallLogGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = false,
)

internal fun readAndroidUiHealth(context: Context): AndroidUiHealth {
    val app = context.applicationContext
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    return AndroidUiHealth(
        batteryExempt = runCatching {
            app.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(app.packageName)
        }.getOrDefault(false),
        notificationListenerGranted = runCatching {
            app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)
        }.getOrDefault(false),
        notificationsGranted = (
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS)
            ) && runCatching {
            app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }.getOrDefault(false),
        foregroundLocationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        backgroundLocationGranted = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        receiveSmsGranted = granted(Manifest.permission.RECEIVE_SMS),
        readPhoneStateGranted = granted(Manifest.permission.READ_PHONE_STATE),
        readCallLogGranted = granted(Manifest.permission.READ_CALL_LOG),
        bluetoothConnectGranted = android.os.Build.VERSION.SDK_INT <
            android.os.Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT),
    )
}

internal fun ShizukuGatewayStatus.toUiStatus(degradedAfterReboot: Boolean): ShizukuStatus = when (this) {
    ShizukuGatewayStatus.NOT_INSTALLED -> ShizukuStatus.NOT_INSTALLED
    ShizukuGatewayStatus.INSTALLED_NOT_RUNNING -> if (degradedAfterReboot) {
        ShizukuStatus.DEGRADED_AFTER_REBOOT
    } else {
        ShizukuStatus.INSTALLED_NOT_RUNNING
    }
    ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED -> ShizukuStatus.RUNNING_NOT_AUTHORIZED
    ShizukuGatewayStatus.AUTHORIZED -> ShizukuStatus.AUTHORIZED
    ShizukuGatewayStatus.UNSUPPORTED -> ShizukuStatus.INSTALLED_NOT_RUNNING
}

internal fun AndroidUiHealth.backgroundLocationState(needed: Boolean): BgLocationState = when {
    // Un grant completo concesso in anticipo deve leggersi GRANTED anche senza regole geofence:
    // altrimenti «sempre/precisa» appariva grigio/NOT_NEEDED e sembrava non essere stato applicato.
    backgroundLocationGranted && foregroundLocationGranted -> BgLocationState.GRANTED
    !needed -> BgLocationState.NOT_NEEDED
    foregroundLocationGranted -> BgLocationState.WHILE_IN_USE
    else -> BgLocationState.DENIED
}
