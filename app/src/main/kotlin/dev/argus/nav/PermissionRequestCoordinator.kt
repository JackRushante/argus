package dev.argus.nav

/**
 * Cosa sappiamo del rifiuto di un permesso runtime (B33), per permesso e persistito.
 *
 * Android non dice mai «negato per sempre»: l'unico indizio è `shouldShowRequestPermissionRationale`,
 * che prima di qualunque richiesta è false anche se il permesso non è mai stato chiesto, e che resta
 * false se l'utente chiude il dialogo toccando fuori (Android non lo conta come rifiuto). Da solo
 * quel segnale non basta, quindi si tiene questo stato.
 */
internal enum class PermissionState {
    /** Mai rifiutato in modo esplicito (mai chiesto, oppure chiuso senza scegliere). */
    NONE,

    /** Un «Non consentire» esplicito: Android mostrerà ancora il dialogo una volta. */
    DENIED_ONCE,

    /** Android non mostra più il dialogo: l'unica strada sono le impostazioni dell'app. */
    PERMANENT,
}

/** Stato di rifiuto per permesso: sopravvive al riavvio del processo, non contiene dati utente. */
internal interface PermissionStateStore {
    fun state(permission: String): PermissionState
    fun setState(permission: String, state: PermissionState)
}

/**
 * Esito del rifiuto di un singolo permesso (decisione pura).
 *
 * @property newState lo stato da salvare.
 * @property message mostrare «Abilita il permesso dalle impostazioni dell'app».
 * @property openSettings aprire subito le impostazioni dell'app.
 */
internal data class DenialOutcome(
    val newState: PermissionState,
    val message: Boolean = false,
    val openSettings: Boolean = false,
)

/** Un esito negato che torna entro questo tempo dal lancio non è passato da un dialogo mostrato. */
internal const val INSTANT_DENIAL_MILLIS = 800L

/**
 * Cosa fare quando la richiesta di un permesso torna **negata** (B33).
 *
 * - rationale vera: Android mostrerà ancora il dialogo -> [PermissionState.DENIED_ONCE];
 * - rationale falsa ed esito **immediato** (entro [INSTANT_DENIAL_MILLIS]): il sistema non ha
 *   mostrato niente, il permesso è già negato per sempre (anche da prima di questa versione) ->
 *   [PermissionState.PERMANENT], messaggio e impostazioni subito, perché l'utente ha toccato
 *   «Concedi» e non è successo nulla;
 * - rationale falsa, esito lento e rifiuto esplicito già registrato ([PermissionState.DENIED_ONCE]):
 *   è il secondo «Non consentire» -> [PermissionState.PERMANENT] e solo il messaggio, le impostazioni
 *   si aprono al tocco successivo (l'utente ha appena detto no: non lo si porta via di colpo);
 * - altrimenti (per esempio la prima richiesta chiusa toccando fuori, che non conta come rifiuto):
 *   nessun cambio e nessun messaggio.
 */
internal fun denialOutcome(
    previous: PermissionState,
    shouldShowRationale: Boolean,
    elapsedMillis: Long?,
): DenialOutcome = when {
    shouldShowRationale -> DenialOutcome(PermissionState.DENIED_ONCE)
    elapsedMillis != null && elapsedMillis <= INSTANT_DENIAL_MILLIS ->
        DenialOutcome(PermissionState.PERMANENT, message = true, openSettings = true)
    previous == PermissionState.DENIED_ONCE ->
        DenialOutcome(PermissionState.PERMANENT, message = true)
    else -> DenialOutcome(previous)
}

/**
 * Tocco su «Concedi» (decisione pura): le impostazioni solo se il permesso è [PermissionState.PERMANENT]
 * e Android conferma di non voler più mostrare il dialogo (rationale falsa). Con la rationale vera il
 * dialogo compare ancora, quindi si lancia la richiesta.
 */
internal fun tapOpensSettings(state: PermissionState, shouldShowRationale: Boolean): Boolean =
    state == PermissionState.PERMANENT && !shouldShowRationale

/**
 * Orchestra una richiesta di permessi runtime senza dipendere da Android: il lancio della richiesta,
 * l'apertura delle impostazioni, il messaggio e l'orologio arrivano come funzioni. In questo modo la
 * logica di «Concedi» (richiesta di sistema oppure impostazioni) si prova su JVM.
 */
internal class PermissionRequestCoordinator(
    private val store: PermissionStateStore,
    private val isGranted: (String) -> Boolean,
    private val shouldShowRationale: (String) -> Boolean,
    private val launch: (Array<String>) -> Unit,
    private val openAppSettings: () -> Unit,
    /** Messaggio «Abilita il permesso dalle impostazioni dell'app». */
    private val notifySettingsNeeded: () -> Unit,
    /** Orologio monotono in millisecondi, per riconoscere un esito immediato. */
    private val nowMillis: () -> Long,
) {
    private var launchedAtMillis: Long? = null

    /**
     * Tocco su «Concedi». Restituisce false se non c'era niente da concedere (tutto già concesso):
     * il chiamante aggiorna lo stato come prima.
     *
     * Se un permesso mancante è [PermissionState.PERMANENT] con rationale falsa, la richiesta non
     * partirebbe: messaggio e impostazioni, senza lanciarla. Altrimenti si lancia e si ricorda l'istante.
     */
    fun request(permissions: Array<String>): Boolean {
        val missing = permissions.filterNot(isGranted)
        if (missing.isEmpty()) return false
        val needsSettings = missing.any { tapOpensSettings(store.state(it), shouldShowRationale(it)) }
        if (needsSettings) {
            notifySettingsNeeded()
            openAppSettings()
        } else {
            launchedAtMillis = nowMillis()
            launch(missing.toTypedArray())
        }
        return true
    }

    /** Esito della richiesta di sistema: permesso -> concesso o meno. Vale per ciascun permesso. */
    fun onResult(result: Map<String, Boolean>) {
        val elapsed = launchedAtMillis?.let { nowMillis() - it }
        launchedAtMillis = null
        var message = false
        var settings = false
        for ((permission, granted) in result) {
            if (granted) {
                store.setState(permission, PermissionState.NONE)
                continue
            }
            val previous = store.state(permission)
            val outcome = denialOutcome(previous, shouldShowRationale(permission), elapsed)
            if (outcome.newState != previous) store.setState(permission, outcome.newState)
            message = message || outcome.message
            settings = settings || outcome.openSettings
        }
        if (message) notifySettingsNeeded()
        if (settings) openAppSettings()
    }
}
