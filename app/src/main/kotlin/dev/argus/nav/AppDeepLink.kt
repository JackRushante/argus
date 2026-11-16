package dev.argus.nav

import android.content.Intent
import dev.argus.automation.health.HealthDeepLinks

/**
 * Schermata da aprire quando l'app parte dal tocco di un avviso di salute (0.7.2). Solo navigazione:
 * il Dettaglio e Sistema si aprono come dalla barra, nessuna azione parte da qui. `MainActivity` è
 * esportata (launcher), quindi l'extra viene da chiunque: un id non valido apre un Dettaglio vuoto.
 */
sealed interface AppDeepLink {
    data class Rule(val id: String) : AppDeepLink
    data object System : AppDeepLink

    companion object {
        private const val MAX_RULE_ID_LENGTH = 128

        fun from(intent: Intent?): AppDeepLink? = intent?.let {
            parse(
                ruleId = it.getStringExtra(HealthDeepLinks.EXTRA_OPEN_RULE),
                openSystem = it.getBooleanExtra(HealthDeepLinks.EXTRA_OPEN_SYSTEM, false),
            )
        }

        fun parse(ruleId: String?, openSystem: Boolean): AppDeepLink? = when {
            ruleId != null && ruleId.isNotBlank() && ruleId.length <= MAX_RULE_ID_LENGTH -> Rule(ruleId)
            openSystem -> System
            else -> null
        }
    }
}
