package dev.argus.engine.runtime
import dev.argus.engine.model.*
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.*

class ConditionEvaluator(private val clock: Clock) {
    /** Condizione null = nessun vincolo = vero. */
    fun eval(c: Condition?, state: DeviceState): Boolean = when (c) {
        null -> true
        is Condition.And -> c.all.all { eval(it, state) }
        is Condition.Or -> c.any.any { eval(it, state) }
        is Condition.Not -> !eval(c.cond, state)
        is Condition.AppInForeground -> state.foregroundApp == c.pkg
        is Condition.StateEquals -> compare(state.values[c.key], c.op, c.value)
        is Condition.LocationIn -> state.location?.let {
            haversineM(it.lat, it.lng, c.lat, c.lng) <= c.radiusM
        } ?: false
        is Condition.TimeWindow -> inWindow(c)
    }

    private fun compare(actual: String?, op: CmpOp, expected: String): Boolean {
        if (actual == null) return false
        val an = actual.toDoubleOrNull(); val en = expected.toDoubleOrNull()
        return when (op) {
            CmpOp.EQ -> actual == expected
            CmpOp.NEQ -> actual != expected
            CmpOp.CONTAINS -> actual.contains(expected)
            CmpOp.GT -> if (an != null && en != null) an > en else actual > expected
            CmpOp.LT -> if (an != null && en != null) an < en else actual < expected
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
}
