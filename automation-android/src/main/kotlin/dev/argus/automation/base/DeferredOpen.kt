package dev.argus.automation.base

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.argus.automation.R
import dev.argus.engine.model.SettingsScreen

/**
 * Esito registrato nel journal quando Android non lascia aprire un'app, un URL o una schermata dal
 * background e Argus ha pubblicato al suo posto la notifica «Tocca per aprire …». È un codice
 * diverso da `activity_start_blocked` (che resta per «non ho potuto fare nemmeno questo»): l'utente
 * ha in mano un tocco che apre davvero la destinazione.
 */
const val DEFERRED_TO_NOTIFICATION = "deferred_to_notification"

/** Cosa l'utente potrà aprire con un tocco sulla notifica. */
sealed interface DeferredOpenTarget {
    data class App(val pkg: String) : DeferredOpenTarget
    data class Url(val url: String) : DeferredOpenTarget
    data class SettingsPage(val screen: SettingsScreen, val pkg: String?) : DeferredOpenTarget
}

/**
 * Pubblica la notifica «Tocca per aprire …». Il `PendingIntent` parte da un gesto dell'utente, quindi
 * Android lo lascia aprire anche se l'app è in background. Ritorna `false` se non si può pubblicare
 * (permesso di notifica mancante): l'esecutore allora fallisce come prima, con `activity_start_blocked`.
 */
fun interface DeferredOpenNotifier {
    fun publish(target: DeferredOpenTarget): Boolean
}

/**
 * Adapter Android: una notifica sul canale delle regole, con id stabile per destinazione (se la regola
 * scatta di nuovo la notifica si aggiorna, non si accumula), tap = apre la destinazione. Il contenuto
 * è la destinazione, mai testo di messaggi o di contatti.
 */
class AndroidDeferredOpenNotifier(context: Context) : DeferredOpenNotifier {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    override fun publish(target: DeferredOpenTarget): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val id = deferredOpenNotificationId(target)
        val pending = PendingIntent.getActivity(
            appContext,
            id,
            deferredOpenIntent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = appContext.getString(R.string.act41_open_tap_text)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setContentTitle(appContext.getString(R.string.act41_open_tap_title, destinationLabel(target)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
            .build()
        return try {
            manager.notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun destinationLabel(target: DeferredOpenTarget): String = when (target) {
        is DeferredOpenTarget.App -> appLabel(target.pkg)
        is DeferredOpenTarget.Url -> shortenedForLabel(target.url)
        is DeferredOpenTarget.SettingsPage -> when (target.screen) {
            SettingsScreen.WIFI -> appContext.getString(R.string.act41_open_dest_wifi)
            SettingsScreen.BLUETOOTH -> appContext.getString(R.string.act41_open_dest_bluetooth)
            SettingsScreen.DISPLAY -> appContext.getString(R.string.act41_open_dest_display)
            SettingsScreen.SOUND -> appContext.getString(R.string.act41_open_dest_sound)
            SettingsScreen.LOCATION -> appContext.getString(R.string.act41_open_dest_location)
            SettingsScreen.BATTERY -> appContext.getString(R.string.act41_open_dest_battery)
            SettingsScreen.DATE -> appContext.getString(R.string.act41_open_dest_date)
            SettingsScreen.APP_DETAILS ->
                appContext.getString(R.string.act41_open_dest_app_details, appLabel(target.pkg.orEmpty()))
            SettingsScreen.SETTINGS -> appContext.getString(R.string.act41_open_dest_settings)
        }
    }

    /** Etichetta dell'app se il sistema la rivela, altrimenti il nome del pacchetto. */
    private fun appLabel(pkg: String): String = runCatching {
        val packages = appContext.packageManager
        packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg

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
        /** Stesso canale delle notifiche delle regole (AndroidAutomationNotifier). */
        const val CHANNEL_ID = "argus_automations"
    }
}

/** Lo stesso Intent che l'esecutore avrebbe lanciato da solo in primo piano. */
internal fun deferredOpenIntent(target: DeferredOpenTarget): Intent = when (target) {
    is DeferredOpenTarget.App -> Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(target.pkg)
    is DeferredOpenTarget.Url -> Intent(Intent.ACTION_VIEW, Uri.parse(target.url))
    is DeferredOpenTarget.SettingsPage -> settingsIntent(target.screen, target.pkg)
}

/** Id stabile per destinazione: la stessa destinazione aggiorna la sua notifica, un'altra ne aggiunge una. */
internal fun deferredOpenNotificationId(target: DeferredOpenTarget): Int = "deferred-open:$target".hashCode()

private const val MAX_URL_LABEL_CHARS = 80

/** Un URL lungo si accorcia nel titolo (l'Intent porta sempre quello intero). */
internal fun shortenedForLabel(url: String): String =
    if (url.length <= MAX_URL_LABEL_CHARS) url else url.take(MAX_URL_LABEL_CHARS) + "…"
