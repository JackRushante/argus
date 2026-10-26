package dev.argus.ui.presentation

/**
 * Galleria di esempi della chat (0.4.0, F10): idee per chi non sa cosa chiedere. Toccarne una riempie
 * il campo di testo, senza inviare.
 *
 * Ogni esempio dichiara in [ChatExample.uses] i trigger, le azioni e le chiavi di stato che richiede:
 * i test li confrontano col vocabolario reale dell'engine e con la classificazione Shizuku, così un
 * esempio non può promettere qualcosa che Argus non sa fare né nascondere che serve Shizuku.
 *
 * Testi scritti come li direbbe una persona, in inglese e in italiano (stesso pattern di
 * [dev.argus.ui.model.ShizukuCapabilityCatalog]: coppie affiancate, nessuna lingua dimenticata).
 */
data class ChatExampleGroup(val title: String, val examples: List<ChatExample>)

/**
 * @param uses vocabolario richiesto, con prefisso: `trigger:<id capability>`, `action:<ActionTypeIds>`,
 *   `state:<StateKeys>` e `tool:<tool generativo>`.
 * @param requiresShizuku vero se almeno un'azione è privilegiata o l'esempio legge lo stato del
 *   dispositivo: senza Shizuku la regola non si arma.
 */
data class ChatExample(val text: String, val requiresShizuku: Boolean, val uses: Set<String>)

object ChatExamples {
    fun groups(language: RenderLanguage = RenderLanguage.system()): List<ChatExampleGroup> = listOf(
        ChatExampleGroup(
            title = language.pick("Times and routines", "Orari e abitudini"),
            examples = listOf(
                ChatExample(
                    text = language.pick(
                        "Monday to Friday at 10:30 pm, switch the ringer to vibrate.",
                        "Dal lunedì al venerdì alle 22:30 metti la suoneria su vibrazione.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:time", "action:set_ringer"),
                ),
                ChatExample(
                    text = language.pick(
                        "In one hour, show me a notification reminding me to call the dentist.",
                        "Tra un'ora mostrami una notifica che mi ricordi di chiamare il dentista.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:time", "action:show_notification"),
                ),
            ),
        ),
        ChatExampleGroup(
            title = language.pick("Places", "Luoghi"),
            examples = listOf(
                ChatExample(
                    text = language.pick(
                        "When I leave here, show me a notification to remember my keys.",
                        "Quando esco da qui, mostrami una notifica per ricordarmi le chiavi.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:geofence", "action:show_notification"),
                ),
                ChatExample(
                    text = language.pick(
                        "When I get here, turn off Do Not Disturb and raise the ringer.",
                        "Quando arrivo qui, togli il Non disturbare e alza la suoneria.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:geofence", "action:set_dnd", "action:set_ringer"),
                ),
            ),
        ),
        ChatExampleGroup(
            title = language.pick("Messages and calls", "Messaggi e chiamate"),
            examples = listOf(
                ChatExample(
                    text = language.pick(
                        "When I get an SMS containing “code”, copy its text to the clipboard.",
                        "Quando mi arriva un SMS che contiene «codice», copia il testo negli appunti.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:phone_state.sms", "action:copy_text"),
                ),
                ChatExample(
                    text = language.pick(
                        "If someone writes to me on WhatsApp and the message contains “urgent”, " +
                            "vibrate the phone for two seconds.",
                        "Se qualcuno mi scrive su WhatsApp e nel messaggio c'è «urgente», " +
                            "fai vibrare il telefono per due secondi.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:notification", "action:vibrate"),
                ),
            ),
        ),
        ChatExampleGroup(
            title = language.pick("Connections and battery", "Connessioni e batteria"),
            examples = listOf(
                ChatExample(
                    text = language.pick(
                        "When my car's Bluetooth connects, set the media volume to maximum.",
                        "Quando si collega il Bluetooth dell'auto, alza al massimo il volume dei contenuti multimediali.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:connectivity.bt", "action:set_volume"),
                ),
                ChatExample(
                    text = language.pick(
                        "When I plug in the charger, set the ringer to silent.",
                        "Quando collego il caricabatterie, metti la suoneria su silenzioso.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:connectivity.power", "action:set_ringer"),
                ),
            ),
        ),
        ChatExampleGroup(
            title = language.pick("Phone settings", "Impostazioni del telefono"),
            examples = listOf(
                ChatExample(
                    text = language.pick(
                        "Every evening at 8 pm, turn on dark mode.",
                        "Ogni sera alle 20 attiva il tema scuro.",
                    ),
                    requiresShizuku = true,
                    uses = setOf("trigger:time", "action:set_dark_mode"),
                ),
                ChatExample(
                    text = language.pick(
                        "When I unplug the charger and the battery is below 20%, " +
                            "turn off mobile data and Bluetooth.",
                        "Quando scollego il caricabatterie e la batteria è sotto il 20%, " +
                            "spegni i dati mobili e il Bluetooth.",
                    ),
                    requiresShizuku = true,
                    uses = setOf(
                        "trigger:connectivity.power",
                        "state:battery",
                        "action:set_mobile_data",
                        "action:set_bluetooth",
                    ),
                ),
            ),
        ),
        ChatExampleGroup(
            title = language.pick("AI and web", "AI e web"),
            examples = listOf(
                ChatExample(
                    text = language.pick(
                        "Every morning at 7:30, search the web for today's weather and show me " +
                            "a short summary in a notification.",
                        "Ogni mattina alle 7:30 cerca sul web che tempo farà oggi e mostrami " +
                            "un riassunto in una notifica.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:time", "action:invoke_llm", "tool:web.search"),
                ),
                ChatExample(
                    text = language.pick(
                        "In 10 minutes, search the web for the Bitcoin price and notify me.",
                        "Tra 10 minuti cerca sul web il prezzo del Bitcoin e avvisami con una notifica.",
                    ),
                    requiresShizuku = false,
                    uses = setOf("trigger:time", "action:invoke_llm", "tool:web.search"),
                ),
            ),
        ),
    )
}
