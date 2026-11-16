package dev.argus.engine.runtime

import dev.argus.engine.model.AutomationId

/**
 * Interruttore globale «Pausa automazioni» (0.4.1, F21), letto dal motore a ogni scatto.
 *
 * `Engine.onTrigger` è il punto da cui passano tutti gli scatti (orario, geofence, notifiche,
 * telefono, connettività, sensori, eventi di sistema, avvio manuale): controllarlo lì garantisce che
 * in pausa NESSUNA regola scatti, da nessun trigger. Lo scatto saltato non consuma nulla: niente
 * claim, niente cooldown, niente deduplica, e alla ripresa NON viene recuperato.
 *
 * 0.7.2: la stessa pausa si decide anche regola per regola ([pauseCodeFor]), per la pausa di un gruppo di
 * regole con la stessa etichetta. Il trattamento dello scatto saltato è identico; cambia solo il codice
 * del Log ([PauseCodes.GROUP_PAUSED]).
 *
 * La lettura globale è sincrona e leggera (una preferenza in memoria): sta nel percorso di ogni evento.
 */
fun interface AutomationPause {
    fun isPaused(): Boolean

    /**
     * Decisione per UNA regola, dopo il match: `null` = può scattare, altrimenti il codice del Log
     * ([PauseCodes.PAUSED] per la pausa globale, che vince sempre, o [PauseCodes.GROUP_PAUSED]). Il
     * default è il solo interruttore globale.
     */
    suspend fun pauseCodeFor(automationId: AutomationId): String? =
        if (isPaused()) PauseCodes.PAUSED else null

    companion object {
        /** Mai in pausa: il valore dei chiamanti che non cablano l'interruttore (test, host JVM). */
        val NEVER: AutomationPause = AutomationPause { false }
    }
}

/** Codici del Log per la pausa: costanti chiuse, mai testo dell'utente. */
object PauseCodes {
    /** Motivo di un `BLOCKED_POLICY`: la regola avrebbe scattato ma le automazioni sono in pausa. */
    const val PAUSED = "paused"

    /**
     * 0.7.2: motivo di un `BLOCKED_POLICY`: la regola avrebbe scattato ma un suo gruppo (etichetta) è in
     * pausa. Il nome dell'etichetta non entra nel Log: è testo dell'utente.
     */
    const val GROUP_PAUSED = "paused_group"
}
