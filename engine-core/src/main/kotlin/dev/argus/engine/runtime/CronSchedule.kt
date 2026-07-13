package dev.argus.engine.runtime
import dev.argus.engine.model.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Cron 5 campi (min hour dom mon dow). Subset supportato: `*`, `n`, liste `a,b`, range `a-b`, step `*/s` e `a-b/s`.
// DOW 0-7 (0 e 7 = domenica). DOM e DOW entrambi ristretti = OR (semantica vixie).
// DST: ora locale saltata -> fire spostato avanti della durata del gap; ora duplicata -> una sola
// esecuzione al primo offset (comportamento documentato di java.time, testato).
class CronSchedule private constructor(
    private val minutes: Set<Int>, private val hours: Set<Int>,
    private val dom: Set<Int>, private val months: Set<Int>, private val dow: Set<Int>,
    private val domRestricted: Boolean, private val dowRestricted: Boolean,
) {
    /** Prossimo scatto STRETTAMENTE dopo [after], o null se non esiste entro 5 anni. */
    fun nextFireAfter(after: Instant, zone: ZoneId): Instant? {
        var t = LocalDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(5)
        while (t < limit) {
            if (t.monthValue !in months) { t = t.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS); continue }
            if (!dayMatches(t.toLocalDate())) { t = t.plusDays(1).truncatedTo(ChronoUnit.DAYS); continue }
            if (t.hour !in hours) { t = t.plusHours(1).truncatedTo(ChronoUnit.HOURS); continue }
            if (t.minute !in minutes) { t = t.plusMinutes(1); continue }
            return t.atZone(zone).toInstant()   // gap -> shift avanti; overlap -> primo offset
        }
        return null
    }

    private fun dayMatches(d: LocalDate): Boolean {
        val domOk = d.dayOfMonth in dom
        val dowOk = (d.dayOfWeek.value % 7) in dow   // java Mon=1..Sun=7 -> cron Sun=0
        return when {
            domRestricted && dowRestricted -> domOk || dowOk
            domRestricted -> domOk
            dowRestricted -> dowOk
            else -> true
        }
    }

    companion object {
        fun parse(expr: String): CronSchedule {
            val f = expr.trim().split(Regex("\\s+"))
            require(f.size == 5) { "cron: servono 5 campi, trovati ${f.size} in '$expr'" }
            val dowRaw = parseField(f[4], 0, 7)
            return CronSchedule(
                minutes = parseField(f[0], 0, 59),
                hours = parseField(f[1], 0, 23),
                dom = parseField(f[2], 1, 31),
                months = parseField(f[3], 1, 12),
                dow = dowRaw.map { it % 7 }.toSet(),
                domRestricted = f[2] != "*",
                dowRestricted = f[4] != "*",
            )
        }

        private fun parseField(field: String, min: Int, max: Int): Set<Int> {
            if (field == "*") return (min..max).toSet()
            val out = mutableSetOf<Int>()
            for (part in field.split(',')) {
                val bits = part.split('/', limit = 2)
                val step = if (bits.size == 2)
                    requireNotNull(bits[1].toIntOrNull()?.takeIf { it > 0 }) { "cron: step non valido '$part'" }
                else 1
                val rangePart = bits[0]
                val range = when {
                    rangePart == "*" -> min..max
                    rangePart.contains('-') -> {
                        val lo = rangePart.substringBefore('-').toIntOrNull()
                        val hi = rangePart.substringAfter('-').toIntOrNull()
                        require(lo != null && hi != null && lo <= hi) { "cron: range non valido '$part'" }
                        lo..hi
                    }
                    else -> {
                        val v = requireNotNull(rangePart.toIntOrNull()) { "cron: valore non valido '$part'" }
                        v..v
                    }
                }
                require(range.first >= min && range.last <= max) { "cron: '$part' fuori da [$min,$max]" }
                out += range step step
            }
            return out
        }
    }
}

/** Entry-point unico per P0-B (AlarmManagerTimeTrigger): gestisce cron e at. */
object TimeSpecs {
    /** Prossimo scatto strettamente dopo [after], o null (one-shot passato / cron senza occorrenze).
     *  Lancia IllegalArgumentException/DateTimeException su spec malformato: il DraftValidator
     *  garantisce che non arrivi mai qui un Time invalido. */
    fun nextFire(t: Trigger.Time, after: Instant): Instant? {
        val zone = ZoneId.of(t.tz)
        return when {
            t.cron != null -> CronSchedule.parse(t.cron).nextFireAfter(after, zone)
            t.at != null -> LocalDateTime.parse(t.at).atZone(zone).toInstant().takeIf { it > after }
            else -> null
        }
    }
}
