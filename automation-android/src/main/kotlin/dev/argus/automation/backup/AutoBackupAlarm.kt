package dev.argus.automation.backup

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.R

/** La sveglia del prossimo backup automatico. */
interface AutoBackupAlarmScheduler {
    fun schedule(atMillis: Long)
    fun cancel()
}

/** La notifica «backup non riuscito», UNA per serie di fallimenti. */
interface AutoBackupFailureNotice {
    fun show()
    fun clear()
}

/**
 * Sveglia inexact `RTC_WAKEUP` (Android la può spostare di un po'; il backup di notte non chiede
 * precisione), quindi nessun permesso di sveglie esatte. Un solo PendingIntent: riprogrammare sostituisce.
 * Il riavvio cancella le sveglie: le rimette [AutoBackupCoordinator.start] all'avvio del processo, che
 * il receiver di BOOT_COMPLETED già esistente provoca.
 */
class AndroidAutoBackupAlarm(context: Context) : AutoBackupAlarmScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = requireNotNull(appContext.getSystemService(AlarmManager::class.java)) {
        "AlarmManager non disponibile"
    }

    override fun schedule(atMillis: Long) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
    }

    override fun cancel() {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent(appContext),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    companion object {
        internal const val ACTION_AUTO_BACKUP = "dev.argus.automation.action.AUTO_BACKUP"
        private const val REQUEST_CODE = 0

        internal fun intent(context: Context): Intent =
            Intent(context, AutoBackupAlarmReceiver::class.java).setAction(ACTION_AUTO_BACKUP)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AutoBackupEntryPoint {
    fun autoBackupCoordinator(): AutoBackupCoordinator
}

/** Riceve la sveglia (intent esplicito, receiver non esportato) e fa partire il backup se è dovuto. */
class AutoBackupAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAutoBackupAlarm.ACTION_AUTO_BACKUP) return
        val pending = goAsync()
        try {
            EntryPointAccessors.fromApplication(context.applicationContext, AutoBackupEntryPoint::class.java)
                .autoBackupCoordinator()
                .onAlarm(onFinished = pending::finish)
        } catch (error: RuntimeException) {
            pending.finish()
            Log.w(TAG, "backup automatico non avviato: ${error::class.java.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ArgusAutoBackup"
    }
}

/** Notifica sul canale delle regole: tap = apre Argus. Testo fisso, senza nomi di file o cartelle. */
class AndroidAutoBackupFailureNotice(context: Context) : AutoBackupFailureNotice {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    override fun show() {
        if (!canPost()) return
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.argus_automation_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = appContext.getString(R.string.argus_automation_channel_description) },
        )
        val contentIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.let { launch ->
                PendingIntent.getActivity(
                    appContext,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val text = appContext.getString(R.string.auto_backup_failed_text)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setContentTitle(appContext.getString(R.string.auto_backup_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "avviso backup non pubblicato: ${error::class.java.simpleName}")
        }
    }

    override fun clear() {
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (error: RuntimeException) {
            Log.w(TAG, "avviso backup non rimosso: ${error::class.java.simpleName}")
        }
    }

    private fun canPost(): Boolean = (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) && manager.areNotificationsEnabled()

    private companion object {
        const val TAG = "ArgusAutoBackup"

        /** Stesso canale delle notifiche delle regole (AndroidAutomationNotifier). */
        const val CHANNEL_ID = "argus_automations"
        const val NOTIFICATION_ID = 0x41524b42
    }
}
