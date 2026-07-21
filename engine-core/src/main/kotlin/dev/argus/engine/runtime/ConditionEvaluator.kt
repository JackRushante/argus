package dev.argus.engine.runtime
import dev.argus.engine.model.*
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.*

class ConditionEvaluator(private val clock: Clock) {
    enum class Result { MET, NOT_MET, STATE_UNAVAILABLE }

    /**
     * Condizione null = nessun vincolo = vero. Uno stato assente è UNKNOWN, non false: in
     * particolare `NOT UNKNOWN` resta UNKNOWN e al confine finale fallisce chiuso. Senza questa
     * semantica, revocare Shizuku/permessi trasformerebbe `not(wifi == on)` in un match.
     */
    fun eval(c: Condition?, state: DeviceState): Boolean = evaluate(c, state, null) == Truth.TRUE

    fun result(c: Condition?, state: DeviceState): Result = resultOf(evaluate(c, state, null))

    /** Valutazione P4 su stato live + scope variabili. Un valore assente resta UNKNOWN. */
    fun flowResult(
        c: Condition,
        state: DeviceState,
        variable: (String) -> VarValue?,
    ): Result = resultOf(evaluate(c, state, variable))

    private fun resultOf(truth: Truth): Result = when (truth) {
        Truth.TRUE -> Result.MET
        Truth.FALSE -> Result.NOT_MET
        Truth.UNKNOWN -> Result.STATE_UNAVAILABLE
    }

    private fun evaluate(
        c: Condition?,
        state: DeviceState,
        variable: ((String) -> VarValue?)?,
    ): Truth = when (c) {
        null -> Truth.TRUE
        is Condition.BooleanLiteral -> Truth.of(c.value)
        is Condition.And -> c.all.fold(Truth.TRUE) { result, child ->
            result and evaluate(child, state, variable)
        }
        is Condition.Or -> c.any.fold(Truth.FALSE) { result, child ->
            result or evaluate(child, state, variable)
        }
        is Condition.Not -> !evaluate(c.cond, state, variable)
        is Condition.AppInForeground -> state.foregroundApp
            ?.let { Truth.of(it == c.pkg) }
            ?: Truth.UNKNOWN
        is Condition.StateEquals -> compare(state.values[c.key], c.op, c.value)
        is Condition.StateCompare -> compareTyped(
            state.queryValues[c.query.canonicalId],
            c.valueType,
            c.op,
            c.expected,
        )
        is Condition.LocationIn -> state.location?.let {
            Truth.of(haversineM(it.lat, it.lng, c.lat, c.lng) <= c.radiusM)
        } ?: Truth.UNKNOWN
        is Condition.TimeWindow -> Truth.of(inWindow(c))
        // Senza resolver siamo nel gate trigger-time: le variabili non esistono e falliscono
        // chiuso. L'interprete P4 passa invece lo scope della singola esecuzione.
        is Condition.VarCompare -> variable?.let { compareVariables(c, it) } ?: Truth.UNKNOWN
    }

    private fun compareVariables(
        condition: Condition.VarCompare,
        variable: (String) -> VarValue?,
    ): Truth {
        val left = variable(condition.varName) ?: return Truth.UNKNOWN
        // Operatori UNARI di parità: nessun RHS. Si coercizza il SOLO lato sinistro a INTERO e si
        // valuta la parità; fail-closed Truth.FALSE se non coercibile a intero (stesso pattern di
        // GT/LT su TEXT: mai UNKNOWN, mai eccezione, il TAINT non è declassificato).
        if (condition.op == CmpOp.IS_EVEN || condition.op == CmpOp.IS_ODD) {
            val number = StateValueCoercion.integer(left.text) ?: return Truth.FALSE
            val even = number % 2L == 0L
            return Truth.of(if (condition.op == CmpOp.IS_EVEN) even else !even)
        }
        val right = condition.expectedVar?.let(variable) ?: condition.expected?.let { expected ->
            VarValue(
                text = expected,
                type = left.type,
                integrity = IntegrityLabel.CLEAN,
                confidentiality = ConfidentialityLabel.PUBLIC,
                provenance = setOf(ValueProvenance.LITERAL),
            )
        } ?: return Truth.UNKNOWN
        if (left.type != right.type) return Truth.UNKNOWN
        return when (left.type) {
            VarType.TEXT -> when (condition.op) {
                CmpOp.EQ -> Truth.of(left.text == right.text)
                CmpOp.NEQ -> Truth.of(left.text != right.text)
                CmpOp.CONTAINS -> Truth.of(left.text.contains(right.text))
                // device-found: GT/LT su TEXT (es. un numero chiesto all'LLM e catturato come TEXT).
                // Si prova a coercizzare numericamente ENTRAMBI i lati: se uno solo non è numerico il
                // confronto è FALSE (fail-closed), mai UNKNOWN (che fermerebbe il programma) e mai una
                // eccezione. La coercizione tocca solo il boolean del confronto, non declassifica il TAINT.
                CmpOp.GT, CmpOp.LT -> {
                    val leftNumber = StateValueCoercion.number(left.text)
                    val rightNumber = StateValueCoercion.number(right.text)
                    if (leftNumber == null || rightNumber == null) {
                        Truth.FALSE
                    } else when (condition.op) {
                        CmpOp.GT -> Truth.of(leftNumber > rightNumber)
                        else -> Truth.of(leftNumber < rightNumber)
                    }
                }
                // Parità: unaria, già gestita e ritornata sopra. Irraggiungibile qui.
                CmpOp.IS_EVEN, CmpOp.IS_ODD -> Truth.UNKNOWN
            }
            VarType.NUMBER -> {
                val leftNumber = StateValueCoercion.number(left.text) ?: return Truth.UNKNOWN
                val rightNumber = StateValueCoercion.number(right.text) ?: return Truth.UNKNOWN
                when (condition.op) {
                    CmpOp.EQ -> Truth.of(leftNumber == rightNumber)
                    CmpOp.NEQ -> Truth.of(leftNumber != rightNumber)
                    CmpOp.GT -> Truth.of(leftNumber > rightNumber)
                    CmpOp.LT -> Truth.of(leftNumber < rightNumber)
                    // CONTAINS non applicabile; parità unaria già gestita sopra (irraggiungibile).
                    CmpOp.CONTAINS, CmpOp.IS_EVEN, CmpOp.IS_ODD -> Truth.UNKNOWN
                }
            }
            VarType.BOOLEAN -> {
                val leftBoolean = StateValueCoercion.boolean(left.text) ?: return Truth.UNKNOWN
                val rightBoolean = StateValueCoercion.boolean(right.text) ?: return Truth.UNKNOWN
                when (condition.op) {
                    CmpOp.EQ -> Truth.of(leftBoolean == rightBoolean)
                    CmpOp.NEQ -> Truth.of(leftBoolean != rightBoolean)
                    CmpOp.GT, CmpOp.LT, CmpOp.CONTAINS, CmpOp.IS_EVEN, CmpOp.IS_ODD -> Truth.UNKNOWN
                }
            }
        }
    }

