package dev.argus.nav

/** Cosa fare con un permesso runtime a questo punto. */
internal enum class PermissionStep {
    /** Mostrare la richiesta di sistema. */
    REQUEST,

    /** Android non mostrerebbe più nulla: portare l'utente nelle impostazioni dell'app. */
    OPEN_APP_SETTINGS,

    /** Niente da fare (già concesso, oppure rifiuto non ancora definitivo). */
    NONE,
}

/** A che punto del giro siamo quando si decide. */
internal enum class PermissionOutcome {
    /** L'utente ha appena toccato «Concedi»: la richiesta non è ancora partita. */
    NOT_REQUESTED_YET,
    GRANTED,
    DENIED,
}

/**
 * Decisione pura (B33). Dopo due rifiuti Android non mostra più la richiesta di sistema e
 * lanciarla non fa niente: il permesso è «negato per sempre» e l'unica strada sono le impostazioni.
 *
 * Il segnale è `shouldShowRequestPermissionRationale == false`, ma da solo non basta: prima di
 * qualunque richiesta vale false anche se il permesso non è mai stato chiesto. Per questo conta
 * solo insieme a [alreadyAsked] (registro «già chiesto»): così il primo rifiuto, che lascia la
 * rationale vera, e la prima richiesta non vengono mai trattati come permanenti.
 */
internal fun nextPermissionStep(
    alreadyAsked: Boolean,
    shouldShowRationale: Boolean,
    outcome: PermissionOutcome,
): PermissionStep {
    val deniedForGood = alreadyAsked && !shouldShowRationale
    return when (outcome) {
        PermissionOutcome.GRANTED -> PermissionStep.NONE
        PermissionOutcome.NOT_REQUESTED_YET ->
            if (deniedForGood) PermissionStep.OPEN_APP_SETTINGS else PermissionStep.REQUEST
        PermissionOutcome.DENIED ->
            if (deniedForGood) PermissionStep.OPEN_APP_SETTINGS else PermissionStep.NONE
    }
}

/** Registro «già chiesto» per permesso: sopravvive al riavvio del processo, non contiene dati utente. */
internal interface PermissionAskStore {
    fun wasAsked(permission: String): Boolean
    fun markAsked(permission: String)
    fun clearAsked(permission: String)
}

/**
 * Orchestra una richiesta di permessi runtime senza dipendere da Android: il lancio della richiesta,
 * l'apertura delle impostazioni e il messaggio arrivano come funzioni. In questo modo la logica di
 * «Concedi» (richiesta di sistema oppure impostazioni) si prova su JVM.
 */
internal class PermissionRequestCoordinator(
    private val store: PermissionAskStore,
    private val isGranted: (String) -> Boolean,
    private val shouldShowRationale: (String) -> Boolean,
    private val launch: (Array<String>) -> Unit,
    private val openAppSettings: () -> Unit,
    /** Messaggio «Abilita il permesso dalle impostazioni dell'app». */
    private val notifySettingsNeeded: () -> Unit,
) {
    /**
     * Tocco su «Concedi». Restituisce false se non c'era niente da concedere (tutto già concesso):
     * il chiamante aggiorna lo stato come prima.
     */
    fun request(permissions: Array<String>): Boolean {
        val missing = permissions.filterNot(isGranted)
        if (missing.isEmpty()) return false
        val needsSettings = missing.any {
            nextPermissionStep(
                store.wasAsked(it),
                shouldShowRationale(it),
                PermissionOutcome.NOT_REQUESTED_YET,
            ) == PermissionStep.OPEN_APP_SETTINGS
        }
        if (needsSettings) {
            goToSettings()
        } else {
            // Si registra PRIMA del lancio: quando il risultato torna, il permesso risulta già chiesto.
            missing.forEach(store::markAsked)
            launch(missing.toTypedArray())
        }
        return true
    }

    /** Esito della richiesta di sistema: permesso -> concesso o meno. */
    fun onResult(result: Map<String, Boolean>) {
        result.filterValues { it }.keys.forEach(store::clearAsked)
        val deniedForGood = result.filterValues { granted -> !granted }.keys.any {
            nextPermissionStep(
                store.wasAsked(it),
                shouldShowRationale(it),
                PermissionOutcome.DENIED,
            ) == PermissionStep.OPEN_APP_SETTINGS
        }
        if (deniedForGood) goToSettings()
    }

    private fun goToSettings() {
        notifySettingsNeeded()
        openAppSettings()
    }
}
