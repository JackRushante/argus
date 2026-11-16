package dev.argus.engine.model

/**
 * Regole chiuse di `send_sms`, `call_number`, `send_intent` e `screen_call` (0.8). Le usano il validator
 * (statico), l'interpolatore (forma risolta) e l'esecutore Android (ultima difesa al momento dello scatto).
 *
 * Minaccia principale: un contenuto che arriva da fuori (SMS, notifica, chi chiama) che decide cosa fa il
 * telefono. Per questo:
 *  - un numero è solo `+` facoltativo e 7..15 cifre: niente numeri brevi, `*`, `#`, pause (codici USSD e
 *    di deviazione di chiamata esclusi);
 *  - il destinatario di un SMS può venire da fuori solo come UN segnaposto legato al numero di chi ha
 *    chiamato o scritto (trigger `phone_state`), ed è ricontrollato dopo la sostituzione;
 *  - un Intent è tutto letterale (mostrato per intero in approvazione) tranne il valore degli extra
 *    STRING; non può puntare ad Argus, chiamare, installare, disinstallare, cancellare o resettare.
 */
object TelephonyActionPolicy {
    /** Testo di un SMS dopo la sostituzione dei segnaposto: tre SMS concatenati al massimo. */
    const val MAX_SMS_CHARS = 480

    /** Tetto orario di tutta l'app agli SMS inviati dalle regole. */
    const val SMS_PER_HOUR = 10

    /** Tetto orario agli SMS verso lo stesso numero. */
    const val SMS_PER_DESTINATION_PER_HOUR = 3

    /** Tetto orario alle chiamate avviate dalle regole. */
    const val CALLS_PER_HOUR = 3

    const val MAX_INTENT_EXTRAS = 16
    const val MAX_INTENT_NAME_CHARS = 255
    const val MAX_INTENT_DATA_CHARS = 2_048
    const val MAX_INTENT_EXTRA_VALUE_CHARS = 4_000

    /** Azioni che chiamano, installano, disinstallano, cancellano o resettano: mai da una regola. */
    val FORBIDDEN_INTENT_ACTIONS: Set<String> = setOf(
        "android.intent.action.CALL",
        "android.intent.action.CALL_PRIVILEGED",
        "android.intent.action.CALL_EMERGENCY",
        "android.intent.action.INSTALL_PACKAGE",
        "android.intent.action.UNINSTALL_PACKAGE",
        "android.intent.action.DELETE",
        "android.intent.action.FACTORY_RESET",
        "android.intent.action.MASTER_CLEAR",
    )

    /** Schemi di `data` che aprono file locali, eseguono script o incapsulano un altro Intent. */
    val FORBIDDEN_DATA_SCHEMES: Set<String> = setOf("file", "javascript", "intent")

    // Codici dei problemi di un Intent: sono anche i codici del validator (testi in ValidationMessages).
    const val INTENT_TARGET_MISSING = "intent_target_missing"
    const val INTENT_NAME_INVALID = "intent_name_invalid"
    const val INTENT_TARGET_FORBIDDEN = "intent_target_forbidden"
    const val INTENT_ACTION_FORBIDDEN = "intent_action_forbidden"
    const val INTENT_DATA_INVALID = "intent_data_invalid"
    const val INTENT_DATA_SCHEME_FORBIDDEN = "intent_data_scheme_forbidden"
    const val INTENT_TOO_MANY_EXTRAS = "intent_too_many_extras"
    const val INTENT_EXTRA_INVALID = "intent_extra_invalid"
    const val INTENT_SERVICE_NEEDS_COMPONENT = "intent_service_needs_component"

