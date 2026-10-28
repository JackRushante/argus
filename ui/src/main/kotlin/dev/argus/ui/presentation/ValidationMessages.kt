package dev.argus.ui.presentation

import dev.argus.engine.safety.ValidationIssue

/**
 * B9 · Motivi leggibili per ogni codice di validazione, conflitto o arm, in EN e IT.
 *
 * Il validator emette codici stabili (vocabolario chiuso) con un messaggio diagnostico italiano; la
 * UI non deve mai mostrare quel messaggio a chi usa l'app in inglese né un "codice con spazi". Qui
 * ogni codice ha il suo testo per lingua. Un codice sconosciuto (versione futura, dato corrotto)
 * produce un testo generico localizzato col codice tra parentesi: mai vuoto, mai un'eccezione.
 *
 * Copertura: `ValidationMessagesTest` legge il sorgente di DraftValidator, ConflictDetector,
 * Approval e ApprovalFlow ed esige che ogni codice emesso sia in tabella.
 *
 * Pura logica JVM: nessuna risorsa Android.
 */
object ValidationMessages {

    private class Text(val en: String, val it: String)

    private fun t(en: String, it: String) = Text(en, it)

    /** Codici il cui [ValidationIssue.detail] completa il testo (capability, famiglia reader, tipo). */
    private val DETAIL_CODES = setOf(
        "capability_unavailable",
        "capability_temporarily_unavailable",
        "state_query_unavailable",
        "state_query_type_mismatch",
    )

