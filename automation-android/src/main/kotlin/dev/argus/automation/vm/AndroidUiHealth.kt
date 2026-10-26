package dev.argus.automation.vm

import android.Manifest
import android.app.AlarmManager
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
    /**
     * `SCHEDULE_EXACT_ALARM` concesso (sempre true sotto Android 12). Da Android 14 e' negato di
     * default: senza, le sveglie ripiegano su quelle inesatte e le regole a orario possono ritardare.
     */
    val exactAlarmsGranted: Boolean,
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
        exactAlarmsGranted = exactAlarmsGranted(android.os.Build.VERSION.SDK_INT) {
            app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        },
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

/**
 * Sotto Android 12 (S) il permesso non esiste e le sveglie esatte ci sono sempre. Da S in poi decide
 * `AlarmManager.canScheduleExactAlarms()`; se la lettura fallisce vale «non concesse», cosi' la riga in
 * Sistema avvisa invece di mostrare un falso verde.
 */
internal fun exactAlarmsGranted(sdkInt: Int, canScheduleExact: () -> Boolean): Boolean =
    sdkInt < android.os.Build.VERSION_CODES.S || runCatching(canScheduleExact).getOrDefault(false)

internal fun ShizukuGatewayStatus.toUiStatus(degradedAfterReboot: Boolean): ShizukuStatus = when (this) {
    ShizukuGatewayStatus.NOT_INSTALLED -> ShizukuStatus.NOT_INSTALLED
    ShizukuGatewayStatus.INSTALLED_NOT_RUNNING -> if (degradedAfterReboot) {
        ShizukuStatus.DEGRADED_AFTER_REBOOT
    } else {
        ShizukuStatus.INSTALLED_NOT_RUNNING
    }
    ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED -> ShizukuStatus.RUNNING_NOT_AUTHORIZED
    ShizukuGatewayStatus.AUTHORIZED -> ShizukuStatus.AUTHORIZED
    // `Shizuku.isPreV11()`: in esecuzione ma troppo vecchio per il binder user service. Va
    // aggiornato, non riavviato: ha il suo stato, con il suo testo e la sua azione.
    ShizukuGatewayStatus.UNSUPPORTED -> ShizukuStatus.UNSUPPORTED
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
 * Messaggio per [dev.argus.shizuku.ShizukuPermissionOutcome.AWAITING_USER]: la richiesta di
 * permesso e' scaduta senza risposta, ma la finestra di Shizuku puo' essere ancora aperta. Non e'
 * «Shizuku non disponibile»: se l'utente risponde dopo, lo stato si aggiorna da solo.
 */
internal fun shizukuAwaitingAnswerNote(language: RenderLanguage): String = language.pick(
    "Waiting for your answer in Shizuku",
    "In attesa della tua risposta in Shizuku",
)
