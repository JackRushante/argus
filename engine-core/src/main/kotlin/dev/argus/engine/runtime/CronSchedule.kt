package dev.argus.engine.runtime
import dev.argus.engine.model.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
        var date = LocalDateTime.ofInstant(after, zone).toLocalDate()
        val limit = date.plusYears(5)
        val sortedHours = hours.sorted()
        val sortedMinutes = minutes.sorted()

        while (!date.isAfter(limit)) {
            if (date.monthValue in months && dayMatches(date)) {
                // Un gap può spostare 02:30 dopo una regolare 03:15: si raccolgono tutti
                // i candidati del giorno e si sceglie il primo Instant, non il primo local-time.
                var best: Instant? = null
                for (hour in sortedHours) for (minute in sortedMinutes) {
                    val candidate = resolveScheduledLocal(date.atTime(hour, minute), zone)
                    if (candidate > after && (best == null || candidate < best)) best = candidate
                }
                if (best != null) return best
            }
            date = date.plusDays(1)
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

/**
 * Risolve la semantica Argus per un orario locale pianificato:
 * - gap DST: trasla avanti della durata del gap;
 * - overlap DST: usa solo la prima occorrenza (offset precedente), mai la seconda.
 */
private fun resolveScheduledLocal(local: LocalDateTime, zone: ZoneId): Instant {
    val rules = zone.rules
    val offsets = rules.getValidOffsets(local)
    return when {
        offsets.size == 1 -> local.toInstant(offsets.single())
        offsets.size >= 2 -> local.toInstant(offsets.first())
        else -> {
            val transition = requireNotNull(rules.getTransition(local)) {
                "Transizione DST assente per local-time non valido $local in $zone"
            }
            local.plusSeconds(transition.duration.seconds).toInstant(transition.offsetAfter)
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
            t.at != null -> resolveScheduledLocal(LocalDateTime.parse(t.at), zone).takeIf { it > after }
            else -> null
        }
    }
}
