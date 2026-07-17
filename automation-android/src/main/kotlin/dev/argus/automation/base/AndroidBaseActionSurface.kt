package dev.argus.automation.base

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import dev.argus.device.RingerMode
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.VolumeStream

/**
 * Adapter Android reale della [BaseActionSurface]: esegue le azioni BASE con API di sistema, senza
 * Shizuku (decision record §7.3). Volutamente sottile — nessuna logica di mapping/validazione, che
 * vive in [AndroidBaseActionExecutor] ed è host-testata. Verificato sul device.
 */
class AndroidBaseActionSurface(context: Context) : BaseActionSurface {
    private val appContext = context.applicationContext
    private val notificationManager: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)
    private val audioManager: AudioManager
        get() = appContext.getSystemService(AudioManager::class.java)

    override fun isDndPolicyGranted(): Boolean =
        notificationManager.isNotificationPolicyAccessGranted

    override fun setInterruptionFilter(mode: DndMode) {
        notificationManager.setInterruptionFilter(
            when (mode) {
                DndMode.OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
                DndMode.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                DndMode.TOTAL -> NotificationManager.INTERRUPTION_FILTER_NONE
            },
        )
    }

    override fun setRingerMode(mode: RingerMode) {
        audioManager.ringerMode = when (mode) {
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
    }

    override fun launchPackage(pkg: String): Boolean {
        val intent = appContext.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    override fun openHttpUrl(url: String) {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun setAlarm(hour: Int, minute: Int, label: String?, skipUi: Boolean): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startIfResolvable(intent)
    }

    override fun setTimer(seconds: Int, label: String?, skipUi: Boolean): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startIfResolvable(intent)
    }

    override fun maxStreamVolume(stream: VolumeStream): Int =
        audioManager.getStreamMaxVolume(streamType(stream))

    override fun setStreamVolume(stream: VolumeStream, level: Int) {
        audioManager.setStreamVolume(streamType(stream), level, 0)
    }

    override fun setTorchMode(on: Boolean): Boolean {
        val cameraManager = appContext.getSystemService(CameraManager::class.java) ?: return false
        return try {
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(flashCameraId, on)
            true
        } catch (_: CameraAccessException) {
            false
        }
    }

    override fun openSettingsScreen(screen: SettingsScreen, pkg: String?): Boolean {
        val intent = settingsIntent(screen, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startIfResolvable(intent)
    }

    override fun vibrateOneShot(durationMs: Int): Boolean {
        val vibrator = vibrator() ?: return false
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE),
        )
        return true
    }

    private fun streamType(stream: VolumeStream): Int = when (stream) {
        VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
        VolumeStream.RING -> AudioManager.STREAM_RING
        VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
    }

    /** Enum CHIUSO → Intent Settings.ACTION_* fisso: nessuna action-string arbitraria (no routing-sink). */
    private fun settingsIntent(screen: SettingsScreen, pkg: String?): Intent = when (screen) {
        SettingsScreen.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsScreen.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SettingsScreen.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        SettingsScreen.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
        SettingsScreen.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        SettingsScreen.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        SettingsScreen.DATE -> Intent(Settings.ACTION_DATE_SETTINGS)
        SettingsScreen.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", pkg.orEmpty(), null))
        SettingsScreen.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }

    /** Nessuna app orologio gestisce l'Intent → false, così l'executor emette `alarm_app_unresolved`
     *  invece di crashare con ActivityNotFoundException. */
    private fun startIfResolvable(intent: Intent): Boolean {
        if (intent.resolveActivity(appContext.packageManager) == null) return false
        appContext.startActivity(intent)
        return true
    }
}
