package dev.argus.nav

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.argus.MainActivity
import dev.argus.automation.health.HealthDeepLinks

/**
 * Trampolino invisibile del tocco di un avviso di salute (0.7.2). NON è esportato: lo avvia solo il
 * `PendingIntent` esplicito della notifica creata da Argus. Passa la destinazione alla navigazione dentro
 * il processo e porta in primo piano `MainActivity` senza extra, così l'Activity esportata non riceve
 * mai dall'esterno l'ordine di aprire una schermata.
 */
class HealthAlertOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent
        val link = request?.let {
            AppDeepLink.parse(
                action = it.action,
                ruleId = it.getStringExtra(HealthDeepLinks.EXTRA_OPEN_RULE),
                openSystem = it.getBooleanExtra(HealthDeepLinks.EXTRA_OPEN_SYSTEM, false),
            )
        }
        if (link != null) {
            HealthAlertNavigation.post(link)
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
        finish()
    }
}
