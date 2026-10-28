package dev.argus.engine.model

/**
 * Politica di interpolazione `${var}` nei campi TESTO delle azioni (P4 §2.4). Vocabolario CHIUSO:
 * ogni campo testuale di ogni azione è classificato STATICAMENTE. Il parser è LINEARE su `${nome}`
 * (niente espressioni, niente eval). L'enforcement è DOPPIO: statico nel
 * [dev.argus.engine.safety.DraftValidator] (qui) e dinamico nel runtime taint-aware (P4-B).
 *
 * [FieldClass] conserva la distinzione tra dato e autorità. L'ammissione di valori TAINTED nei
 * campi [FieldClass.AUTHORITY] è decisa centralmente da [TaintPolicy] (abilitata nella postura
 * Aggressive corrente), non da questa tabella.
 *
 * Il template di un campo è LETTERALE e fingerprintato; i valori interpolati no. Per il solo comando
 * shell ([TemplateField.shellQuoted], `run_shell.cmd`) un valore TAINTED viene inserito già
 * quotato ([ShellQuoting]): un messaggio esterno non può più aggiungere comandi, redirezioni o
 * sostituzioni al comando approvato. I valori CLEAN restano testo grezzo, come scelto dall'utente.
 */
object InterpolationPolicy {

    /** Classe di un campo rispetto all'interpolazione. Vocabolario CHIUSO. */
    enum class FieldClass {
        /** Accetta `${var}` anche TAINTED: il valore entra come DATO delimitato (cleanUntrusted). */
        SINK,

        /** Autorità/routing: l'ammissione di TAINTED dipende da [TaintPolicy]. */
        AUTHORITY,
    }

    /**
     * Come un valore interpolato entra in un campo (0.4.1). [RAW] = testo così com'è (o già quotato
     * per la shell, v. [TemplateField.shellQuoted]); [JSON_STRING] = contenuto di una stringa JSON con
     * escape (body `http_request` con content type JSON); [FORM] = percent-encoding (body
     * `application/x-www-form-urlencoded`).
     */
    enum class ValueEncoding { RAW, JSON_STRING, FORM }

    /**
     * Un campo testuale di un'azione con il suo valore e la sua classe. [shellQuoted] è vero solo
     * per `run_shell.cmd`: il runtime inserisce i valori TAINTED con [ShellQuoting] (D1). [encoding] e
     * [remoteEgress] riguardano il body di `http_request`: l'encoding dice come si inseriscono i
     * valori, [remoteEgress] che il campo lascia il telefono verso un servizio remoto (un valore con
     * provenienza di credenziale non può uscire, [RemoteEgressPolicy]).
     */
    data class TemplateField(
        val label: String,
        val value: String,
        val cls: FieldClass,
        val shellQuoted: Boolean = false,
        val encoding: ValueEncoding = ValueEncoding.RAW,
        val remoteEgress: Boolean = false,
    )

    /** Riferimento `${nome}` valido: il nome rispetta [VarBinding.NAME_REGEX]. */
    // Escape both braces: Android's ICU regex engine rejects a bare closing brace even though
    // the host JDK accepts it, which would otherwise crash the process on first validation.
    private val VALID_REF = Regex("""\$\{([a-z][a-z0-9_]{0,31})\}""")

    /** Marcatore di apertura interpolazione: rileva ANCHE i `${...}` malformati. */
    private val OPEN_MARKER = Regex("""\$\{""")

    /** Esito del parsing lineare di un template. */
    data class ParseResult(val refs: List<String>, val malformed: Boolean)

    /** Un riferimento `${nome}` valido con la sua posizione nel template. */
    data class Reference(val name: String, val range: IntRange)

    /** Riferimenti validi nell'ordine di apparizione, con gli intervalli nel [template]. */
    fun references(template: String): List<Reference> =
        VALID_REF.findAll(template).map { Reference(it.groupValues[1], it.range) }.toList()

    /**
     * Estrae i nomi di variabile referenziati da `${nome}`. [malformed] è true se esiste un `${`
     * che non forma un riferimento valido (nome fuori regex o parentesi non chiusa): fail-closed.
     */
    fun parse(template: String): ParseResult {
        val valid = VALID_REF.findAll(template).map { it.groupValues[1] }.toList()
        val openCount = OPEN_MARKER.findAll(template).count()
        return ParseResult(refs = valid, malformed = openCount > valid.size)
    }

    /** true se il campo contiene un qualsiasi tentativo di interpolazione (`${...}`, anche malformato). */
    fun containsInterpolation(template: String): Boolean = OPEN_MARKER.containsMatchIn(template)

