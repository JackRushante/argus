package dev.argus.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Semi-finestra `[startMillis, endMillisExclusive)`: l'evento "adesso" è incluso, il DAO usa `< end`. */
data class UsageWindow(val startMillis: Long, val endMillisExclusive: Long)

/**
 * Confini delle finestre di aggregazione usage. `lastHour` è un rolling window; `currentDay`/
 * `currentMonth` partono dalla mezzanotte locale (zone-aware, DST-safe via java.time, minSdk 30).
 * La fine è sempre `nowMillis + 1` così che l'evento corrente rientri nella query `< end`.
 */
object UsageWindows {
    const val HOUR_MILLIS = 3_600_000L

    fun lastHour(nowMillis: Long): UsageWindow =
        UsageWindow(nowMillis - HOUR_MILLIS, nowMillis + 1)

    fun currentDay(nowMillis: Long, zone: ZoneId): UsageWindow {
        val start = Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant()
            .toEpochMilli()
        return UsageWindow(start, nowMillis + 1)
    }

    fun currentMonth(nowMillis: Long, zone: ZoneId): UsageWindow {
        val start = Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .with(TemporalAdjusters.firstDayOfMonth())
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant()
            .toEpochMilli()
        return UsageWindow(start, nowMillis + 1)
    }
}
