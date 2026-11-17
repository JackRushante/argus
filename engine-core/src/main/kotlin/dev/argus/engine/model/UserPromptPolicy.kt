package dev.argus.engine.model

/**
 * Limiti di `confirm` sulla regola (0.8, [FeatureGate.CONFIRM]): la stessa fonte per validator, motore,
 * approvazione e runtime Android.
 */
object ConfirmPolicy {
    const val MIN_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 60L * 60 * 1_000

    fun validTimeout(timeoutMs: Long): Boolean = timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS

    /**
     * Una regola che si consuma da sola dopo il primo scatto (immediata, ora singola, «fra N minuti») non
     * è più armata quando l'utente risponde: lì la conferma non ha senso e il validator la rifiuta.
     */
    fun supportsTrigger(trigger: Trigger): Boolean = when (trigger) {
        is Trigger.Immediate -> false
        is Trigger.Time -> !trigger.isOneShot()
        else -> true
    }
}

/**
 * Limiti dell'azione `ask` (0.8, [FeatureGate.ACTION_ASK]). Le scelte sono etichette letterali dei
 * pulsanti: il valore catturato è sempre una di loro, quindi CLEAN e PUBLIC.
 */
object AskPolicy {
    const val MIN_CHOICES = 2
    const val MAX_CHOICES = 4
    const val MAX_CHOICE_CHARS = 24

    /** Il testo della domanda dopo l'interpolazione (anche il template non può essere più lungo). */
    const val MAX_PROMPT_CHARS = 120
    const val MIN_TIMEOUT_MS = 10_000L
    const val MAX_TIMEOUT_MS = 60L * 60 * 1_000

    fun validChoice(choice: String): Boolean =
        choice.length in 1..MAX_CHOICE_CHARS && choice.isNotBlank() && choice.none(::forbiddenChar) &&
            !InterpolationPolicy.containsInterpolation(choice)

    fun validChoices(choices: List<String>): Boolean =
        choices.size in MIN_CHOICES..MAX_CHOICES && choices.all(::validChoice) &&
            choices.toSet().size == choices.size

    fun validTimeout(timeoutMs: Long): Boolean = timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS

    fun validDefault(action: Action.Ask): Boolean = action.defaultChoice == null || action.defaultChoice in action.choices

    /** Il testo della domanda già risolto, così come comparirà nella notifica. */
    fun validResolvedPrompt(prompt: String): Boolean =
        prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS && prompt.none(::forbiddenChar)

    /** Forma completa di un `ask` risolto: interpolatore e interprete la ricontrollano. */
    fun validResolved(action: Action.Ask): Boolean =
        validResolvedPrompt(action.prompt) && validChoices(action.choices) && validTimeout(action.timeoutMs) &&
            validDefault(action)

    /** Caratteri di controllo e di formato (bidi, zero-width): mai in un'etichetta o in una domanda. */
    private fun forbiddenChar(char: Char): Boolean =
        char.isISOControl() || Character.getType(char).toByte() == Character.FORMAT ||
            Character.getType(char).toByte() == Character.LINE_SEPARATOR ||
            Character.getType(char).toByte() == Character.PARAGRAPH_SEPARATOR
}

/**
 * Uscita vincolata di `invoke_llm` (0.8, [FeatureGate.LLM_OUTPUT]). La risposta del modello vale solo se,
 * dopo il trim, è esattamente una delle forme approvate; mai una coercizione del testo libero.
 */
object LlmOutputPolicy {
    const val MIN_CHOICES = 2
    const val MAX_CHOICES = 8

    /** Tetto dei limiti NUMBER: interi esatti anche come double (confronti delle condizioni). */
    const val MAX_ABS_NUMBER = 1_000_000_000_000_000L

    val CHOICE_LABEL = Regex("^[a-z0-9_-]{1,24}$")
    private val INTEGER = Regex("^(?:0|-?[1-9][0-9]{0,17})$")

