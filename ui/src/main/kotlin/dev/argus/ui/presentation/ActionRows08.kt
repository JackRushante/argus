package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.SecretRef
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.TelephonyActionPolicy
import dev.argus.ui.model.ActionRow

/**
 * Resa di approvazione delle azioni del vocabolario 0.8 (ancora spente: il validator le rifiuta con
 * `not_available_yet`, ma la review le mostra già per intero). Come ogni resa di [RuleRenderMapper] parte
 * dai TIPI, è bilingue via [RenderLanguage] e mostra ogni campo che cambia l'esecuzione. I testi letterali
 * passano da [VisibleText.singleLine]: un a capo dentro un campo non imita una riga in più.
 */
internal object ActionRows08 {

    fun ask(a: Action.Ask, l: RenderLanguage): ActionRow {
        val timeout = RuleRenderMapper.humanizeDuration(a.timeoutMs, l)
        val choices = a.choices.joinToString(", ") { quoted(it) }
        val fallback = a.defaultChoice?.let { choice ->
            l.pick(
                "With no answer within $timeout: ${quoted(choice)}",
                "Senza risposta entro $timeout: ${quoted(choice)}",
            )
        } ?: l.pick(
            "With no answer within $timeout the rule stops",
            "Senza risposta entro $timeout la regola si ferma",
        )
        return row(
            "ask",
            l.pick("Ask with a notification", "Fai una domanda con una notifica"),
            listOf(
                l.pick("Question: ", "Domanda: ") + VisibleText.singleLine(a.prompt),
                l.pick("Choices: $choices", "Scelte: $choices"),
                l.pick("Answer captured as ${a.captureAs}", "Risposta catturata come ${a.captureAs}"),
                fallback,
            ).joinToString("\n"),
        )
    }

    fun setStored(a: Action.SetStored, l: RenderLanguage): ActionRow {
        val key = quoted(a.key)
        val what = when (a.op) {
            StoredOp.SET -> l.pick("Set $key", "Imposta $key")
            StoredOp.INCREMENT -> l.pick("Increase $key", "Aumenta $key")
            StoredOp.TOGGLE -> l.pick("Toggle $key (true/false)", "Inverti $key (true/false)")
            StoredOp.CLEAR -> l.pick("Delete $key", "Cancella $key")
        }
        val value = a.value?.let { l.pick(" · value: ${quoted(it)}", " · valore: ${quoted(it)}") }
            ?: if (a.op == StoredOp.INCREMENT) l.pick(" · by 1", " · di 1") else ""
        return row("stored", l.pick("Change a stored value", "Cambia un valore conservato"), what + value)
    }

    fun setUserMode(a: Action.SetUserMode, l: RenderLanguage) = row(
        "user_mode",
        l.pick("Set the user mode", "Imposta la modalità utente"),
        quoted(a.mode),
    )

    /** Come `whatsapp_reply`: una risposta a un'altra persona porta il segno «conferma dal vivo». */
    fun notificationReply(a: Action.NotificationReply, l: RenderLanguage) = row(
        "reply",
        l.pick("Reply to the notification that started the rule", "Rispondi alla notifica che ha avviato la regola"),
        a.text,
    ).copy(requiresLiveConfirm = true)

    fun notificationAction(a: Action.NotificationAction, l: RenderLanguage): ActionRow {
        val label = when (a.op) {
            NotificationActionOp.DISMISS -> l.pick(
                "Dismiss the notification that started the rule",
                "Chiudi la notifica che ha avviato la regola",
            )
            NotificationActionOp.SNOOZE -> l.pick(
                "Snooze the notification that started the rule",
                "Posticipa la notifica che ha avviato la regola",
            )
            NotificationActionOp.CLICK -> l.pick(
                "Tap a button of the notification that started the rule",
                "Tocca un pulsante della notifica che ha avviato la regola",
            )
        }
        val details = listOfNotNull(
            a.label?.let { l.pick("button ${quoted(it)}", "pulsante ${quoted(it)}") },
            a.snoozeMinutes?.let { l.pick("for $it minutes", "per $it minuti") },
        )
        return row("notify", label, details.joinToString(" · ").ifEmpty { null })
    }

    /** Un destinatario `${'$'}{var}` è il numero di chi ha chiamato o scritto: lo si dice, col segnaposto. */
    fun sendSms(a: Action.SendSms, l: RenderLanguage): ActionRow {
        val number = VisibleText.singleLine(a.number)
        val to = if (TelephonyActionPolicy.placeholder(a.number) != null) {
            l.pick("To: whoever called or texted ($number)", "A: chi ha chiamato o scritto ($number)")
        } else {
            l.pick("To: ", "A: ") + number
        }
        return row(
            "sms",
            l.pick("Send an SMS", "Invia un SMS"),
            to + "\n" + l.pick("Text: ", "Testo: ") + VisibleText.singleLine(a.text),
        )
    }

    fun callNumber(a: Action.CallNumber, l: RenderLanguage) = row(
        "phone",
        l.pick("Call", "Chiama"),
        VisibleText.singleLine(a.number),
    )