    private val table: Map<String, Text> = mapOf(
        // --- Regola ---------------------------------------------------------------------------
        "name_invalid" to t(
            "The rule needs a name of up to 120 characters.",
            "La regola ha bisogno di un nome lungo al massimo 120 caratteri.",
        ),
        "no_actions" to t("The rule has no actions.", "La regola non ha azioni."),
        "cooldown_invalid" to t("The cooldown cannot be negative.", "Il cooldown non può essere negativo."),
        "cooldown_raised" to t(
            "Cooldown below 60 s on an AI rule: Argus will enforce 60 s.",
            "Cooldown sotto i 60 s su una regola con AI: Argus userà 60 s.",
        ),
        "sensor_cooldown_invalid" to t(
            "Sensor triggers need a cooldown between 1 minute and 7 days.",
            "I trigger sensore richiedono un cooldown tra 1 minuto e 7 giorni.",
        ),
        "conditions_too_many" to t(
            "Too many conditions (maximum 64).",
            "Troppe condizioni (massimo 64).",
        ),
        "condition_too_deep" to t(
            "The conditions are nested too deeply.",
            "Le condizioni sono annidate troppo in profondità.",
        ),
        "condition_empty" to t(
            "An AND/OR condition has no members.",
            "Una condizione E/O non ha elementi.",
        ),
        "too_many_actions_total" to t(
            "Too many actions in total (maximum 64).",
            "Troppe azioni in totale (massimo 64).",
        ),
        "flow_too_deep" to t(
            "If/repeat blocks are nested too deeply (maximum 4 levels).",
            "I blocchi se/ripeti sono annidati troppo in profondità (massimo 4 livelli).",
        ),
        "flow_empty_branch" to t(
            "An if or repeat block has no actions to run.",
            "Un blocco se o ripeti non ha azioni da eseguire.",
        ),
        "time_budget_exceeded" to t(
            "The worst-case running time is over 6 hours: reduce repeats, delays or long actions.",
            "Il tempo massimo di esecuzione supera le 6 ore: riduci ripetizioni, attese o azioni lunghe.",
        ),

        // --- Trigger --------------------------------------------------------------------------
        "time_spec" to t(
            "A time trigger needs exactly one of: a schedule, a date and time, a delay, or sunrise/sunset.",
            "Un trigger a tempo richiede esattamente uno tra pianificazione, data e ora, ritardo, o alba/tramonto.",
        ),
        "tz_invalid" to t("The time zone is not valid.", "Il fuso orario non è valido."),
        "cron_invalid" to t("The schedule expression is not valid.", "L'espressione di pianificazione non è valida."),
        "at_invalid" to t("The date and time are not valid.", "La data e l'ora non sono valide."),
        "after_ms_invalid" to t(
            "The delay must be between 1 second and 7 days.",
            "Il ritardo deve essere tra 1 secondo e 7 giorni.",
        ),
        "solar_offset_invalid" to t(
            "The shift from sunrise or sunset must be between 12 hours before and 12 hours after.",
            "Lo spostamento da alba o tramonto deve essere tra 12 ore prima e 12 ore dopo.",
        ),
        "solar_location_invalid" to t(
            "The position for sunrise and sunset is not valid: the phone reads it when the rule is activated.",
            "La posizione per alba e tramonto non è valida: la legge il telefono quando la regola viene attivata.",
        ),
        "radius_invalid" to t(
            "The location radius is not valid (up to 100 km).",
            "Il raggio del luogo non è valido (fino a 100 km).",
        ),
        "radius_small" to t(
            "Radius under 100 m: the trigger can arrive minutes late in the background.",
            "Raggio sotto i 100 m: lo scatto in background può arrivare con minuti di ritardo.",
        ),
        "loitering_invalid" to t(
            "The loitering delay is out of range.",
            "L'attesa di permanenza è fuori intervallo.",
        ),
        "geofence_transition_unsupported" to t(
            "Only entering or leaving a place is supported, not dwelling.",
            "Sono supportati solo l'ingresso e l'uscita da un luogo, non la permanenza.",
        ),
        "geofence_loitering_unsupported" to t(
            "The loitering delay needs dwell detection, which is not available.",
            "L'attesa di permanenza richiede il rilevamento della sosta, non disponibile.",
        ),
        "geofence_coords" to t(
            "The coordinates are missing or out of range.",
            "Le coordinate mancano o sono fuori intervallo.",
        ),
        "notification_own_package" to t(
            "Argus notifications cannot trigger Argus rules.",
            "Le notifiche di Argus non possono innescare regole di Argus.",
        ),
        "conversation_id_invalid" to t(
            "The conversation id cannot be empty.",
            "L'id della conversazione non può essere vuoto.",
        ),
        "sender_spoofable" to t(
            "Matching by display name can be spoofed: prefer a verified conversation.",
            "Il confronto per nome visualizzato è falsificabile: meglio una conversazione verificata.",
        ),
        "sender_invalid" to t("The sender filter is empty or too long.", "Il filtro mittente è vuoto o troppo lungo."),
        "title_match_invalid" to t("The title filter is empty or too long.", "Il filtro titolo è vuoto o troppo lungo."),
        "text_match_invalid" to t("The text filter is empty or too long.", "Il filtro testo è vuoto o troppo lungo."),
        "sms_text_match_invalid" to t(
            "The SMS text filter is invalid or used on a trigger that is not an incoming SMS.",
            "Il filtro sul testo dell'SMS non è valido o è usato su un trigger che non è un SMS in arrivo.",
        ),
        "number_invalid" to t("The number filter is empty or too long.", "Il filtro numero è vuoto o troppo lungo."),
        "match_invalid" to t("The device filter is empty or too long.", "Il filtro dispositivo è vuoto o troppo lungo."),
        "sim_invalid" to t(
            "The SIM filter must be SIM 1 or SIM 2.",
            "Il filtro SIM deve essere SIM 1 o SIM 2.",
        ),
        "battery_spec" to t(
            "A battery trigger needs exactly one threshold: below or above.",
            "Un trigger batteria richiede esattamente una soglia: sotto oppure sopra.",
        ),
        "battery_threshold_invalid" to t(
            "The battery threshold must be between 1% and 99%.",
            "La soglia della batteria deve essere tra 1% e 99%.",
        ),
        "mode_to_invalid" to t(
            "The mode is not valid: Do Not Disturb is off, priority or total; the ringer is normal, vibrate or silent.",
            "La modalità non è valida: Non disturbare è off, priority o total; la suoneria è normal, vibrate o silent.",
        ),
        "pkg_blank" to t("The app package is missing.", "Il package dell'app manca."),
        "package_invalid" to t("The app package name is not valid.", "Il nome del package dell'app non è valido."),
        "sensor_event_count_invalid" to t(
            "The event count is not valid for this sensor.",
            "Il conteggio degli eventi non è valido per questo sensore.",
        ),
        "sensor_sampling_unsupported" to t(
            "Continuous or high-rate sensor sampling is not available.",
            "Il campionamento continuo o ad alta frequenza del sensore non è disponibile.",
        ),

        // --- Condizioni -----------------------------------------------------------------------
        "time_window_invalid" to t(
            "The time window is not valid: use HH:mm and a time zone.",
            "La finestra oraria non è valida: usa HH:mm e un fuso orario.",
        ),
        "state_key_unknown" to t(
            "The device state used in a condition is not known.",
            "Lo stato del dispositivo usato in una condizione non è noto.",
        ),
        "state_value_invalid" to t(
            "The value or operator is not valid for this device state.",
            "Il valore o l'operatore non è valido per questo stato del dispositivo.",
        ),
        "state_query_policy_incompatible" to t(
            "The state reader policy version is not compatible.",
            "La versione della policy del lettore di stato non è compatibile.",
        ),
        "state_query_invalid" to t(
            "The state reader or its parameters are not valid.",
            "Il lettore di stato o i suoi parametri non sono validi.",
        ),
        "state_compare_invalid" to t(
            "The type, operator or expected value do not fit the state reader.",
            "Tipo, operatore o valore atteso non sono adatti al lettore di stato.",
        ),
        "location_invalid" to t(
            "The condition coordinates are out of range.",
            "Le coordinate della condizione sono fuori intervallo.",
        ),
        "var_compare_outside_flow" to t(
            "Comparing variables is only allowed inside if or repeat blocks.",
            "Il confronto tra variabili è ammesso solo dentro i blocchi se o ripeti.",
        ),
        "var_compare_undeclared" to t(
            "A comparison uses a variable that was not declared.",
            "Un confronto usa una variabile che non è stata dichiarata.",
        ),
        "var_not_definitely_assigned" to t(
            "A variable may be used before it has a value on every path.",
            "Una variabile può essere usata prima di avere un valore su ogni percorso.",
        ),
        "var_compare_rhs_invalid" to t(
            "A comparison needs exactly one value to compare against (or none for even/odd).",
            "Un confronto richiede esattamente un valore di confronto (nessuno per pari/dispari).",
        ),
        "var_compare_type_invalid" to t(
            "The comparison operator or value does not fit the variable type.",
            "L'operatore o il valore del confronto non è adatto al tipo della variabile.",
        ),
        "var_compare_invalid" to t(
            "The comparison value is too long or contains control characters.",
            "Il valore di confronto è troppo lungo o contiene caratteri di controllo.",
        ),

        // --- Variabili, capture, interpolazione ----------------------------------------------
        "too_many_vars" to t(
            "Too many variables (maximum 16, captured outputs included).",
            "Troppe variabili (massimo 16, comprese le uscite catturate).",
        ),
        "var_name_invalid" to t(
            "A variable name is not valid: use lowercase letters, digits and underscores.",
            "Il nome di una variabile non è valido: usa minuscole, cifre e trattini bassi.",
        ),
        "var_name_duplicate" to t("Two variables have the same name.", "Due variabili hanno lo stesso nome."),
        "var_state_query_invalid" to t(
            "A variable reads a state reader that is not valid.",
            "Una variabile legge un lettore di stato non valido.",
        ),
        "var_state_policy_incompatible" to t(
            "A variable uses an incompatible state reader policy.",
            "Una variabile usa una policy del lettore di stato non compatibile.",
        ),
        "var_state_type_invalid" to t(
            "A variable declares a type its state reader cannot return.",
            "Una variabile dichiara un tipo che il suo lettore di stato non può restituire.",
        ),
        "var_state_underclassified" to t(
            "A state reader variable is classified below its minimum privacy level.",
            "Una variabile da lettore di stato è classificata sotto il livello minimo di riservatezza.",
        ),
        "var_random_max_invalid" to t(
            "The random number limit must be between 1 and 1,000,000.",
            "Il limite del numero casuale deve essere tra 1 e 1.000.000.",
        ),
        "var_clock_tz_invalid" to t(
            "A clock variable uses a time zone that does not exist: use an IANA name such as Europe/Rome, or leave it empty for the phone's time zone.",
            "Una variabile orologio usa un fuso orario che non esiste: usa un nome IANA come Europe/Rome, oppure lascialo vuoto per il fuso del telefono.",
        ),
        "var_clock_pattern_invalid" to t(
            "A clock variable has a time format that is not valid or longer than 40 characters (for example HH:mm or EEEE d MMMM).",
            "Una variabile orologio ha un formato dell'ora non valido o più lungo di 40 caratteri (per esempio HH:mm o EEEE d MMMM).",
        ),
        "var_literal_invalid" to t(
            "A fixed value is too long or contains control characters.",
            "Un valore fisso è troppo lungo o contiene caratteri di controllo.",
        ),
        "var_literal_type_invalid" to t(
            "A fixed value does not match its declared type.",
            "Un valore fisso non corrisponde al tipo dichiarato.",
        ),
        "var_trigger_payload_unsupported" to t(
            "A variable reads the trigger payload, but this trigger has none.",
            "Una variabile legge il contenuto del trigger, ma questo trigger non ne ha.",
        ),
        "var_trigger_field_unsupported" to t(
            "A variable reads a trigger field that does not exist for this trigger.",
            "Una variabile legge un campo del trigger che non esiste per questo trigger.",
        ),
        "var_trigger_underclassified" to t(
            "External data must be classified at least private.",
            "I dati esterni devono essere classificati almeno come privati.",
        ),
        "var_extraction_regex_invalid" to t(
            "A variable extraction pattern is empty, unsafe or incompatible.",
            "Il modello di estrazione di una variabile è vuoto, non sicuro o incompatibile.",
        ),
        "capture_name_invalid" to t(
            "A captured output name is not valid.",
            "Il nome di un'uscita catturata non è valido.",
        ),
        "capture_name_duplicate" to t(
            "A captured output name is already used by another capture or variable.",
            "Il nome di un'uscita catturata è già usato da un'altra cattura o variabile.",
        ),
        "p4_invoke_llm_v2_unsupported" to t(
            "The shared-state AI action is not allowed in programs with variables or flow control yet.",
            "L'azione AI con stato condiviso non è ancora ammessa in programmi con variabili o blocchi di flusso.",
        ),
        "interpolation_malformed" to t(
            "A \${variable} reference in a text field is malformed.",
            "Un riferimento \${variabile} in un campo di testo è malformato.",
        ),
        "interpolation_undeclared_var" to t(
            "A text field uses a variable that was not declared.",
            "Un campo di testo usa una variabile che non è stata dichiarata.",
        ),
        "interpolation_tainted_authority" to t(
            "External data is not allowed in this command or target field.",
            "I dati esterni non sono ammessi in questo campo di comando o destinazione.",
        ),
        "interpolation_shell_context" to t(
            "External data sits where a shell command cannot quote it safely (command substitution, backticks, here-document or comment): the action would be blocked when it runs.",
            "Un dato esterno è in un punto del comando shell dove non si può quotare in sicurezza (sostituzione di comando, backtick, here-document o commento): l'azione verrebbe bloccata all'esecuzione.",
        ),
        "secret_interpolation_disclosure" to t(
            "A secret variable will be sent to the configured AI service.",
            "Una variabile segreta verrà inviata al servizio AI configurato.",
        ),
        "while_iterations_ambiguous" to t(
            "A repeat block needs exactly one of: a fixed count or a count variable.",
            "Un blocco ripeti richiede esattamente uno tra conteggio fisso e variabile di conteggio.",
        ),
        "while_iterations_var_undeclared" to t(
            "The repeat count variable was not declared.",
            "La variabile del conteggio di ripetizioni non è stata dichiarata.",
        ),
        "while_iterations_var_type_invalid" to t(
            "The repeat count variable must be a number.",
            "La variabile del conteggio di ripetizioni deve essere un numero.",
        ),
        "while_iterations_invalid" to t(
            "The repeat count must be between 1 and 1000.",
            "Il conteggio di ripetizioni deve essere tra 1 e 1000.",
        ),
        "while_delay_invalid" to t(
            "The delay between repeats must be between 0 and 1 hour.",
            "L'attesa tra le ripetizioni deve essere tra 0 e 1 ora.",
        ),

        // --- Azioni ---------------------------------------------------------------------------
        "ringer_mode_invalid" to t("The ringer mode is not valid.", "La modalità suoneria non è valida."),
        "url_invalid" to t(
            "The URL is not valid or its scheme is not allowed.",
            "L'URL non è valido o lo schema non è consentito.",
        ),
        "title_invalid" to t("The title is missing or too long.", "Il titolo manca o è troppo lungo."),
        "text_invalid" to t("The text is missing or too long.", "Il testo manca o è troppo lungo."),
        "coordinates_invalid" to t("The tap coordinates are out of range.", "Le coordinate del tocco sono fuori intervallo."),
        "goal_invalid" to t("The AI goal is missing or too long.", "L'obiettivo dell'AI manca o è troppo lungo."),
        "shell_invalid" to t(
            "The shell command is missing, too long or contains an unrunnable character.",
            "Il comando shell manca, è troppo lungo o contiene un carattere non eseguibile.",
        ),
        "shell_external_trigger" to t(
            "Shell commands only run from time, location, connectivity or sensor triggers, or from a whitelisted one-to-one WhatsApp chat. SMS and calls are excluded because the sender can be forged.",
            "I comandi shell partono solo da trigger a tempo, luogo, connettività o sensore, oppure da una chat WhatsApp 1:1 in whitelist. SMS e chiamate sono esclusi perché il mittente è falsificabile.",
        ),
        "shell_review" to t(
            "The shell command runs exactly as approved, with a 30 s limit.",
            "Il comando shell viene eseguito esattamente come approvato, con limite di 30 s.",
        ),
        "shell_contact_trigger" to t(
            "The whitelisted contact can start this command whenever the message matches; the command stays the approved one, the timing is theirs.",
            "Il contatto in whitelist potrà avviare questo comando ogni volta che il messaggio corrisponde: il comando resta quello approvato, il momento lo sceglie lui.",
        ),
        "clipboard_source_missing" to t(
            "Copying to the clipboard needs a trigger with text (an SMS or a notification).",
            "Copiare negli appunti richiede un trigger con testo (un SMS o una notifica).",
        ),
        "extraction_regex_invalid" to t(
            "The extraction pattern is unsafe, incompatible or too long.",
            "Il modello di estrazione non è sicuro, è incompatibile o è troppo lungo.",
        ),
        "alarm_time_invalid" to t("The alarm time is out of range.", "L'ora della sveglia è fuori intervallo."),
        "alarm_label_invalid" to t("The alarm label is empty or too long.", "L'etichetta della sveglia è vuota o troppo lunga."),
        "timer_seconds_invalid" to t(
            "The timer length must be between 1 second and 24 hours.",
            "La durata del timer deve essere tra 1 secondo e 24 ore.",
        ),
        "timer_label_invalid" to t("The timer label is empty or too long.", "L'etichetta del timer è vuota o troppo lunga."),
        "volume_level_invalid" to t("The volume level must be between 0 and 100.", "Il livello del volume deve essere tra 0 e 100."),
        "settings_pkg_missing" to t(
            "The app details screen needs an app package.",
            "La schermata dettagli app richiede un package.",
        ),
        "settings_pkg_invalid" to t("The app package for the settings screen is not valid.", "Il package per la schermata impostazioni non è valido."),
        "settings_pkg_unexpected" to t(
            "A package is only allowed for the app details screen.",
            "Il package è ammesso solo per la schermata dettagli app.",
        ),
        "vibrate_duration_invalid" to t(
            "The vibration length must be between 1 and 10,000 ms.",
            "La durata della vibrazione deve essere tra 1 e 10.000 ms.",
        ),
        "wait_duration_invalid" to t(
            "The pause length must be between 1 ms and 1 hour.",
            "La durata della pausa deve essere tra 1 ms e 1 ora.",
        ),
        "write_setting_key_invalid" to t(
            "The setting name is not valid (shape or length).",
            "Il nome dell'impostazione non è valido (forma o lunghezza).",
        ),
        "write_setting_value_invalid" to t(
            "The setting value is empty, too long or contains control characters.",
            "Il valore dell'impostazione è vuoto, troppo lungo o contiene caratteri di controllo.",
        ),
        "write_setting_review" to t(
            "The setting is written exactly as shown in the rule: check the namespace, name and value.",
            "L'impostazione viene scritta esattamente come mostrato nella regola: controlla namespace, nome e valore.",
        ),
        "write_setting_high_impact" to t(
            "This rule changes a phone security setting (debugging, accessibility, keyboard or app verification). Only arm it if you know what it does.",
            "Questa regola cambia un'impostazione di sicurezza del telefono (debug, accessibilità, tastiera o verifica delle app). Armala solo se sai cosa fa.",
        ),
        "write_setting_external_value" to t(
            "The value written comes from external data (a message or notification).",
            "Il valore scritto arriva da un dato esterno (messaggio o notifica).",
        ),

        // --- Azioni generative ----------------------------------------------------------------
        "state_context_empty" to t(
            "The shared-state AI action needs at least one explicit state reader.",
            "L'azione AI con stato condiviso richiede almeno un lettore di stato esplicito.",
        ),
        "state_context_too_large" to t(
            "Too many state readers in the AI action (maximum 16).",
            "Troppi lettori di stato nell'azione AI (massimo 16).",
        ),
        "state_context_duplicated" to t(
            "The same state reader is listed twice in the AI action.",
            "Lo stesso lettore di stato è elencato due volte nell'azione AI.",
        ),
        "state_context_policy_incompatible" to t(
            "A state reader in the AI action has an incompatible policy version.",
            "Un lettore di stato nell'azione AI ha una versione di policy non compatibile.",
        ),
        "state_context_query_invalid" to t(
            "A state reader in the AI action is not valid.",
            "Un lettore di stato nell'azione AI non è valido.",
        ),
        "state_context_type_invalid" to t(
            "A state reader in the AI action declares a type it cannot return.",
            "Un lettore di stato nell'azione AI dichiara un tipo che non può restituire.",
        ),
        "state_context_integrity_invalid" to t(
            "An approved local state reader must be marked trusted.",
            "Un lettore di stato locale approvato deve essere marcato come attendibile.",
        ),
        "state_context_underclassified" to t(
            "A state reader in the AI action is classified below its minimum privacy level.",
            "Un lettore di stato nell'azione AI è classificato sotto il livello minimo di riservatezza.",
        ),
        "state_context_disclosure" to t(
            "The listed state readers will be sent to the configured AI service.",
            "I lettori di stato elencati verranno inviati al servizio AI configurato.",
        ),
        "secret_state_context" to t(
            "This rule shares secret device state with the configured AI service.",
            "Questa regola condivide stato segreto del dispositivo con il servizio AI configurato.",
        ),
        "allowed_tools_unsupported" to t(
            "The allowed tools must include the WhatsApp reply and at most web search.",
            "Gli strumenti consentiti devono includere la risposta WhatsApp e al più la ricerca web.",
        ),
        "no_tools" to t("The AI action has no allowed tools.", "L'azione AI non ha strumenti consentiti."),
        "too_many_tools" to t("Too many tools in the AI action.", "Troppi strumenti nell'azione AI."),
        "timeout_invalid" to t(
            "The AI action timeout is out of range (1 to 120 seconds).",
            "Il timeout dell'azione AI è fuori intervallo (da 1 a 120 secondi).",
        ),
        "tool_invalid" to t("A tool name is not valid.", "Il nome di uno strumento non è valido."),
        "tool_forbidden" to t(
            "A tool is forbidden in AI actions at run time.",
            "Uno strumento è vietato nelle azioni AI all'esecuzione.",
        ),
        "tool_unknown" to t("A tool is not in the catalog.", "Uno strumento non è nel catalogo."),
        "reply_target_unbound" to t(
            "A reply tool must be bound to the sender of the trigger.",
            "Uno strumento di risposta deve essere vincolato al mittente del trigger.",
        ),
        "reply_target_without_tool" to t(
            "The reply is bound to the sender, but no reply tool is allowed.",
            "La risposta è vincolata al mittente, ma nessuno strumento di risposta è consentito.",
        ),
        "context_sources_invalid" to t(
            "The context sources are not valid or are too many.",
            "Le fonti di contesto non sono valide o sono troppe.",
        ),
        "context_sources_empty" to t(
            "The AI action needs at least the notification context source.",
            "L'azione AI richiede almeno la fonte di contesto delle notifiche.",
        ),
        "context_notification_required" to t(
            "The AI context must include the notification.",
            "Il contesto dell'AI deve includere la notifica.",
        ),
        "context_sources_duplicated" to t(
            "The same context source is listed twice.",
            "La stessa fonte di contesto è elencata due volte.",
        ),
        "context_source_unsupported" to t(
            "A context source is not supported by this kind of AI action.",
            "Una fonte di contesto non è supportata da questo tipo di azione AI.",
        ),
        "read_plus_reply" to t(
            "Read tools plus an outgoing channel can leak data.",
            "Strumenti di lettura più un canale in uscita possono esfiltrare dati.",
        ),
        "capture_required" to t(
            "An AI action that only captures a value needs a capture name.",
            "Un'azione AI che cattura solo un valore richiede un nome di cattura.",
        ),
        "reply_target_forbidden" to t(
            "This kind of AI action cannot bind a reply recipient.",
            "Questo tipo di azione AI non può vincolare un destinatario di risposta.",
        ),
        "notification_title_forbidden" to t(
            "This kind of AI action does not use a notification title.",
            "Questo tipo di azione AI non usa un titolo di notifica.",
        ),
        "notification_title_invalid" to t(
            "The notification title is missing, too long or contains control characters.",
            "Il titolo della notifica manca, è troppo lungo o contiene caratteri di controllo.",
        ),
        "reply_target_no_notification" to t(
            "A reply needs a notification trigger.",
            "Una risposta richiede un trigger di notifica.",
        ),
        "reply_wrong_package" to t(
            "A WhatsApp reply needs a recognized WhatsApp package.",
            "Una risposta WhatsApp richiede un package WhatsApp riconosciuto.",
        ),
        "reply_target_group" to t(
            "Automatic replies are only allowed in verified one-to-one chats.",
            "Le risposte automatiche sono ammesse solo in chat 1:1 verificate.",
        ),
        "reply_needs_conversation_id" to t(
            "Automatic replies need a conversation id.",
            "Le risposte automatiche richiedono un id di conversazione.",
        ),
        "target_not_whitelisted" to t(
            "The conversation is not in the whitelist.",
            "La conversazione non è in whitelist.",
        ),

        // --- Approvazione e arm ---------------------------------------------------------------
        "approval_integrity_failure" to t(
            "The draft failed its integrity check.",
            "La bozza non ha superato il controllo di integrità.",
        ),
        "approval_policy_unavailable" to t(
            "The device policy is not available right now.",
            "La policy del dispositivo non è disponibile in questo momento.",
        ),
        "capability_policy_unavailable" to t(
            "The device capabilities could not be checked.",
            "Non è stato possibile verificare le capacità del dispositivo.",
        ),
        "capability_unavailable" to t(
            "This rule needs a capability the device does not have right now.",
            "Questa regola richiede una capacità che il dispositivo non ha al momento.",
        ),
        "capability_temporarily_unavailable" to t(
            "A capability this rule needs is temporarily unavailable.",
            "Una capacità richiesta da questa regola è temporaneamente non disponibile.",
        ),
        "state_query_unavailable" to t(
            "A state reader used by this rule is not available on this device.",
            "Un lettore di stato usato da questa regola non è disponibile su questo dispositivo.",
        ),
        "state_query_type_mismatch" to t(
            "A state reader does not return the declared type.",
            "Un lettore di stato non restituisce il tipo dichiarato.",
        ),
        "draft_review_unavailable" to t(
            "This revision could not be verified. Reopen the details and try again.",
            "Non è stato possibile verificare questa revisione. Riapri il dettaglio e riprova.",
        ),
        "privacy_generative" to t(
            "This rule sends data to the configured AI service.",
            "Questa regola invia dati al servizio AI configurato.",
        ),
        "schema_or_integrity_review" to t(
            "The rule can no longer be verified and must be recreated.",
            "La regola non è più verificabile e deve essere ricreata.",
        ),

        // --- Conflitti tra regole (un codice per impostazione) -----------------------------
        "conflict_wifi" to t(
            "Another active rule sets Wi-Fi to the opposite value on triggers that may overlap.",
            "Un'altra regola attiva imposta il Wi-Fi sul valore opposto con trigger che possono sovrapporsi.",
        ),
        "conflict_bluetooth" to t(
            "Another active rule sets Bluetooth to the opposite value on triggers that may overlap.",
            "Un'altra regola attiva imposta il Bluetooth sul valore opposto con trigger che possono sovrapporsi.",
        ),
        "conflict_mobile_data" to t(
            "Another active rule sets mobile data to the opposite value on triggers that may overlap.",
            "Un'altra regola attiva imposta i dati mobili sul valore opposto con trigger che possono sovrapporsi.",
        ),
        "conflict_dnd" to t(
            "Another active rule sets Do Not Disturb to a different mode on triggers that may overlap.",
            "Un'altra regola attiva imposta Non disturbare su una modalità diversa con trigger che possono sovrapporsi.",
        ),
        "conflict_ringer" to t(
            "Another active rule sets the ringer to a different mode on triggers that may overlap.",
            "Un'altra regola attiva imposta la suoneria su una modalità diversa con trigger che possono sovrapporsi.",
        ),
        "conflict_dark_mode" to t(
            "Another active rule sets dark mode to a different value on triggers that may overlap.",
            "Un'altra regola attiva imposta la modalità scura su un valore diverso con trigger che possono sovrapporsi.",
        ),

        // --- 0.4.1 · azioni senza Shizuku e richiesta HTTP --------------------------------------
        "speak_text_invalid" to t(
            "The text to read aloud is required and can have up to 1,000 characters.",
            "Il testo da leggere ad alta voce è obbligatorio e lungo al massimo 1.000 caratteri.",
        ),
        "brightness_spec" to t(
            "Brightness needs exactly one of: a level or automatic.",
            "La luminosità richiede esattamente uno tra livello e automatica.",
        ),
        "brightness_level_invalid" to t(
            "The brightness level must be between 0 and 100.",
            "Il livello di luminosità deve essere tra 0 e 100.",
        ),
        "http_url_invalid" to t(
            "The request address must start with https:// (http:// is allowed only for localhost) and cannot contain a user name or password.",
            "L'indirizzo della richiesta deve iniziare con https:// (http:// è ammesso solo per localhost) e non può contenere nome utente o password.",
        ),
        "http_url_interpolated" to t(
            "The request address is fixed: it cannot contain a \${variable}.",
            "L'indirizzo della richiesta è fisso: non può contenere una \${variabile}.",
        ),
        "http_headers_too_many" to t(
            "A request can have at most 10 headers.",
            "Una richiesta può avere al massimo 10 intestazioni.",
        ),
        "http_header_interpolated" to t(
            "Headers are fixed: they cannot contain a \${variable}.",
            "Le intestazioni sono fisse: non possono contenere una \${variabile}.",
        ),
        "http_header_invalid" to t(
            "A header is not valid: check its name and value (not empty, no line breaks, no duplicates; Host, Content-Length, Transfer-Encoding and Connection are not allowed).",
            "Un'intestazione non è valida: controlla nome e valore (non vuoto, senza a capo, senza duplicati; Host, Content-Length, Transfer-Encoding e Connection non sono ammessi).",
        ),
        "http_content_type_invalid" to t(
            "The content type is not valid or differs from the Content-Type header.",
            "Il content type non è valido o è diverso dall'intestazione Content-Type.",
        ),
        "http_body_not_allowed" to t(
            "A GET request cannot have a body.",
            "Una richiesta GET non può avere un body.",
        ),
        "http_body_too_large" to t(
            "The body is larger than 16 KB.",
            "Il body supera i 16 KB.",
        ),
        "http_body_secret_disclosure" to t(
            "A secret variable will be sent to the address of the HTTP request.",
            "Una variabile segreta verrà inviata all'indirizzo della richiesta HTTP.",
        ),
        "interpolation_json_context" to t(
            "External data sits in the JSON body outside a quoted string: the action would be blocked when it runs.",
            "Un dato esterno è nel body JSON fuori da una stringa tra virgolette: l'azione verrebbe bloccata all'esecuzione.",
        ),

        // --- 0.5.1 · azioni privilegiate tipizzate ----------------------------------------------
        "priv_pkg_invalid" to t(
            "The app package is missing, malformed or a \${variable}: it must be written out in full, like com.example.app.",
            "Il package dell'app manca, non è valido o è una \${variabile}: va scritto per intero, come com.example.app.",
        ),
        "priv_target_forbidden" to t(
            "A rule cannot change Argus, the launcher or an essential system app (system UI, phone, Settings, Google services).",
            "Una regola non può modificare Argus, il launcher o un'app di sistema essenziale (interfaccia di sistema, telefono, Impostazioni, servizi Google).",
        ),
        "priv_permission_invalid" to t(
            "Only runtime permissions of the allowed list can be granted or revoked (location, camera, microphone, contacts, SMS, phone, calendar, files and media, notifications, body sensors, nearby devices).",
            "Si possono concedere o revocare solo i permessi runtime dell'elenco ammesso (posizione, fotocamera, microfono, contatti, SMS, telefono, calendario, file e media, notifiche, sensori del corpo, dispositivi vicini).",
        ),
        "app_control_freeze_review" to t(
            "Freezing disables the app: it disappears from the launcher and cannot start until it is unfrozen.",
            "Congelare disattiva l'app: sparisce dal launcher e non può partire finché non viene riattivata.",
        ),
        "app_permission_revoke_review" to t(
            "Revoking a permission removes an access from the app, and Android may close it.",
            "Revocare un permesso toglie un accesso all'app, e Android può chiuderla.",
        ),
        // --- Macro sull'interfaccia (0.5.1) ----------------------------------------------------
        "ui_steps_empty" to t(
            "The screen macro has no steps.",
            "La macro sullo schermo non ha passi.",
        ),
        "ui_steps_too_many" to t(
            "A screen macro can have at most 20 steps.",
            "Una macro sullo schermo può avere al massimo 20 passi.",
        ),
        "ui_pkg_invalid" to t(
            "The app to open is not a valid package name (it is fixed: no \${variable}).",
            "L'app da aprire non è un nome di package valido (è fisso: niente \${variabile}).",
        ),
        "ui_pkg_forbidden" to t(
            "A screen macro cannot act on Argus, on Settings, on the package installer or on permission dialogs.",
            "Una macro sullo schermo non può agire su Argus, sulle Impostazioni, sull'installatore di pacchetti né sui dialoghi dei permessi.",
        ),
        "ui_step_invalid" to t(
            "A step is not valid: it needs a text, an id or a description (up to 200 characters), a wait of 0.1 to 15 s, and a match number from 0 to 50.",
            "Un passo non è valido: serve un testo, un id o una descrizione (fino a 200 caratteri), un'attesa da 0,1 a 15 s e un numero di corrispondenza da 0 a 50.",
        ),
        "ui_step_interpolated" to t(
            "Only the text of a \"type\" step can contain a \${variable}; everything else in the macro is fixed.",
            "Solo il testo di un passo \"scrivi\" può contenere una \${variabile}: il resto della macro è fisso.",
        ),
        "ui_steps_external_trigger" to t(
            "A screen macro only starts from time, location, connectivity, sensor, screen, battery, audio, mode or manual triggers, or a whitelisted one-to-one WhatsApp chat. Not from SMS, calls, media or an app opening or closing: anyone can cause those.",
            "Una macro sullo schermo parte solo da trigger a tempo, luogo, connettività, sensore, schermo, batteria, audio, modalità o avvio manuale, oppure da una chat WhatsApp 1:1 in whitelist. Non da SMS, chiamate, media né dall'apertura o chiusura di un'app, perché chiunque può provocarli.",
        ),
        "ui_steps_review" to t(
            "The macro acts on the screen like you would: it taps, types and scrolls in the app, exactly as listed.",
            "La macro agisce sullo schermo come faresti tu: tocca, scrive e scorre nell'app, esattamente come elencato.",
        ),
    )

