package dev.argus.automation.vm

import dev.argus.ui.presentation.RenderLanguage
import java.util.Locale

/**
 * Etichette leggibili dei codici-motivo che finiscono nel Log (audit e journal). I codici sono
 * costanti emesse dall'app (mai testo dell'utente); un codice senza etichetta si mostra col codice
 * stesso come dettaglio tecnico.
 */
internal object LogReasonLabels {
    private data class Label(val en: String, val it: String)

    private val labels: Map<String, Label> = mapOf(
        // --- Codici arrivati con la 0.3.6 (permessi, Shizuku, DND, esecuzioni interrotte) ---
        "foreground_service_denied" to Label(
            "Android did not allow the foreground service",
            "Android non ha permesso il servizio in primo piano",
        ),
        "location_disabled" to Label("location is turned off", "la localizzazione è spenta"),
        "shizuku_unavailable" to Label("Shizuku is not running", "Shizuku non è attivo"),
        "shizuku_timeout" to Label("Shizuku did not respond in time", "Shizuku non ha risposto in tempo"),
        "shizuku_unsupported" to Label("Shizuku version is too old", "la versione di Shizuku è troppo vecchia"),
        "dnd_change_not_applied" to Label(
            "Android did not apply the Do Not Disturb change",
            "Android non ha applicato il cambio di Non disturbare",
        ),
        "one_shot_in_past" to Label(
            "the one-time rule's time has already passed",
            "l'orario della regola una tantum è già passato",
        ),
        "execution_interrupted" to Label(
            "execution interrupted, the app was closed",
            "esecuzione interrotta, l'app è stata chiusa",
        ),
        // --- Blocchi di policy al momento dello scatto ---
        "approval_fingerprint_mismatch" to Label(
            "the rule changed after it was approved",
            "la regola è cambiata dopo l'approvazione",
        ),
        "capability_snapshot_unavailable" to Label(
            "device capabilities could not be read",
            "le capacità del dispositivo non sono leggibili",
        ),
        "capability_unavailable" to Label(
            "a required capability is not available",
            "una capacità necessaria non è disponibile",
        ),
        "capability_requirements_mismatch" to Label(
            "the capabilities the rule needs changed",
            "le capacità richieste dalla regola sono cambiate",
        ),
        "live_confirmation_required" to Label(
            "live confirmation required",
            "serve una conferma in tempo reale",
        ),
        "reply_event_unverified" to Label(
            "the notification could not be verified for a reply",
            "la notifica non è verificabile per rispondere",
        ),
        "reply_notification_unavailable" to Label(
            "the notification to reply to is no longer available",
            "la notifica a cui rispondere non è più disponibile",
        ),
        "schema_incompatible" to Label(
            "the rule format is not compatible with this version",
            "il formato della regola non è compatibile con questa versione",
        ),
        "shell_external_trigger" to Label(
            "shell commands cannot run from an external trigger",
            "i comandi shell non possono partire da un trigger esterno",
        ),
        "validation_failed" to Label("the rule no longer passes validation", "la regola non supera più la validazione"),
        // --- Arresti dei programmi (cicli, condizioni, variabili) ---
        "invalid_program" to Label("the rule program is not valid", "il programma della regola non è valido"),
        "trigger_mismatch" to Label("the event does not match the trigger", "l'evento non corrisponde al trigger"),
        "binding_unavailable" to Label("a variable could not be filled", "una variabile non è stata valorizzata"),
        "binding_state_unavailable" to Label(
            "a variable needs a state that could not be read",
            "una variabile richiede uno stato non leggibile",
        ),
        "condition_state_unavailable" to Label(
            "required state unavailable",
            "stato necessario non disponibile",
        ),
        "sim_unknown" to Label(
            "the SIM of the call or SMS could not be determined",
            "non è stato possibile capire la SIM della chiamata o dell'SMS",
        ),
        "variable_unavailable" to Label("a variable has no value", "una variabile non ha valore"),
        "interpolation_policy_missing" to Label(
            "a text template has no approved policy",
            "un modello di testo non ha una policy approvata",
        ),
        "interpolation_malformed" to Label("a text template is malformed", "un modello di testo è malformato"),
        "interpolation_too_long" to Label("a text template is too long", "un modello di testo è troppo lungo"),
        "resolved_action_invalid" to Label(
            "the action became invalid after filling variables",
            "l'azione non è più valida dopo aver inserito le variabili",
        ),
        "taint_blocked" to Label(
            "external content is not allowed in this action",
            "il contenuto esterno non è ammesso in questa azione",
        ),
        "remote_egress_blocked" to Label(
            "private data cannot be sent to a remote service",
            "i dati privati non possono andare a un servizio remoto",
        ),
        "p4_invoke_llm_v2_unsupported" to Label(
            "this AI action is not supported in a program",
            "questa azione AI non è supportata in un programma",
        ),
        "deadline_exceeded" to Label("the rule ran for too long", "la regola è durata troppo a lungo"),
        "capture_missing" to Label("the result to keep was missing", "il risultato da conservare mancava"),
        "capture_too_long" to Label("the result to keep is too long", "il risultato da conservare è troppo lungo"),
        "while_count_unavailable" to Label(
            "the repeat count could not be read",
            "il numero di ripetizioni non è leggibile",
        ),
        "cancelled" to Label("execution cancelled", "esecuzione annullata"),
        // --- Pianificazione e attivazione ---
        "scheduling_failed" to Label("the schedule could not be set", "la pianificazione non è riuscita"),
        "expired" to Label("the time has already passed", "l'orario è già passato"),
        "dispatch_failed" to Label("the scheduled run could not start", "l'esecuzione pianificata non è partita"),
        "reschedule_failed" to Label("the next run could not be scheduled", "la prossima esecuzione non è pianificabile"),
        "reschedule_expired" to Label("the next run time has already passed", "l'orario della prossima esecuzione è già passato"),
        "registrar_failed" to Label("the rule could not be registered", "la regola non si è potuta registrare"),
        "review_required" to Label("the rule needs a review first", "la regola va prima rivista"),
        "immediate_dispatch_failed" to Label("the immediate run could not start", "l'esecuzione immediata non è partita"),
        // --- 0.4.1: azioni senza Shizuku e richiesta HTTP ---
        "deferred_to_notification" to Label(
            "Android blocks opening it from the background: a notification asks you to tap",
            "Android blocca l'apertura dal background: una notifica chiede di toccare",
        ),
        "activity_start_blocked" to Label(
            "Android blocked opening it from the background",
            "Android ha bloccato l'apertura dal background",
        ),
        "tts_unavailable" to Label(
            "no text-to-speech engine is installed",
            "nessun motore di sintesi vocale installato",
        ),
        "tts_failed" to Label(
            "the text-to-speech engine reported an error",
            "il motore di sintesi vocale ha dato un errore",
        ),
        "tts_timeout" to Label(
            "the reading did not finish in time",
            "la lettura non è finita in tempo",
        ),
        "write_settings_unavailable" to Label(
            "the \"Modify system settings\" access is missing",
            "manca l'accesso a \"Modifica impostazioni di sistema\"",
        ),
        "settings_write_failed" to Label(
            "Android did not accept the setting",
            "Android non ha accettato l'impostazione",
        ),
        "http_request_invalid" to Label(
            "the request is not valid (address, headers or body)",
            "la richiesta non è valida (indirizzo, intestazioni o body)",
        ),
        "http_timeout" to Label("the request took too long", "la richiesta ha impiegato troppo tempo"),
        "http_network_error" to Label("the server could not be reached", "il server non è raggiungibile"),
        "http_tls_error" to Label("the secure connection failed", "la connessione sicura non è riuscita"),
        "http_redirect_blocked" to Label(
            "the server redirected to a different address",
            "il server ha rimandato a un altro indirizzo",
        ),
        "http_too_many_redirects" to Label("too many redirects", "troppi reindirizzamenti"),
        "json_quote_context" to Label(
            "external data in a JSON body must sit inside a quoted string",
            "un dato esterno in un body JSON deve stare dentro una stringa tra virgolette",
        ),
        // --- 0.5.1: azioni privilegiate tipizzate ---
        "target_forbidden" to Label(
            "Argus, the launcher and essential system apps cannot be changed by a rule",
            "Argus, il launcher e le app di sistema essenziali non si possono modificare con una regola",
        ),
        "nfc_unavailable" to Label("this phone has no NFC", "questo telefono non ha l'NFC"),
        "permission_not_applied" to Label(
            "Android did not apply the permission change (the app may not use that permission)",
            "Android non ha applicato il cambio di permesso (l'app potrebbe non usare quel permesso)",
        ),
        "screenshot_save_failed" to Label(
            "the screenshot could not be saved",
            "non è stato possibile salvare la schermata",
        ),
        "screen_capture_failed" to Label(
            "the screenshot could not be taken",
            "non è stato possibile catturare la schermata",
        ),
        "screen_capture_invalid" to Label(
            "the screen capture was not a valid image",
            "la cattura dello schermo non è un'immagine valida",
        ),
        // --- 0.4.1: «Pausa automazioni» ---
        "paused" to Label(
            "automations are paused",
            "le automazioni sono in pausa",
        ),
        // --- 0.5.1: macro sullo schermo (Accessibilità) ---
        "ui_steps_external_trigger" to Label(
            "a screen macro cannot start from this trigger",
            "una macro sullo schermo non può partire da questo trigger",
        ),
        "ui_service_unavailable" to Label(
            "the Accessibility service of Argus is not on",
            "il servizio di Accessibilità di Argus non è attivo",
        ),
        "ui_notification_unavailable" to Label(
            "the macro needs the notification with Stop, and notifications are off",
            "la macro richiede la notifica con Ferma, e le notifiche sono spente",
        ),
        "ui_busy" to Label(
            "another screen macro is already running",
            "un'altra macro sullo schermo è già in corso",
        ),
        "ui_forbidden_screen" to Label(
            "the macro reached a screen it may not touch (Argus, Settings, installer or permissions)",
            "la macro è arrivata su una schermata che non può toccare (Argus, Impostazioni, installatore o permessi)",
        ),
        "ui_open_failed" to Label(
            "the app did not come to the front",
            "l'app non è passata in primo piano",
        ),
        "ui_element_not_found" to Label(
            "the element to tap was not on the screen",
            "l'elemento da toccare non era sullo schermo",
        ),
        "ui_check_failed" to Label(
            "a check on the screen did not hold",
            "un controllo sullo schermo non è andato a buon fine",
        ),
        "ui_timeout" to Label(
            "the element did not appear in time",
            "l'elemento non è comparso in tempo",
        ),
        "ui_macro_timeout" to Label(
            "the screen macro ran for more than 60 seconds",
            "la macro sullo schermo è durata più di 60 secondi",
        ),
        "ui_stopped" to Label(
            "you stopped the screen macro",
            "hai fermato la macro sullo schermo",
        ),
        "ui_click_failed" to Label(
            "Android did not accept the tap",
            "Android non ha accettato il tocco",
        ),
        "ui_input_failed" to Label(
            "there was no text field in focus to type into",
            "nessun campo di testo attivo in cui scrivere",
        ),
        "ui_scroll_failed" to Label(
            "there was nothing to scroll",
            "non c'era niente da scorrere",
        ),
        "ui_action_failed" to Label(
            "Android did not accept the Back or Home press",
            "Android non ha accettato il tasto Indietro o Home",
        ),
        // --- Codici generici (nessun motivo più preciso disponibile) ---
        "execution_error" to Label("unspecified error", "errore non specificato"),
        "policy_blocked" to Label("unspecified policy", "policy non specificata"),
        "arm_failed" to Label("unspecified reason", "motivo non specificato"),
        "enable_failed" to Label("unspecified reason", "motivo non specificato"),
    )

