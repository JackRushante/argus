package dev.argus.automation.screen

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.argus.automation.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Il segnale «Ferma» della notifica della macro. È un oggetto di processo perché lo imposta un
 * [BroadcastReceiver] che non ha accesso alle dipendenze: la sessione lo azzera a ogni inizio e lo
 * legge a ogni giro dell'esecutore.
 */
internal object UiMacroStopSignal {
    private val requested = AtomicBoolean(false)

    fun request() = requested.set(true)

    fun clear() = requested.set(false)

    val isRequested: Boolean get() = requested.get()
}

/**
 * Lo tocca solo la notifica «Ferma»: l'intent è esplicito, il receiver non è esportato e non ascolta
 * nessuna azione esterna. Non porta dati: ferma la macro in corso, tutto qui.
 */
class UiMacroStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_STOP) UiMacroStopSignal.request()
    }

    companion object {
        const val ACTION_STOP = "dev.argus.automation.screen.STOP_UI_MACRO"
    }
}

/**
 * La notifica in corso «Argus sta usando lo schermo» con «Ferma» (0.5.1). Senza il permesso di
 * pubblicare notifiche non si può mostrare: [begin] torna falso e la macro non parte, perché non
 * avrebbe né indicatore né tasto di stop.
 */
class AndroidUiMacroSession(context: Context) : UiMacroSession {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    override val stopRequested: Boolean get() = UiMacroStopSignal.isRequested

    override fun begin(): Boolean {
        UiMacroStopSignal.clear()
        if (!canPost()) return false
        ensureChannel()
        val stop = PendingIntent.getBroadcast(
            appContext,
            STOP_REQUEST_CODE,
            Intent(UiMacroStopReceiver.ACTION_STOP)
                .setClass(appContext, UiMacroStopReceiver::class.java)
                .setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = appContext.getString(R.string.a11y51_notification_text)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setContentTitle(appContext.getString(R.string.a11y51_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, appContext.getString(R.string.a11y51_notification_stop), stop)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override fun end() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.a11y51_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.a11y51_channel_description)
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
        const val CHANNEL_ID = "argus_screen_macro"
        const val NOTIFICATION_ID = 0x5C21
        const val STOP_REQUEST_CODE = 0x5C22
    }
}
