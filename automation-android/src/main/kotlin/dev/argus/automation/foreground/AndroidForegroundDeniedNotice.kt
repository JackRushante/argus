package dev.argus.automation.foreground

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.argus.automation.R
import dev.argus.automation.connectivity.ForegroundDeniedNotice

/**
 * «Regole in background (Wi-Fi, alimentazione, schermo, batteria, audio, modalità) in pausa:
 * apri Argus per riattivarle». Una sola notifica con id
 * fisso (ripubblicarla la aggiorna), tap = apre l'app, rimossa da [clear] quando la sentinella
 * riparte. Usa il canale delle notifiche delle regole, creato qui se manca (idempotente).
 */
class AndroidForegroundDeniedNotice(context: Context) : ForegroundDeniedNotice {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    override fun show() {
        if (!canPost()) return
        ensureChannel()
        val contentIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    appContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setContentTitle(appContext.getString(R.string.argus_foreground_denied_title))
            .setContentText(appContext.getString(R.string.argus_foreground_denied_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(appContext.getString(R.string.argus_foreground_denied_text)),
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "avviso servizio negato non pubblicato: ${error::class.java.simpleName}")
        }
    }

    override fun clear() {
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (error: RuntimeException) {
            Log.w(TAG, "avviso servizio negato non rimosso: ${error::class.java.simpleName}")
        }
    }

    private fun ensureChannel() {
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.argus_automation_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.argus_automation_channel_description)
            },
        )
    }

    private fun canPost(): Boolean = (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) && manager.areNotificationsEnabled()

    private companion object {
        const val TAG = "ArgusForeground"

        /** Stesso canale delle notifiche delle regole (AndroidAutomationNotifier). */
        const val CHANNEL_ID = "argus_automations"
        const val NOTIFICATION_ID = 0x41524745
    }
}