    private val NUMBER = Regex("^\\+?[0-9]{7,15}$")
    private val PLACEHOLDER = Regex("^\\$\\{([a-z][a-z0-9_]{0,31})\\}$")
    private val INTENT_ACTION = Regex("^[A-Za-z0-9_$-]+(?:\\.[A-Za-z0-9_$-]+)*$")
    private val CLASS_NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
    private val MIME_TYPE = Regex("^[A-Za-z0-9*][A-Za-z0-9!#$&^_.+*-]*/[A-Za-z0-9*][A-Za-z0-9!#$&^_.+*-]*$")
    private val EXTRA_NAME = Regex("^[A-Za-z0-9_.:-]+$")
    private val URI_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):.+", RegexOption.DOT_MATCHES_ALL)

    /** Numero di telefono ammesso: `+` facoltativo all'inizio e da 7 a 15 cifre, nient'altro. */
    fun isValidNumber(number: String): Boolean = NUMBER.matches(number)

    /** Il nome della variabile se [template] è ESATTAMENTE un segnaposto `${'$'}{nome}`, altrimenti null. */
    fun placeholder(template: String): String? = PLACEHOLDER.matchEntire(template)?.groupValues?.get(1)

    /**
     * Caratteri di controllo (Cc) o invisibili (Cf, Zl, Zp): in un nome o valore di un Intent farebbero
     * vedere in approvazione qualcosa di diverso da ciò che parte.
     */
    fun hasControlCharacters(text: String): Boolean = text.codePoints().anyMatch { code ->
        when (Character.getType(code).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> false
        }
    }

    /** Argus stesso (varianti comprese): pacchetto, classe o azione nel suo spazio di nomi. */
    fun isArgusName(name: String): Boolean = PrivilegedActionPolicy.isArgus(name)

    /**
     * Problemi di un `send_intent`, nell'ordine dei campi, senza doppioni (vuoto = valido). Con
     * [resolved] = false si controlla il template approvato (i valori STRING possono contenere segnaposto);
     * con true la forma dopo la sostituzione, al momento dello scatto.
     */
    fun intentProblems(intent: Action.SendIntent, resolved: Boolean = false): List<String> {
        val problems = linkedSetOf<String>()
        val action = intent.action
        val pkg = intent.pkg
        val cls = intent.cls
        if (action == null && (pkg == null || cls == null)) problems += INTENT_TARGET_MISSING
        if (cls != null && pkg == null) problems += INTENT_TARGET_MISSING
        if (intent.kind == IntentKind.SERVICE && (pkg == null || cls == null)) problems += INTENT_SERVICE_NEEDS_COMPONENT
        action?.let {
            if (!validName(it, INTENT_ACTION)) problems += INTENT_NAME_INVALID
            if (FORBIDDEN_INTENT_ACTIONS.any { forbidden -> forbidden.equals(it, ignoreCase = true) }) {
                problems += INTENT_ACTION_FORBIDDEN
            }
            if (isArgusName(it)) problems += INTENT_TARGET_FORBIDDEN
        }
        pkg?.let {
            if (!PrivilegedActionPolicy.isValidPackage(it) || hasControlCharacters(it)) problems += INTENT_NAME_INVALID
            if (isArgusName(it)) problems += INTENT_TARGET_FORBIDDEN
        }
        cls?.let {
            if (!validName(it, CLASS_NAME)) problems += INTENT_NAME_INVALID
            if (isArgusName(it)) problems += INTENT_TARGET_FORBIDDEN
        }
        intent.mimeType?.let { if (!validName(it, MIME_TYPE)) problems += INTENT_NAME_INVALID }
        intent.data?.let { data ->
            val scheme = URI_SCHEME.matchEntire(data)?.groupValues?.get(1)?.lowercase()
            when {
                data.isBlank() || data.length > MAX_INTENT_DATA_CHARS || hasControlCharacters(data) ||
                    InterpolationPolicy.containsInterpolation(data) || scheme == null -> problems += INTENT_DATA_INVALID
                scheme in FORBIDDEN_DATA_SCHEMES -> problems += INTENT_DATA_SCHEME_FORBIDDEN
            }
        }
        if (intent.extras.size > MAX_INTENT_EXTRAS) problems += INTENT_TOO_MANY_EXTRAS
        val names = intent.extras.map { it.name }
        if (names.size != names.toSet().size) problems += INTENT_EXTRA_INVALID
        intent.extras.forEach { extra ->
            if (!validName(extra.name, EXTRA_NAME) || !validExtraValue(extra, resolved)) problems += INTENT_EXTRA_INVALID
        }
        return problems.toList()
    }

    private fun validName(value: String, shape: Regex): Boolean =
        value.length in 1..MAX_INTENT_NAME_CHARS && !hasControlCharacters(value) && shape.matches(value)

    private fun validExtraValue(extra: IntentExtra, resolved: Boolean): Boolean {
        val value = extra.value
        if (hasControlCharacters(value)) return false
        return when (extra.type) {
            // Un valore vuoto è anche quello lasciato «da completare» dall'export senza dati personali.
            IntentExtraType.STRING -> value.isNotEmpty() && value.length <= MAX_INTENT_EXTRA_VALUE_CHARS &&
                (resolved || !InterpolationPolicy.parse(value).malformed)
            IntentExtraType.INT -> value.toIntOrNull() != null
            IntentExtraType.LONG -> value.toLongOrNull() != null
            IntentExtraType.FLOAT -> value.toFloatOrNull()?.isFinite() == true
            IntentExtraType.BOOLEAN -> value == "true" || value == "false"
        }
    }

    /**
     * Condizioni ammesse in una regola con `screen_call`: la decisione va data prima che il telefono squilli,
     * quindi solo ciò che si calcola con l'orologio (fascia oraria, giorno della settimana) e i letterali,
     * combinati con and/or/not. Nessun lettore di stato, posizione, app in primo piano o variabile.
     */
    fun isScreeningCondition(condition: Condition): Boolean {
        val pending = ArrayDeque<Condition>()
        pending.addLast(condition)
        var visited = 0
        while (pending.isNotEmpty()) {
            if (visited++ > MAX_SCREENING_CONDITION_NODES) return false
            when (val c = pending.removeFirst()) {
                is Condition.TimeWindow, is Condition.Weekday, is Condition.BooleanLiteral -> Unit
                is Condition.And -> c.all.forEach(pending::addLast)
                is Condition.Or -> c.any.forEach(pending::addLast)
                is Condition.Not -> pending.addLast(c.cond)
                else -> return false
            }
        }
        return true
    }

    private const val MAX_SCREENING_CONDITION_NODES = 128
}
