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
    fun eval(c: Condition?, state: DeviceState): Boolean = evaluate(c, state) == Truth.TRUE

    fun result(c: Condition?, state: DeviceState): Result = when (evaluate(c, state)) {
        Truth.TRUE -> Result.MET
        Truth.FALSE -> Result.NOT_MET
        Truth.UNKNOWN -> Result.STATE_UNAVAILABLE
    }

    private fun evaluate(c: Condition?, state: DeviceState): Truth = when (c) {
        null -> Truth.TRUE
        is Condition.And -> c.all.fold(Truth.TRUE) { result, child ->
            result and evaluate(child, state)
        }
        is Condition.Or -> c.any.fold(Truth.FALSE) { result, child ->
            result or evaluate(child, state)
        }
        is Condition.Not -> !evaluate(c.cond, state)
        is Condition.AppInForeground -> state.foregroundApp
            ?.let { Truth.of(it == c.pkg) }
            ?: Truth.UNKNOWN
        is Condition.StateEquals -> compare(state.values[c.key], c.op, c.value)
        is Condition.LocationIn -> state.location?.let {
            Truth.of(haversineM(it.lat, it.lng, c.lat, c.lng) <= c.radiusM)
        } ?: Truth.UNKNOWN
        is Condition.TimeWindow -> Truth.of(inWindow(c))
    }

    private fun compare(actual: String?, op: CmpOp, expected: String): Truth {
        if (actual == null) return Truth.UNKNOWN
        val an = actual.toDoubleOrNull(); val en = expected.toDoubleOrNull()
        return Truth.of(when (op) {
            CmpOp.EQ -> actual == expected
            CmpOp.NEQ -> actual != expected
            CmpOp.CONTAINS -> actual.contains(expected)
            CmpOp.GT -> if (an != null && en != null) an > en else actual > expected
            CmpOp.LT -> if (an != null && en != null) an < en else actual < expected
        })
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
