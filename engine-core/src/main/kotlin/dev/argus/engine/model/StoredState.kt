package dev.argus.engine.model

import java.math.BigDecimal
import java.util.Locale

/**
 * 0.8: stato che sopravvive fra le esecuzioni. Due famiglie, entrambe scritte SOLO da azioni approvate
 * (`set_stored`, `set_user_mode`) o dall'utente nell'app:
 *  - i valori conservati: chiave → valore tipizzato con le etichette che aveva quando è stato scritto;
 *  - le modalità: un elenco definito dall'utente e al più una attiva.
 *
 * Nessuna delle due tocca le automazioni (invariante 7): una regola può cambiare un valore o la modalità
 * solo se lo dice la regola approvata, e un valore esterno resta TAINTED ovunque venga riletto.
 */

/** Chi ha fatto l'ultimo cambio: l'utente (app, riquadro, widget) o una regola. */
sealed interface ChangeAuthor {
    data object User : ChangeAuthor

    data class Rule(val id: AutomationId) : ChangeAuthor
}

/**
 * Un valore conservato. [text] è sempre nella forma canonica del suo [type] (numero senza zeri inutili,
 * `true`/`false`). Le etichette sono quelle del valore quando è stato scritto: un dato esterno resta
 * TAINTED anche letto da un'altra regola giorni dopo.
 */
data class StoredValue(
    val key: String,
    val type: StateValueType,
    val text: String,
    val integrity: IntegrityLabel,
    val confidentiality: ConfidentialityLabel,
    val writer: ChangeAuthor,
    val updatedAtMillis: Long,
) {
    override fun toString(): String =
        "StoredValue(key=$key, type=$type, text=<redacted>, integrity=$integrity, " +
            "confidentiality=$confidentiality, writer=$writer, updatedAtMillis=$updatedAtMillis)"
}

object StoredValuePolicy {
    /** Stessa forma dei nomi di variabile: `[a-z][a-z0-9_]{0,31}`. */
    val KEY_REGEX: Regex = VarBinding.NAME_REGEX
    const val MAX_VALUE_CHARS = 1_024
    const val MAX_KEYS = 64

    /** Incremento letterale: un intero con segno, al massimo nove cifre. */
    val INCREMENT_LITERAL = Regex("^-?[0-9]{1,9}$")
    const val DEFAULT_INCREMENT = 1L

    /** Numero canonico ammesso: niente esponenti, al massimo 30 cifre intere e 20 decimali. */
    private val NUMBER = Regex("^-?[0-9]{1,30}(?:\\.[0-9]{1,20})?$")
    private const val MAX_INTEGER_DIGITS = 30

    fun validKey(key: String): Boolean = KEY_REGEX.matches(key)

    /** Forma canonica di [raw] per [type]; null se [raw] non è di quel tipo (mai un valore inventato). */
    fun canonical(raw: String, type: StateValueType): String? = when (type) {
        StateValueType.TEXT -> raw.takeIf { it.length <= MAX_VALUE_CHARS && '\u0000' !in it }
        StateValueType.NUMBER -> raw.trim().takeIf(NUMBER::matches)?.let { canonicalNumber(BigDecimal(it)) }
        StateValueType.BOOLEAN -> when (raw.trim().lowercase(Locale.ROOT)) {
            "true" -> "true"
            "false" -> "false"
            else -> null
        }
    }

    fun canonicalNumber(value: BigDecimal): String? {
        val plain = value.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
        val integerDigits = plain.removePrefix("-").substringBefore('.').length
        return plain.takeIf { integerDigits <= MAX_INTEGER_DIGITS && it.length <= MAX_VALUE_CHARS }
    }
}

/** Esito calcolato di un `set_stored` sul valore corrente: lo store lo applica nella stessa transazione. */
sealed interface StoredWrite {
    data class Put(val value: StoredValue) : StoredWrite

    data class Remove(val key: String) : StoredWrite

    /** CLEAR di una chiave che non c'è: niente da fare, ed è un successo. */
    data object Unchanged : StoredWrite

    /** Nessuna scrittura; [code] è il codice chiuso del fallimento dell'azione. */
    data class Rejected(val code: String) : StoredWrite
}

object StoredValueOps {
    const val TYPE_MISMATCH = "stored_type_mismatch"
    const val VALUE_INVALID = "stored_value_invalid"
    const val KEY_LIMIT = "stored_key_limit"
    const val KEY_INVALID = "stored_key_invalid"

