package dev.argus.automation.base

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
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
}
