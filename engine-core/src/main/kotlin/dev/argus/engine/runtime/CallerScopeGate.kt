package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation

/** 0.8: dove sta chi chiama rispetto a una regola `screen_call` con `onlyUnknownCallers`. */
enum class CallerScope {
    /** Non è nei contatti, o ha il numero nascosto: la regola vale per questa chiamata. */
    IN_SCOPE,

    /** È nei contatti: la regola non vale per questa chiamata. */
    KNOWN_CALLER,

    /** Non si è potuto sapere (permesso mancante, errore, tempo scaduto): nel dubbio la regola non esegue. */
    UNVERIFIED,
}

/**
 * 0.8: `onlyUnknownCallers` di `screen_call` è una condizione della regola intera. Prima che il programma parta
 * (dopo trigger, pausa, policy e condizioni, prima del claim) il motore chiede qui se chi chiama è nel perimetro
 * della regola: null = esegue; altrimenti il codice chiuso per cui NESSUNA azione della regola gira su questa
 * chiamata, nemmeno quelle che vengono prima del filtro. Va nel Log come una condizione non soddisfatta (non è
 * un errore, non conta per gli avvisi di salute) e non contiene mai il numero. La chiamata intanto squilla o no
 * per conto suo: il filtro chiamate continua a fallire aperto.
 */
fun interface CallerScopeGate {
    suspend fun closedCode(automation: Automation, event: TriggerEvent): String?

    companion object {
        const val KNOWN_CALLER = "screen_call_known_caller"
        const val CALLER_UNVERIFIED = "screen_call_caller_unverified"

        /** Senza un filtro chiamate collegato l'appartenenza non si stabilisce mai: le azioni non girano. */
        val UNAVAILABLE = CallerScopeGate { _, _ -> CALLER_UNVERIFIED }

        /** La regola ha un `screen_call` con `onlyUnknownCallers` (il validator lo ammette solo fra le principali). */
        fun appliesTo(automation: Automation): Boolean =
            automation.actions.any { it is Action.ScreenCall && it.onlyUnknownCallers }

        fun codeFor(scope: CallerScope?): String? = when (scope) {
            CallerScope.IN_SCOPE -> null
            CallerScope.KNOWN_CALLER -> KNOWN_CALLER
            CallerScope.UNVERIFIED, null -> CALLER_UNVERIFIED
        }
    }
}