    fun sendIntent(a: Action.SendIntent, l: RenderLanguage): ActionRow {
        val label = when (a.kind) {
            IntentKind.BROADCAST -> l.pick("Send a broadcast Intent", "Invia un Intent broadcast")
            IntentKind.ACTIVITY -> l.pick("Start an activity with an Intent", "Avvia un'activity con un Intent")
            IntentKind.SERVICE -> l.pick("Start a service with an Intent", "Avvia un servizio con un Intent")
        }
        val lines = buildList {
            a.action?.let { add(l.pick("Action: ", "Azione: ") + VisibleText.singleLine(it)) }
            a.pkg?.let { add(l.pick("Package: ", "Pacchetto: ") + VisibleText.singleLine(it)) }
            a.cls?.let { add(l.pick("Class: ", "Classe: ") + VisibleText.singleLine(it)) }
            a.data?.let { add(l.pick("Data: ", "Dati: ") + VisibleText.singleLine(it)) }
            a.mimeType?.let { add(l.pick("MIME type: ", "Tipo MIME: ") + VisibleText.singleLine(it)) }
            a.extras.forEach { extra ->
                add(
                    "Extra ${VisibleText.singleLine(extra.name)} (${extra.type.name}): " +
                        VisibleText.singleLine(extra.value),
                )
            }
        }
        return row("send_intent", label, lines.joinToString("\n").ifEmpty { null })
    }

    fun screenCall(a: Action.ScreenCall, l: RenderLanguage) = row(
        "screen_call",
        when (a.op) {
            ScreenCallOp.REJECT -> l.pick("Reject the incoming call", "Rifiuta la chiamata in arrivo")
            ScreenCallOp.SILENCE -> l.pick("Silence the incoming call", "Silenzia la chiamata in arrivo")
        },
        if (a.onlyUnknownCallers) {
            l.pick(
                "Only callers not in your contacts (a hidden number counts as unknown)",
                "Solo chi non è nei tuoi contatti (un numero nascosto conta come sconosciuto)",
            )
        } else {
            l.pick("Every caller, contacts included", "Chiunque chiami, contatti compresi")
        },
    )

    // --- Campi 0.8 di azioni esistenti ------------------------------------------------------------

    /**
     * Righe dei campi 0.8 di `http_request`: cattura della risposta e valori dalla cassaforte. Di un segreto
     * si mostrano SOLO i nomi (intestazione o parametro, nome nella cassaforte) e il prefisso letterale: il
     * valore non è nella regola.
     */
    fun httpRequestExtras(a: Action.HttpRequest, l: RenderLanguage): List<String> = buildList {
        a.secretHeaders.orEmpty().forEach { ref ->
            add("${VisibleText.singleLine(ref.name)}: ${secretValue(ref, l)}")
        }
        a.secretQuery.orEmpty().forEach { ref ->
            add(
                l.pick(
                    "Query parameter ${VisibleText.singleLine(ref.name)} = ${secretValue(ref, l)}",
                    "Parametro di query ${VisibleText.singleLine(ref.name)} = ${secretValue(ref, l)}",
                ),
            )
        }
        val extract = a.extract?.let { VisibleText.singleLine(it) }
        when {
            a.captureAs != null && extract != null -> add(
                l.pick(
                    "Response captured as ${a.captureAs} (JSON pointer $extract)",
                    "Risposta catturata come ${a.captureAs} (puntatore JSON $extract)",
                ),
            )
            a.captureAs != null -> add(
                l.pick(
                    "Response captured as ${a.captureAs} (whole body)",
                    "Risposta catturata come ${a.captureAs} (corpo intero)",
                ),
            )
            extract != null -> add(l.pick("JSON pointer $extract", "Puntatore JSON $extract"))
        }
    }

    private fun secretValue(ref: SecretRef, l: RenderLanguage): String =
        VisibleText.singleLine(ref.prefix) + l.pick(
            "[secret ${quoted(ref.secret)} from the phone's vault]",
            "[segreto ${quoted(ref.secret)} dalla cassaforte del telefono]",
        )

    /** Suffisso del dettaglio di `invoke_llm` con l'uscita vincolata; vuoto se l'uscita è testo libero. */
    fun outputSuffix(output: OutputSpec?, l: RenderLanguage): String {
        if (output == null) return ""
        val choices = output.choices.joinToString(", ") { quoted(it) }
        val shape = when (output.kind) {
            OutputKind.CHOICE -> l.pick("one of $choices", "una tra $choices")
            OutputKind.NUMBER -> l.pick("a whole number", "un numero intero") +
                (output.min?.let { l.pick(" from $it", " da $it") } ?: "") +
                (output.max?.let { l.pick(" up to $it", " fino a $it") } ?: "")
            OutputKind.BOOLEAN -> l.pick("yes or no (true/false)", "sì o no (true/false)")
        }
        // Scelte dichiarate con un tipo che non le usa: il validator lo rifiuterà, la review le mostra.
        val extraChoices = if (output.kind != OutputKind.CHOICE && output.choices.isNotEmpty()) {
            l.pick(" (choices: $choices)", " (scelte: $choices)")
        } else {
            ""
        }
        return l.pick(" · output: ", " · uscita: ") + shape + extraChoices
    }

    private fun quoted(text: String): String = "\"${VisibleText.singleLine(text)}\""

    private fun row(iconKey: String, label: String, detail: String? = null) = ActionRow(
        iconKey = iconKey,
        label = label,
        detail = detail,
        isShell = false,
        shellCommand = null,
        isGenerative = false,
        requiresLiveConfirm = false,
    )
}