    /** Codice di chiusura quando il modello, anche al secondo tentativo, non rispetta la forma. */
    const val OUTPUT_INVALID = "llm_output_invalid"

    /** Il problema della forma approvata, o null se è valida. */
    fun specProblem(spec: OutputSpec): String? = when (spec.kind) {
        OutputKind.CHOICE -> when {
            spec.min != null || spec.max != null -> "llm_output_bounds_forbidden"
            spec.choices.size !in MIN_CHOICES..MAX_CHOICES -> "llm_output_choices_invalid"
            spec.choices.any { !CHOICE_LABEL.matches(it) } -> "llm_output_choices_invalid"
            spec.choices.toSet().size != spec.choices.size -> "llm_output_choices_invalid"
            else -> null
        }
        OutputKind.NUMBER -> {
            val min = spec.min
            val max = spec.max
            when {
                spec.choices.isNotEmpty() -> "llm_output_choices_forbidden"
                min == null || max == null -> "llm_output_bounds_required"
                min > max -> "llm_output_bounds_invalid"
                min < -MAX_ABS_NUMBER || max > MAX_ABS_NUMBER -> "llm_output_bounds_invalid"
                else -> null
            }
        }
        OutputKind.BOOLEAN -> when {
            spec.choices.isNotEmpty() -> "llm_output_choices_forbidden"
            spec.min != null || spec.max != null -> "llm_output_bounds_forbidden"
            else -> null
        }
    }

    fun validSpec(spec: OutputSpec): Boolean = specProblem(spec) == null

    /** Tipo della variabile catturata. */
    fun varType(spec: OutputSpec): VarType = when (spec.kind) {
        OutputKind.CHOICE -> VarType.TEXT
        OutputKind.NUMBER -> VarType.NUMBER
        OutputKind.BOOLEAN -> VarType.BOOLEAN
    }

    /**
     * Forma canonica della risposta, o null se non è una delle risposte ammesse. CHOICE: l'etichetta
     * esatta; NUMBER: l'intero in `min..max` senza segno `+` né zeri iniziali; BOOLEAN: `yes`/`no` (o
     * `true`/`false`, la forma dell'output strutturato) diventano `true`/`false`.
     */
    fun normalize(spec: OutputSpec, raw: String): String? {
        if (!validSpec(spec)) return null
        val text = raw.trim()
        return when (spec.kind) {
            OutputKind.CHOICE -> text.takeIf { it in spec.choices }
            OutputKind.NUMBER -> {
                if (!INTEGER.matches(text)) return null
                val value = text.toLongOrNull() ?: return null
                val min = spec.min ?: return null
                val max = spec.max ?: return null
                if (value in min..max) value.toString() else null
            }
            OutputKind.BOOLEAN -> when (text) {
                "yes", "true" -> "true"
                "no", "false" -> "false"
                else -> null
            }
        }
    }

    /**
     * Istruzione aggiunta dall'app (mai dal modello) al goal approvato: dice al modello la forma della
     * risposta. Testo fisso più le etichette e i limiti approvati, niente dati runtime.
     */
    fun instruction(spec: OutputSpec): String = when (spec.kind) {
        OutputKind.CHOICE ->
            "Answer with exactly one of these labels and nothing else: ${spec.choices.joinToString(", ")}."
        OutputKind.NUMBER ->
            "Answer with exactly one integer between ${spec.min} and ${spec.max} and nothing else."
        OutputKind.BOOLEAN -> "Answer with exactly yes or no and nothing else."
    }

    /** Goal approvato più l'istruzione della forma: quello che riceve un modello senza output strutturato. */
    fun constrainedGoal(goal: String, spec: OutputSpec): String = "$goal\n\n${instruction(spec)}"

    /** Il goal con l'istruzione deve stare nel limite del goal (4000 caratteri): il validator lo controlla. */
    fun constrainedGoalFits(goal: String, spec: OutputSpec, maxGoalChars: Int): Boolean =
        constrainedGoal(goal, spec).length <= maxGoalChars
}
