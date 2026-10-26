package dev.argus.engine.runtime

/**
 * Interruttore globale «Pausa automazioni» (0.4.1, F21), letto dal motore a ogni scatto.
 *
 * `Engine.onTrigger` è il punto da cui passano tutti gli scatti (orario, geofence, notifiche,
 * telefono, connettività, sensori, eventi di sistema, avvio manuale): controllarlo lì garantisce che
 * in pausa NESSUNA regola scatti, da nessun trigger. Lo scatto saltato non consuma nulla: niente
 * claim, niente cooldown, niente deduplica, e alla ripresa NON viene recuperato.
 *
 * La lettura è sincrona e leggera (una preferenza in memoria): sta nel percorso di ogni evento.
 */
fun interface AutomationPause {
    fun isPaused(): Boolean

    companion object {
        /** Mai in pausa: il valore dei chiamanti che non cablano l'interruttore (test, host JVM). */
        val NEVER: AutomationPause = AutomationPause { false }
    }
}

/** Codici del Log per la pausa: costanti chiuse, mai testo dell'utente. */
object PauseCodes {
    /** Motivo di un `BLOCKED_POLICY`: la regola avrebbe scattato ma le automazioni sono in pausa. */
    const val PAUSED = "paused"
}