    /**
     * Campi TESTO di un'azione con la loro classe. Vocabolario CHIUSO: i campi non elencati come
     * [FieldClass.SINK] sono [FieldClass.AUTHORITY] (default-deny fail-closed). I campi non testuali
     * (numerici, booleani, enum, liste di tool) NON compaiono: l'interpolazione non li tocca in P4-A.
     * I contenitori control-flow (If/While) non hanno campi propri: la ricorsione sulle azioni
     * annidate è responsabilità del validator.
     */
    fun textFields(action: Action): List<TemplateField> = when (action) {
        // --- SINK: il tainted entra come dato delimitato ---
        is Action.ShowNotification -> listOf(
            TemplateField("title", action.title, FieldClass.SINK),
            TemplateField("text", action.text, FieldClass.SINK),
        )
        is Action.WhatsAppReply -> listOf(TemplateField("text", action.text, FieldClass.SINK))
        // Clipboard letterale: uscita dato locale, non un sink di autorità. Il tainted può entrare
        // come dato (l'utente usa ${'$'}{var}), come una notifica/reply.
        is Action.CopyText -> listOf(TemplateField("text", action.text, FieldClass.SINK))
        is Action.InvokeLlm -> buildList {
            add(TemplateField("goal", action.goal, FieldClass.SINK))
            action.notificationTitle?.let { add(TemplateField("notificationTitle", it, FieldClass.SINK)) }
        }
        is Action.InvokeLlmV2 -> listOf(TemplateField("goal", action.goal, FieldClass.SINK))
        // Lettura ad alta voce: uscita locale, come una notifica.
        is Action.Speak -> listOf(TemplateField("text", action.text, FieldClass.SINK))
        // Chiamata HTTP: URL e intestazioni sono letterali (mai interpolati); i dati esterni, anche
        // TAINTED, entrano SOLO nel body, che esce verso l'host approvato. Codifica per content type.
        is Action.HttpRequest -> listOfNotNull(
            action.body?.let {
                TemplateField(
                    "body",
                    it,
                    FieldClass.SINK,
                    encoding = HttpRequestPolicy.bodyEncoding(action),
                    remoteEgress = true,
                )
            },
        )

        // Macro sull'interfaccia: SOLO il testo dei passi `type` accetta segnaposto (uscita locale, come
        // una notifica); pkg e criteri dei passi restano letterali, mostrati interi in approvazione.
        is Action.UiSteps -> action.steps.mapIndexedNotNull { index, step ->
            (step as? UiStep.Type)?.let { TemplateField("steps.$index.text", it.text, FieldClass.SINK) }
        }

        // --- AUTHORITY: template fingerprintati; TAINTED dipende da TaintPolicy ---
        is Action.RunShell -> listOf(
            TemplateField("cmd", action.cmd, FieldClass.AUTHORITY, shellQuoted = true),
        )
        is Action.OpenUrl -> listOf(TemplateField("url", action.url, FieldClass.AUTHORITY))
        is Action.LaunchApp -> listOf(TemplateField("pkg", action.pkg, FieldClass.AUTHORITY))
        is Action.InputText -> listOf(TemplateField("text", action.text, FieldClass.AUTHORITY))
        is Action.SetRinger -> listOf(TemplateField("mode", action.mode, FieldClass.AUTHORITY))
        is Action.WriteSetting -> listOf(
            TemplateField("key", action.key, FieldClass.AUTHORITY),
            TemplateField("value", action.value, FieldClass.AUTHORITY),
        )
        is Action.SetAlarm -> buildList {
            action.label?.let { add(TemplateField("label", it, FieldClass.SINK)) }
        }
        is Action.SetTimer -> buildList {
            action.label?.let { add(TemplateField("label", it, FieldClass.SINK)) }
        }
        is Action.OpenSettingsScreen -> buildList {
            action.pkg?.let { add(TemplateField("pkg", it, FieldClass.AUTHORITY)) }
        }
        is Action.CopyToClipboard -> buildList {
            action.extractionRegex?.let { add(TemplateField("extractionRegex", it, FieldClass.AUTHORITY)) }
        }

        // --- Nessun campo testuale interpolabile ---
        is Action.SetWifi,
        is Action.SetBluetooth,
        is Action.SetMobileData,
        is Action.SetDnd,
        is Action.Tap,
        is Action.SetVolume,
        is Action.SetFlashlight,
        is Action.SetDarkMode,
        is Action.Vibrate,
        is Action.Wait,
        is Action.MediaControl,
        is Action.SetBrightness,
        is Action.SetRotation,
        // 0.5.1: pkg e permission sono letterali (mai interpolati): nessun campo testuale.
        is Action.AppControl,
        is Action.SetAirplane,
        is Action.SetLocation,
        is Action.SetNfc,
        is Action.SetBatterySaver,
        is Action.LockScreen,
        is Action.Screenshot,
        is Action.AppPermission,
        is Action.If,
        is Action.While,
        -> emptyList()
    }
}
