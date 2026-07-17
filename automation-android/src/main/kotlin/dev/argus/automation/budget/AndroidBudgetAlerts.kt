package dev.argus.automation.budget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.argus.automation.R

/**
 * Canale notifiche proprio per il budget ("argus_budget"), separato dalle automazioni.
 * Testo SOLO da costanti (mai payload). Alert best-effort: permessi mancanti inghiottiti.
 */
class AndroidBudgetAlerts(context: Context) : BudgetAlerts {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.argus_budget_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.argus_budget_channel_description)
            },
        )
    }

    override suspend fun notify(title: String, text: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // permesso notifiche mancante: l'alert è best-effort.
        }
    }

    private companion object {
        const val CHANNEL_ID = "argus_budget"
        const val NOTIFICATION_ID = 0x8_00D_6E7
    }
}
