package dev.argus.automation.base

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import dev.argus.device.RingerMode
import dev.argus.engine.model.DndMode

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

    /** Nessuna app orologio gestisce l'Intent → false, così l'executor emette `alarm_app_unresolved`
     *  invece di crashare con ActivityNotFoundException. */
    private fun startIfResolvable(intent: Intent): Boolean {
        if (intent.resolveActivity(appContext.packageManager) == null) return false
        appContext.startActivity(intent)
        return true
    }
}