    val knownCodes: Set<String> get() = labels.keys

    /** Etichetta nella lingua richiesta, o null se il codice non è noto. */
    fun label(code: String, l: RenderLanguage): String? =
        labels[code]?.let { l.pick(it.en, it.it) } ?: commandExitLabel(code, l) ?: httpStatusLabel(code, l)

    /** Chiamata HTTP finita con un codice non 2xx (`http_status_<n>`): conta il codice, mai il corpo. */
    private fun httpStatusLabel(code: String, l: RenderLanguage): String? {
        val status = HTTP_STATUS.matchEntire(code)?.groupValues?.get(1) ?: return null
        return l.pick("the server answered with status $status", "il server ha risposto con stato $status")
    }

    /**
     * Comandi della shell privilegiata finiti con un codice d'uscita (`<operazione>_exit_<n>`, es.
     * `set_wifi_exit_1`): l'operazione è un nome interno, il codice d'uscita è l'informazione utile.
     */
    private fun commandExitLabel(code: String, l: RenderLanguage): String? {
        val exit = COMMAND_EXIT.matchEntire(code)?.groupValues?.get(1) ?: return null
        return l.pick("the system command ended with code $exit", "il comando di sistema è terminato con codice $exit")
    }

    /** Etichetta se nota, altrimenti il codice stesso (ripulito) come dettaglio tecnico. */
    fun text(code: String, l: RenderLanguage): String =
        label(code, l) ?: code.technicalCode()

    private fun String.technicalCode(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .take(MAX_CODE_CHARS)
        .ifBlank { "unknown" }

    private const val MAX_CODE_CHARS = 64
    private val COMMAND_EXIT = Regex("^[a-z][a-z0-9_]*_exit_([0-9]{1,3})$")
    private val HTTP_STATUS = Regex("^http_status_([1-5][0-9]{2})$")
}