    private fun compare(actual: String?, op: CmpOp, expected: String): Truth {
        if (actual == null) return Truth.UNKNOWN
        val an = actual.toDoubleOrNull(); val en = expected.toDoubleOrNull()
        return when (op) {
            CmpOp.EQ -> Truth.of(actual == expected)
            CmpOp.NEQ -> Truth.of(actual != expected)
            CmpOp.CONTAINS -> Truth.of(actual.contains(expected))
            CmpOp.GT -> Truth.of(if (an != null && en != null) an > en else actual > expected)
            CmpOp.LT -> Truth.of(if (an != null && en != null) an < en else actual < expected)
            // Parità non applicabile a state_equals (il validator la esclude): irraggiungibile qui.
            CmpOp.IS_EVEN, CmpOp.IS_ODD -> Truth.UNKNOWN
        }
    }

    private fun inWindow(c: Condition.TimeWindow): Boolean {
        val now = LocalTime.now(clock.withZone(ZoneId.of(c.tz)))
        val start = LocalTime.parse(c.startLocal); val end = LocalTime.parse(c.endLocal)
        return if (start <= end) now >= start && now < end          // stessa giornata
        else now >= start || now < end                               // attraversa mezzanotte
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun compareTyped(
        actual: String?,
        type: StateValueType,
        op: CmpOp,
        expected: String,
    ): Truth {
        if (actual == null) return Truth.UNKNOWN
        return when (type) {
            StateValueType.TEXT -> when (op) {
                CmpOp.EQ -> Truth.of(actual == expected)
                CmpOp.NEQ -> Truth.of(actual != expected)
                CmpOp.CONTAINS -> Truth.of(actual.contains(expected))
                // Parità esclusa da state_compare dal validator: non applicabile qui.
                CmpOp.GT, CmpOp.LT, CmpOp.IS_EVEN, CmpOp.IS_ODD -> Truth.UNKNOWN
            }
            StateValueType.NUMBER -> {
                val left = StateValueCoercion.number(actual) ?: return Truth.UNKNOWN
                val right = StateValueCoercion.number(expected) ?: return Truth.UNKNOWN
                when (op) {
                    CmpOp.EQ -> Truth.of(left == right)
                    CmpOp.NEQ -> Truth.of(left != right)
                    CmpOp.GT -> Truth.of(left > right)
                    CmpOp.LT -> Truth.of(left < right)
                    CmpOp.CONTAINS, CmpOp.IS_EVEN, CmpOp.IS_ODD -> Truth.UNKNOWN
                }
            }
            StateValueType.BOOLEAN -> {
                val left = StateValueCoercion.boolean(actual) ?: return Truth.UNKNOWN
                val right = expected.toBooleanStrictOrNull() ?: return Truth.UNKNOWN
                when (op) {
                    CmpOp.EQ -> Truth.of(left == right)
                    CmpOp.NEQ -> Truth.of(left != right)
                    CmpOp.GT, CmpOp.LT, CmpOp.CONTAINS, CmpOp.IS_EVEN, CmpOp.IS_ODD -> Truth.UNKNOWN
                }
            }
        }
    }

    private enum class Truth {
        TRUE,
        FALSE,
        UNKNOWN;

        infix fun and(other: Truth): Truth = when {
            this == FALSE || other == FALSE -> FALSE
            this == UNKNOWN || other == UNKNOWN -> UNKNOWN
            else -> TRUE
        }

        infix fun or(other: Truth): Truth = when {
            this == TRUE || other == TRUE -> TRUE
            this == UNKNOWN || other == UNKNOWN -> UNKNOWN
            else -> FALSE
        }

        operator fun not(): Truth = when (this) {
            TRUE -> FALSE
            FALSE -> TRUE
            UNKNOWN -> UNKNOWN
        }

        companion object {
            fun of(value: Boolean): Truth = if (value) TRUE else FALSE
        }
    }
}
