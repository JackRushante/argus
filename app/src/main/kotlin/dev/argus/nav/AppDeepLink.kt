package dev.argus.nav

import dev.argus.automation.health.HealthDeepLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Schermata da aprire per il tocco di un avviso di salute (0.7.2). Solo navigazione, nessuna azione. */
sealed interface AppDeepLink {
    data class Rule(val id: String) : AppDeepLink
    data object System : AppDeepLink

    companion object {
        private const val MAX_RULE_ID_LENGTH = 128

        /**
         * La destinazione di un avvio del trampolino [HealthAlertOpenActivity]: solo con la sua azione,
         * che mette soltanto il `PendingIntent` esplicito creato da Argus.
         */
        fun parse(action: String?, ruleId: String?, openSystem: Boolean): AppDeepLink? {
            if (action != HealthDeepLinks.ACTION_OPEN) return null
            return when {
                ruleId != null && ruleId.isNotBlank() && ruleId.length <= MAX_RULE_ID_LENGTH -> Rule(ruleId)
                openSystem -> System
                else -> null
            }
        }
    }
}

/**
 * Passaggio della destinazione dal trampolino al NavHost, dentro il processo. `MainActivity` è esportata
 * e non legge extra dal suo intent: l'unico che scrive qui è il trampolino non esportato.
 */
object HealthAlertNavigation {
    private val pending = MutableStateFlow<AppDeepLink?>(null)

    val requests: StateFlow<AppDeepLink?> = pending.asStateFlow()

    internal fun post(link: AppDeepLink) {
        pending.value = link
    }

    /** Il NavHost ha aperto [link]; una richiesta più nuova arrivata nel frattempo resta. */
    fun consume(link: AppDeepLink) {
        pending.compareAndSet(link, null)
    }
}
