package dev.argus.automation.health

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.argus.automation.R

/**
 * Dove porta il tocco di un avviso di salute (0.7.2). L'intent esplicito va al trampolino NON esportato
 * del modulo app ([OPEN_ACTIVITY_CLASS]), che passa la destinazione alla navigazione dentro il processo.
 * `MainActivity` è esportata e non legge mai questi extra: un'altra app non può farle aprire nulla.
 */
object HealthDeepLinks {
    const val ACTION_OPEN = "dev.argus.action.OPEN_FROM_HEALTH_ALERT"
    /** Classe del trampolino (modulo app): il manifest la dichiara non esportata. */
    const val OPEN_ACTIVITY_CLASS = "dev.argus.nav.HealthAlertOpenActivity"

    /** Id della regola da aprire nel Dettaglio. */
    const val EXTRA_OPEN_RULE = "dev.argus.extra.OPEN_RULE"
    /** Apre la schermata Sistema, dove si sistemano permessi e privilegi. */
    const val EXTRA_OPEN_SYSTEM = "dev.argus.extra.OPEN_SYSTEM"
}

/**
 * Canale proprio («argus_health»), separato da budget e automazioni, così si può silenziare da solo.
 * Testi solo da risorse, con il nome della regola o un conteggio: mai il contenuto di un messaggio.
 * Best-effort: senza permesso notifiche non succede nulla.
 */
class AndroidHealthNotifier(context: Context) : HealthNotifier {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.argus_health_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.argus_health_channel_description)
            },
        )
    }

    override fun ruleFailing(ruleId: String, ruleName: String) {
        val title = appContext.getString(R.string.argus_health_rule_failed_title, ruleName, RuleHealthPolicy.FAILURES_IN_A_ROW)
        val text = appContext.getString(R.string.argus_health_rule_failed_text)
        post(
            ruleNotificationId(ruleId),
            builder(title, text).setContentIntent(open(ruleNotificationId(ruleId)) { putExtra(HealthDeepLinks.EXTRA_OPEN_RULE, ruleId) }),
        )
    }

    override fun clearRuleFailing(ruleId: String) {
        manager.cancel(ruleNotificationId(ruleId))
    }

    override fun capabilityLost(capability: HealthCapability, affectedRules: Int) {
        val id = capabilityNotificationId(capability)
        val title = appContext.getString(capability.titleRes())
        val text = appContext.resources.getQuantityString(R.plurals.argus_health_capability_rules, affectedRules, affectedRules)
        val openSystem = open(id) { putExtra(HealthDeepLinks.EXTRA_OPEN_SYSTEM, true) }
        post(
            id,
            builder(title, text)
                .setContentIntent(openSystem)
                .addAction(0, appContext.getString(R.string.argus_health_fix), openSystem),
        )
    }

    override fun clearCapabilityLost(capability: HealthCapability) {
        manager.cancel(capabilityNotificationId(capability))
    }

    private fun builder(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    /** Intent esplicito verso il trampolino non esportato: stesso pacchetto, mai implicito. */
    private fun open(requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
        val launch = openIntent(appContext.packageName)
        launch.extras()
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        try {
            manager.notify(id, builder.build())
        } catch (_: SecurityException) {
            // Permesso notifiche mancante: l'avviso è best-effort.
        }
    }

    private fun HealthCapability.titleRes(): Int = when (this) {
        HealthCapability.PRIVILEGED_BACKEND -> R.string.argus_health_lost_privileged
        HealthCapability.NOTIFICATION_LISTENER -> R.string.argus_health_lost_notification_listener
        HealthCapability.ACCESSIBILITY -> R.string.argus_health_lost_accessibility
        HealthCapability.EXACT_ALARMS -> R.string.argus_health_lost_exact_alarms
        HealthCapability.LOCATION -> R.string.argus_health_lost_location
    }

    internal companion object {
        const val CHANNEL_ID = "argus_health"
        private const val CAPABILITY_ID_BASE = 0x4EA1_7000

        fun openIntent(packageName: String): Intent = Intent(HealthDeepLinks.ACTION_OPEN)
            .setClassName(packageName, HealthDeepLinks.OPEN_ACTIVITY_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Un id per regola, stabile e lontano da quelli delle capacità. */
        fun ruleNotificationId(ruleId: String): Int = ("health-rule:$ruleId").hashCode() and 0x3FFF_FFFF

        fun capabilityNotificationId(capability: HealthCapability): Int = CAPABILITY_ID_BASE + capability.ordinal
    }
}