    /**
     * Calcola la scrittura di [action] (già risolta: `value` è il template riempito) su [current]. [keyCount]
     * è il numero di chiavi salvate adesso. SET scrive il testo con le etichette [integrity]/[confidentiality]
     * di ciò che è entrato nel template; INCREMENT e TOGGLE partono da 0 e da false e tengono le etichette
     * del valore salvato; un tipo diverso fallisce con [TYPE_MISMATCH] senza cambiare niente.
     */
    fun plan(
        action: Action.SetStored,
        current: StoredValue?,
        keyCount: Int,
        integrity: IntegrityLabel,
        confidentiality: ConfidentialityLabel,
        author: ChangeAuthor,
        atMillis: Long,
    ): StoredWrite {
        if (!StoredValuePolicy.validKey(action.key)) return StoredWrite.Rejected(KEY_INVALID)
        if (action.op == StoredOp.CLEAR) {
            return if (current == null) StoredWrite.Unchanged else StoredWrite.Remove(action.key)
        }
        if (current == null && keyCount >= StoredValuePolicy.MAX_KEYS) return StoredWrite.Rejected(KEY_LIMIT)

        fun put(type: StateValueType, text: String, integrity: IntegrityLabel, confidentiality: ConfidentialityLabel) =
            StoredWrite.Put(StoredValue(action.key, type, text, integrity, confidentiality, author, atMillis))

        return when (action.op) {
            StoredOp.SET -> {
                val raw = action.value ?: return StoredWrite.Rejected(VALUE_INVALID)
                if (raw.length > StoredValuePolicy.MAX_VALUE_CHARS) return StoredWrite.Rejected(VALUE_INVALID)
                val type = action.valueType ?: StateValueType.TEXT
                val text = StoredValuePolicy.canonical(raw, type)
                    ?: return StoredWrite.Rejected(if (type == StateValueType.TEXT) VALUE_INVALID else TYPE_MISMATCH)
                put(type, text, integrity, confidentiality)
            }
            StoredOp.INCREMENT -> {
                val step = action.value?.let { literal ->
                    literal.takeIf(StoredValuePolicy.INCREMENT_LITERAL::matches)?.toLong()
                        ?: return StoredWrite.Rejected(VALUE_INVALID)
                } ?: StoredValuePolicy.DEFAULT_INCREMENT
                val base = when {
                    current == null -> BigDecimal.ZERO
                    current.type != StateValueType.NUMBER -> return StoredWrite.Rejected(TYPE_MISMATCH)
                    else -> current.text.toBigDecimalOrNull() ?: return StoredWrite.Rejected(TYPE_MISMATCH)
                }
                val text = StoredValuePolicy.canonicalNumber(base + BigDecimal.valueOf(step))
                    ?: return StoredWrite.Rejected(VALUE_INVALID)
                put(StateValueType.NUMBER, text, joined(current?.integrity, integrity), joined(current?.confidentiality, confidentiality))
            }
            StoredOp.TOGGLE -> {
                if (action.value != null) return StoredWrite.Rejected(VALUE_INVALID)
                val before = when {
                    current == null -> false
                    current.type != StateValueType.BOOLEAN -> return StoredWrite.Rejected(TYPE_MISMATCH)
                    else -> current.text.toBooleanStrictOrNull() ?: return StoredWrite.Rejected(TYPE_MISMATCH)
                }
                put(StateValueType.BOOLEAN, (!before).toString(), joined(current?.integrity, integrity), joined(current?.confidentiality, confidentiality))
            }
            StoredOp.CLEAR -> StoredWrite.Unchanged // gestito sopra
        }
    }

    private fun joined(saved: IntegrityLabel?, input: IntegrityLabel) =
        if (saved == null) input else joinIntegrity(saved, input)

    private fun joined(saved: ConfidentialityLabel?, input: ConfidentialityLabel) =
        if (saved == null) input else joinConfidentiality(saved, input)
}

/** Le modalità dell'utente e quella attiva; [active] null = nessuna. */
data class UserModeSnapshot(
    val modes: List<String>,
    val active: String?,
    val changedBy: ChangeAuthor? = null,
    val changedAtMillis: Long? = null,
) {
    /** Il nome com'è scritto nell'elenco, cercato senza maiuscole. */
    fun find(name: String): String? = modes.firstOrNull { UserModePolicy.same(it, name) }
}

/** Esito del cambio di modalità. */
sealed interface ModeSwitch {
    /** Cambio reale; [to] null = nessuna modalità. */
    data class Changed(val from: String?, val to: String?) : ModeSwitch

    data object Unchanged : ModeSwitch

