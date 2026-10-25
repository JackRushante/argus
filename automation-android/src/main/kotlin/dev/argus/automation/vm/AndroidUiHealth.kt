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
import dev.argus.ui.presentation.RenderLanguage

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
    // L'enum UI non ha un valore «troppo vecchio»: è la riga di stato più vicina. Il testo giusto
    // («Shizuku troppo vecchio: aggiornalo») viene da [statusNote].
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

/**
 * Nota di stato di Shizuku quando l'enum UI [ShizukuStatus] non basta. Oggi solo UNSUPPORTED:
 * `Shizuku.isPreV11()` è vero per una versione troppo vecchia per il binder user service, e
 * mostrarla come «non in esecuzione» manda l'utente a riavviare un servizio che gira già. Chi
 * costruisce la schermata sostituisce con questo testo la riga di stato (è `null` per gli altri
 * stati). Il testo è prodotto con la lingua corrente, come ogni messaggio dei ViewModel.
 */
internal fun ShizukuGatewayStatus.statusNote(language: RenderLanguage): String? = when (this) {
    ShizukuGatewayStatus.UNSUPPORTED -> language.pick(
        "Shizuku is too old: update it",
        "Shizuku troppo vecchio: aggiornalo",
    )
    ShizukuGatewayStatus.NOT_INSTALLED,
    ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
    ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
    ShizukuGatewayStatus.AUTHORIZED,
    -> null
}
