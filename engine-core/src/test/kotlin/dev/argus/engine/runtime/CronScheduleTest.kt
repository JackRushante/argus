package dev.argus.engine.runtime
import dev.argus.engine.model.Trigger
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
class CronScheduleTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun next(cron: String, afterIso: String): Instant? =
        CronSchedule.parse(cron).nextFireAfter(Instant.parse(afterIso), rome)

    @Test fun `daily at 23 local`() {
        assertEquals(Instant.parse("2026-07-12T21:00:00Z"), next("0 23 * * *", "2026-07-12T10:00:00Z"))
    }
    @Test fun `strictly after - fire time itself rolls to next day`() {
        assertEquals(Instant.parse("2026-07-13T21:00:00Z"), next("0 23 * * *", "2026-07-12T21:00:00Z"))
    }
    @Test fun `dst gap - skipped local time shifts forward`() {
        // 29/03/2026: 02:00->03:00 CEST; 02:30 locale non esiste -> 03:30 CEST = 01:30Z
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), next("30 2 * * *", "2026-03-28T23:30:00Z"))
    }
    @Test fun `dst overlap - duplicated local time fires once (earlier offset)`() {
        // 25/10/2026: 03:00 CEST -> 02:00 CET; 02:30 occorre due volte -> prima occorrenza (CEST) = 00:30Z
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), next("30 2 * * *", "2026-10-24T22:00:00Z"))
    }
    @Test fun `step and dow`() {
        assertEquals(Instant.parse("2026-07-12T10:15:00Z"), next("*/15 * * * *", "2026-07-12T10:07:00Z"))
        // 12/07/2026 è domenica -> lunedì 13 alle 09:00 CEST
        assertEquals(Instant.parse("2026-07-13T07:00:00Z"), next("0 9 * * 1", "2026-07-12T10:00:00Z"))
        // dow 7 = domenica
        assertEquals(Instant.parse("2026-07-12T07:00:00Z"), next("0 9 * * 7", "2026-07-11T00:00:00Z"))
    }
    @Test fun `vixie OR between dom and dow`() {
        // "giorno 1 del mese OPPURE lunedì": dopo mercoledì 1/7 sera viene lunedì 6/7, non il 1/8
        assertEquals(Instant.parse("2026-07-06T07:00:00Z"), next("0 9 1 * 1", "2026-07-01T12:00:00Z"))
    }
    @Test fun `leap day`() {
        assertEquals(Instant.parse("2028-02-29T11:00:00Z"), next("0 12 29 2 *", "2026-03-01T00:00:00Z"))
    }
    @Test fun `invalid expressions throw`() {
        assertFailsWith<IllegalArgumentException> { CronSchedule.parse("61 * * * *") }
        assertFailsWith<IllegalArgumentException> { CronSchedule.parse("* * * *") }
        assertFailsWith<IllegalArgumentException> { CronSchedule.parse("x * * * *") }
    }
    @Test fun `timespecs one-shot at`() {
        val t = Trigger.Time(at = "2026-07-15T08:00", tz = "Europe/Rome")
        assertEquals(Instant.parse("2026-07-15T06:00:00Z"), TimeSpecs.nextFire(t, Instant.parse("2026-07-12T00:00:00Z")))
        assertNull(TimeSpecs.nextFire(t, Instant.parse("2026-07-15T06:00:00Z")))   // passato -> mai più
    }
}
