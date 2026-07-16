package dev.argus.data

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Confini delle finestre di aggregazione usage (ora rolling / giorno / mese, zone-aware).
 * L'atteso è costruito nel test con java.time, mai richiamando la funzione in prova.
 */
class UsageWindowsTest {

    @Test
    fun `lastHour is a rolling window ending now inclusive`() {
        val window = UsageWindows.lastHour(10_000_000L)
        assertEquals(10_000_000L - 3_600_000L, window.startMillis)
        assertEquals(10_000_001L, window.endMillisExclusive)
    }

    @Test
    fun `currentDay starts at local midnight`() {
        val rome = ZoneId.of("Europe/Rome")
        val now = ZonedDateTime.of(2026, 7, 16, 10, 45, 0, 0, rome)
        val nowMillis = now.toInstant().toEpochMilli()
        val expectedStart = ZonedDateTime.of(2026, 7, 16, 0, 0, 0, 0, rome)
            .toInstant().toEpochMilli()

        val window = UsageWindows.currentDay(nowMillis, rome)
        assertEquals(expectedStart, window.startMillis)
        assertEquals(nowMillis + 1, window.endMillisExclusive)
    }

    @Test
    fun `currentMonth starts on the first of the month`() {
        val rome = ZoneId.of("Europe/Rome")
        val now = ZonedDateTime.of(2026, 7, 16, 10, 45, 0, 0, rome)
        val nowMillis = now.toInstant().toEpochMilli()
        val expectedStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, rome)
            .toInstant().toEpochMilli()

        val window = UsageWindows.currentMonth(nowMillis, rome)
        assertEquals(expectedStart, window.startMillis)
        assertEquals(nowMillis + 1, window.endMillisExclusive)
    }

    @Test
    fun `currentMonth handles a month boundary across DST`() {
        val rome = ZoneId.of("Europe/Rome")
        // 2026-03-31 è dopo il passaggio DST (ultima domenica di marzo): il 1° marzo era CET.
        val now = ZonedDateTime.of(2026, 3, 31, 12, 0, 0, 0, rome)
        val nowMillis = now.toInstant().toEpochMilli()
        val expectedStart = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, rome)
            .toInstant().toEpochMilli()

        val window = UsageWindows.currentMonth(nowMillis, rome)
        assertEquals(expectedStart, window.startMillis)
    }
}