    data object UnknownMode : ModeSwitch
}

enum class UserModeListIssue { NAME_INVALID, DUPLICATE, TOO_MANY, UNKNOWN }

object UserModePolicy {
    const val MAX_NAME_CHARS = 24
    const val MAX_MODES = 12

    /** Nome pulito (spazi ai bordi tolti) se valido: 1..24 caratteri visibili, nessun segnaposto. */
    fun normalize(name: String): String? = name.trim().takeIf { trimmed ->
        trimmed.length in 1..MAX_NAME_CHARS &&
            trimmed.none(Char::isISOControl) &&
            !InterpolationPolicy.containsInterpolation(trimmed) &&
            "\${" !in trimmed
    }

    /** Un nome scritto in una regola: valido e già senza spazi ai bordi. */
    fun validLiteral(name: String): Boolean = normalize(name) == name

    fun same(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

    /** Chiave unica (senza maiuscole) con cui l'elenco è salvato. */
    fun key(name: String): String = name.lowercase(Locale.ROOT)

    fun checkAdd(existing: List<String>, name: String): UserModeListIssue? {
        val clean = normalize(name) ?: return UserModeListIssue.NAME_INVALID
        if (existing.any { same(it, clean) }) return UserModeListIssue.DUPLICATE
        if (existing.size >= MAX_MODES) return UserModeListIssue.TOO_MANY
        return null
    }

    fun checkRename(existing: List<String>, from: String, to: String): UserModeListIssue? {
        if (existing.none { same(it, from) }) return UserModeListIssue.UNKNOWN
        val clean = normalize(to) ?: return UserModeListIssue.NAME_INVALID
        if (existing.any { same(it, clean) && !same(it, from) }) return UserModeListIssue.DUPLICATE
        return null
    }

    /** Il riquadro scorre le modalità in ordine; dopo l'ultima (o da nessuna) torna alla prima. */
    fun next(modes: List<String>, active: String?): String? {
        if (modes.isEmpty()) return null
        val index = active?.let { name -> modes.indexOfFirst { same(it, name) } } ?: -1
        return modes[(index + 1) % modes.size]
    }

    /** Ogni nome di modalità che la regola nomina (trigger, condizioni, azioni, uscita), in ordine. */
    fun modesUsedBy(draft: AutomationDraft): List<String> = buildList {
        (draft.trigger as? Trigger.UserModeChanged)?.to?.let(::add)
        (draft.exit?.trigger as? Trigger.UserModeChanged)?.to?.let(::add)
        visit(draft, conditions = { addAll(it) }, actions = { add(it) })
    }.distinct()

    /** I nomi usati come condizione (allo scatto o dentro if/while): il conteggio della schermata Modalità. */
    fun modesInConditions(draft: AutomationDraft): List<String> = buildList {
        visit(draft, conditions = { addAll(it) }, actions = { })
    }.distinct()

    fun uses(draft: AutomationDraft, mode: String): Boolean = modesUsedBy(draft).any { same(it, mode) }

    fun usesAsCondition(draft: AutomationDraft, mode: String): Boolean =
        modesInConditions(draft).any { same(it, mode) }

    private fun visit(
        draft: AutomationDraft,
        conditions: (List<String>) -> Unit,
        actions: (String) -> Unit,
    ) {
        draft.conditions?.let { conditions(conditionModes(it)) }
        val pending = ArrayDeque<Action>()
        (draft.actions + draft.exit?.actions.orEmpty()).forEach(pending::addLast)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VISITED_NODES) {
            when (val action = pending.removeFirst()) {
                is Action.SetUserMode -> actions(action.mode)
                is Action.If -> {
                    conditions(conditionModes(action.condition))
                    action.then.forEach(pending::addLast)
                    action.orElse.forEach(pending::addLast)
                }
                is Action.While -> {
                    conditions(conditionModes(action.condition))
                    action.body.forEach(pending::addLast)
                }
                else -> Unit
            }
        }
    }

    private fun conditionModes(root: Condition): List<String> {
        val found = mutableListOf<String>()
        val pending = ArrayDeque<Condition>()
        pending.addLast(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VISITED_NODES) {
            when (val condition = pending.removeFirst()) {
                is Condition.UserModeIs -> found += condition.mode
                is Condition.And -> condition.all.forEach(pending::addLast)
                is Condition.Or -> condition.any.forEach(pending::addLast)
                is Condition.Not -> pending.addLast(condition.cond)
                else -> Unit
            }
        }
        return found
    }

    private const val MAX_VISITED_NODES = 4_096
}