    /** Tutti i codici con testo dedicato (usato dal test di copertura). */
    val knownCodes: Set<String> get() = table.keys

    fun isKnown(code: String): Boolean = code in table

    /** Testo del codice nella lingua; sconosciuto → generico col codice tra parentesi. */
    fun text(code: String, language: RenderLanguage): String {
        val known = table[code]
        if (known != null) return language.pick(known.en, known.it)
        val shown = sanitize(code).ifBlank { "?" }
        return language.pick(
            "Unrecognized validation problem ($shown).",
            "Problema di validazione non riconosciuto ($shown).",
        )
    }

    /** Come [text], completando con [ValidationIssue.detail] i codici che ne hanno bisogno. */
    fun text(issue: ValidationIssue, language: RenderLanguage): String {
        val base = text(issue.code, language)
        val detail = issue.detail?.let(::sanitize)?.takeIf { it.isNotBlank() }
        return if (issue.code in DETAIL_CODES && detail != null) "$base ($detail)" else base
    }

    /** Elenco di codici separati da virgola (audit VALIDATION_REJECTED): un testo per codice. */
    fun textList(codes: String, language: RenderLanguage): String =
        codes.split(',').map(String::trim).filter(String::isNotEmpty)
            .joinToString(" ") { text(it, language) }

    /** Solo caratteri innocui in un testo derivato da dati strutturati; il resto diventa «_». */
    private fun sanitize(raw: String): String =
        raw.take(64).map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
}
