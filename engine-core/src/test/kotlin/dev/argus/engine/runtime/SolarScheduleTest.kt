package dev.argus.engine.runtime

import dev.argus.engine.model.SolarEvent
import dev.argus.engine.model.SolarSpec
import dev.argus.engine.model.Trigger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Alba e tramonto (0.4.1, F9). I valori di riferimento sono PUBBLICATI da due fonti indipendenti,
 * lette il 2026-10-26, e il calcolo deve cadere entro 2 minuti da entrambe:
 *  - U.S. Naval Observatory, Astronomical Applications API
 *    `https://aa.usno.navy.mil/api/rstt/oneday?date=<data>&coords=<lat>,<lng>&tz=<ore>`;
 *  - MET Norway, Sunrise API 3.0
 *    `https://api.met.no/weatherapi/sunrise/3.0/sun?lat=<lat>&lon=<lng>&date=<data>&offset=<±HH:MM>`.
 * Per Roma il 2026-03-29 anche sunrise-sunset.org dà 04:57:40 / 17:31:52 UTC (06:57 / 19:32 CEST).
 */
class SolarScheduleTest {
    private data class Reference(
        val place: String,
        val lat: Double,
        val lng: Double,
        val zone: String,
        val date: String,
        val usnoRise: String,
        val usnoSet: String,
        val metRise: String,
        val metSet: String,
    )

    private val references = listOf(
        // Roma prima e dopo il passaggio all'ora legale (2026-03-29, 02:00 -> 03:00).
        Reference("Roma", 41.9028, 12.4964, "Europe/Rome", "2026-03-28", "06:00", "18:31", "05:59", "18:30"),
        Reference("Roma", 41.9028, 12.4964, "Europe/Rome", "2026-03-29", "06:58", "19:32", "06:58", "19:32"),
        // Latitudine alta in pieno inverno (giorno cortissimo).
        Reference("Reykjavik", 64.1466, -21.9426, "Atlantic/Reykjavik", "2026-12-21", "11:22", "15:29", "11:22", "15:29"),
        Reference("Anchorage", 61.2181, -149.9003, "America/Anchorage", "2026-12-21", "10:14", "15:41", "10:14", "15:41"),
        // Equatore, a ridosso dell'equinozio.
        Reference("Quito", -0.1807, -78.4678, "America/Guayaquil", "2026-03-20", "06:18", "18:24", "06:18", "18:24"),
        // Emisfero australe, longitudine molto a est e molto a ovest.
        Reference("Sydney", -33.8688, 151.2093, "Australia/Sydney", "2026-12-21", "05:41", "20:05", "05:40", "20:05"),
        Reference("Honolulu", 21.3069, -157.8583, "Pacific/Honolulu", "2026-06-21", "05:50", "19:16", "05:50", "19:16"),
    )

    private fun instant(zone: String, date: String, time: String): Instant =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ZoneId.of(zone)).toInstant()

    private fun minutesApart(a: Instant, b: Instant): Long = Duration.between(a, b).abs().toMinutes()

    private fun events(event: SolarEvent, lat: Double, lng: Double, zone: String, date: String) =
        SolarSchedule.eventsOn(event, lat, lng, ZoneId.of(zone), LocalDate.parse(date))

    private fun solar(
        event: SolarEvent,
        lat: Double,
        lng: Double,
        zone: String,
        offsetMinutes: Int = 0,
    ) = Trigger.Time(tz = zone, solar = SolarSpec(event, offsetMinutes, lat, lng))

    private val rome = Triple(41.9028, 12.4964, "Europe/Rome")
    private val tromso = Triple(69.6492, 18.9553, "Europe/Oslo")

    private fun romeSolar(event: SolarEvent, offsetMinutes: Int = 0) =
        solar(event, rome.first, rome.second, rome.third, offsetMinutes)

    private fun tromsoSolar(event: SolarEvent, offsetMinutes: Int = 0) =
        solar(event, tromso.first, tromso.second, tromso.third, offsetMinutes)

    @Test
    fun `sunrise and sunset match the published values of two sources within two minutes`() {
        references.forEach { ref ->
            val rise = events(SolarEvent.SUNRISE, ref.lat, ref.lng, ref.zone, ref.date).single()
            val set = events(SolarEvent.SUNSET, ref.lat, ref.lng, ref.zone, ref.date).single()
            val label = "${ref.place} ${ref.date}"
            assertTrue(minutesApart(rise, instant(ref.zone, ref.date, ref.usnoRise)) <= 2, "$label alba USNO $rise")
            assertTrue(minutesApart(rise, instant(ref.zone, ref.date, ref.metRise)) <= 2, "$label alba MET $rise")
            assertTrue(minutesApart(set, instant(ref.zone, ref.date, ref.usnoSet)) <= 2, "$label tramonto USNO $set")
            assertTrue(minutesApart(set, instant(ref.zone, ref.date, ref.metSet)) <= 2, "$label tramonto MET $set")
        }
    }

    @Test
    fun `the next event is the first one strictly after the given instant`() {
        val trigger = romeSolar(SolarEvent.SUNSET)
        val today = events(SolarEvent.SUNSET, rome.first, rome.second, rome.third, "2026-06-21").single()
        val tomorrow = events(SolarEvent.SUNSET, rome.first, rome.second, rome.third, "2026-06-22").single()

        assertEquals(today, SolarSchedule.nextFire(trigger, today.minusSeconds(1)))
        // Nell'istante stesso dell'evento «oggi è già passato»: si va a domani.
        assertEquals(tomorrow, SolarSchedule.nextFire(trigger, today))
        assertEquals(tomorrow, SolarSchedule.nextFire(trigger, today.plusSeconds(3_600)))
        val gap = Duration.between(today, tomorrow)
        assertTrue(gap >= Duration.ofHours(23) && gap <= Duration.ofHours(25), "tra due tramonti: $gap")
    }

    @Test
    fun `minutes before and after are added to the event`() {
        val base = events(SolarEvent.SUNSET, rome.first, rome.second, rome.third, "2026-06-21").single()
        val sunrise = events(SolarEvent.SUNRISE, rome.first, rome.second, rome.third, "2026-06-21").single()
        val after = Instant.parse("2026-06-21T00:00:00Z").minusSeconds(3 * 3_600L)

        assertEquals(base.minusSeconds(30 * 60L), SolarSchedule.nextFire(romeSolar(SolarEvent.SUNSET, -30), after))
        assertEquals(base.plusSeconds(45 * 60L), SolarSchedule.nextFire(romeSolar(SolarEvent.SUNSET, 45), after))
        assertEquals(sunrise.plusSeconds(90 * 60L), SolarSchedule.nextFire(romeSolar(SolarEvent.SUNRISE, 90), after))
    }

    @Test
    fun `a positive offset can move yesterday's event into today`() {
        val yesterday = events(SolarEvent.SUNSET, rome.first, rome.second, rome.third, "2026-06-20").single()
        val trigger = romeSolar(SolarEvent.SUNSET, 720)
        // Poco dopo la mezzanotte locale di oggi, prima delle 08:49 (tramonto di ieri + 12 ore).
        val after = instant(rome.third, "2026-06-21", "00:30")

        val fire = assertIs<SolarSchedule.Next.Fire>(SolarSchedule.next(trigger, after))

        assertEquals(yesterday.plusSeconds(720 * 60L), fire.at)
        assertEquals(LocalDate.parse("2026-06-21"), fire.at.atZone(ZoneId.of(rome.third)).toLocalDate())
    }

    @Test
    fun `a negative offset reaches two days ahead when the event is twelve hours early`() {
        // Alba del 23 giugno meno 12 ore = sera del 22: il primo scatto dopo le 23:30 del 21 giugno.
        val sunrise = events(SolarEvent.SUNRISE, rome.first, rome.second, rome.third, "2026-06-23").single()
        val trigger = romeSolar(SolarEvent.SUNRISE, -720)
        val after = instant(rome.third, "2026-06-21", "23:30")

        val fire = assertIs<SolarSchedule.Next.Fire>(SolarSchedule.next(trigger, after))

        assertEquals(sunrise.minusSeconds(720 * 60L), fire.at)
        assertEquals(LocalDate.parse("2026-06-22"), fire.at.atZone(ZoneId.of(rome.third)).toLocalDate())
    }

    @Test
    fun `daylight saving changes the wall clock but not the absolute chain of events`() {
        val trigger = romeSolar(SolarEvent.SUNSET)
        val zone = ZoneId.of(rome.third)
        // Primavera: 2026-03-29 02:00 CET -> 03:00 CEST.
        val sunsetBefore = events(SolarEvent.SUNSET, rome.first, rome.second, rome.third, "2026-03-28").single()
        val sunsetAfter = events(SolarEvent.SUNSET, rome.first, rome.second, rome.third, "2026-03-29").single()
        assertEquals(sunsetAfter, SolarSchedule.nextFire(trigger, sunsetBefore.plusSeconds(60)))
        assertEquals(LocalTime.parse("18:31"), sunsetBefore.atZone(zone).toLocalTime())
        assertEquals(LocalTime.parse("19:32"), sunsetAfter.atZone(zone).toLocalTime())
        // L'orologio sul muro salta di un'ora, il Sole no: due tramonti restano a ~24 ore.
        val springGap = Duration.between(sunsetBefore, sunsetAfter)
        assertTrue(springGap >= Duration.ofHours(23) + Duration.ofMinutes(55), "primavera: $springGap")
        assertTrue(springGap <= Duration.ofHours(24) + Duration.ofMinutes(5), "primavera: $springGap")

        // Autunno: 2026-10-25 03:00 CEST -> 02:00 CET.
        val sunsetOct24 = events(SolarEvent.SUNSET, rome.first, rome.second, rome.third, "2026-10-24").single()
        val sunsetOct25 = events(SolarEvent.SUNSET, rome.first, rome.second, rome.third, "2026-10-25").single()
        assertEquals(sunsetOct25, SolarSchedule.nextFire(trigger, sunsetOct24.plusSeconds(60)))
        assertEquals(LocalTime.parse("18:15"), sunsetOct24.atZone(zone).toLocalTime())
        assertEquals(LocalTime.parse("17:14"), sunsetOct25.atZone(zone).toLocalTime())
        val autumnGap = Duration.between(sunsetOct24, sunsetOct25)
        assertTrue(autumnGap >= Duration.ofHours(23) + Duration.ofMinutes(55), "autunno: $autumnGap")
        assertTrue(autumnGap <= Duration.ofHours(24) + Duration.ofMinutes(5), "autunno: $autumnGap")
    }

    @Test
    fun `polar night and midnight sun have no sunrise or sunset`() {
        listOf("2026-12-21", "2026-12-01").forEach { date ->
            assertEquals(emptyList(), events(SolarEvent.SUNRISE, tromso.first, tromso.second, tromso.third, date), date)
            assertEquals(emptyList(), events(SolarEvent.SUNSET, tromso.first, tromso.second, tromso.third, date), date)
        }
        listOf("2026-06-21", "2026-06-05").forEach { date ->
            assertEquals(emptyList(), events(SolarEvent.SUNRISE, tromso.first, tromso.second, tromso.third, date), date)
            assertEquals(emptyList(), events(SolarEvent.SUNSET, tromso.first, tromso.second, tromso.third, date), date)
        }
        // Lo stesso giorno più a sud ha entrambi gli eventi: il vuoto viene dal luogo, non dal calcolo.
        assertEquals(1, events(SolarEvent.SUNRISE, 59.9139, 10.7522, "Europe/Oslo", "2026-12-21").size)
    }

    @Test
    fun `with no event in the next two days it rechecks after 24 hours instead of firing`() {
        val trigger = tromsoSolar(SolarEvent.SUNRISE)
        val after = instant(tromso.third, "2026-12-21", "12:00")

        val next = assertIs<SolarSchedule.Next.Recheck>(SolarSchedule.next(trigger, after))

        assertEquals(after.plus(Duration.ofHours(24)), next.at)
        assertEquals(null, SolarSchedule.nextFire(trigger, after))
        assertFalse(SolarSchedule.isFireInstant(trigger, next.at.toEpochMilli()))
    }

    @Test
    fun `the lookahead sees an event up to two days ahead but not three`() {
        val zone = ZoneId.of(tromso.third)
        val firstSunrise = generateSequence(LocalDate.parse("2027-01-01")) { it.plusDays(1) }
            .first { events(SolarEvent.SUNRISE, tromso.first, tromso.second, tromso.third, it.toString()).isNotEmpty() }
        val sunrise = events(SolarEvent.SUNRISE, tromso.first, tromso.second, tromso.third, firstSunrise.toString()).single()
        assertTrue(firstSunrise.isAfter(LocalDate.parse("2027-01-05")), "il sole non torna a Tromso il $firstSunrise")
        val trigger = tromsoSolar(SolarEvent.SUNRISE)

        val threeDaysAhead = firstSunrise.minusDays(3).atTime(12, 0).atZone(zone).toInstant()
        val twoDaysAhead = firstSunrise.minusDays(2).atTime(12, 0).atZone(zone).toInstant()

        assertIs<SolarSchedule.Next.Recheck>(SolarSchedule.next(trigger, threeDaysAhead))
        assertEquals(sunrise, assertIs<SolarSchedule.Next.Fire>(SolarSchedule.next(trigger, twoDaysAhead)).at)
    }

    @Test
    fun `daily rechecks reach the first sunrise of the year and then fire on it`() {
        val trigger = tromsoSolar(SolarEvent.SUNRISE, -30)
        val zone = ZoneId.of(tromso.third)
        var cursor = instant(tromso.third, "2026-12-21", "12:00")
        var rechecks = 0

        val fire = generateSequence { SolarSchedule.next(trigger, cursor) }
            .onEach { if (it is SolarSchedule.Next.Recheck) { rechecks++; cursor = it.at } }
            .take(60)
            .first { it is SolarSchedule.Next.Fire }

        assertTrue(rechecks in 20..30, "controlli giornalieri prima dell'alba: $rechecks")
        val local = fire.at.atZone(zone)
        assertEquals(2027, local.year)
        assertEquals(1, local.monthValue)
        assertTrue(SolarSchedule.isFireInstant(trigger, fire.at.toEpochMilli()))
    }

    @Test
    fun `no event is skipped even when the sun sets twice in one civil day`() {
        // Fine del sole di mezzanotte a Tromso: nei giorni a cavallo si ha un tramonto poco dopo le 00:00
        // e uno poco prima delle 24:00 dello stesso giorno civile.
        val trigger = tromsoSolar(SolarEvent.SUNSET)
        val zone = ZoneId.of(tromso.third)
        val from = LocalDate.parse("2026-07-10")
        val to = LocalDate.parse("2026-08-10")
        val expected = (-3L..(to.toEpochDay() - from.toEpochDay() + 3))
            .mapNotNull { SolarSchedule.utcDayEvent(SolarEvent.SUNSET, tromso.first, tromso.second, from.plusDays(it)) }
            .filter { instant ->
                val day = instant.atZone(zone).toLocalDate()
                !day.isBefore(from) && !day.isAfter(to)
            }

        var cursor = from.atStartOfDay(zone).toInstant().minusSeconds(1)
        val fired = mutableListOf<Instant>()
        while (true) {
            val fire = SolarSchedule.next(trigger, cursor)
            cursor = fire.at
            if (fire is SolarSchedule.Next.Fire) {
                if (fire.at.atZone(zone).toLocalDate() > to) break
                fired += fire.at
            } else if (cursor > to.plusDays(3).atStartOfDay(zone).toInstant()) {
                break
            }
        }

        assertEquals(expected.sorted(), fired)
    }

    @Test
    fun `every day has one sunrise and one sunset about a day apart in any time zone`() {
        val places = listOf(
            Triple(41.9028, 12.4964, "Europe/Rome"),
            Triple(-33.8688, 151.2093, "Australia/Sydney"),
            Triple(21.3069, -157.8583, "Pacific/Honolulu"),
            Triple(1.87, -157.4, "Pacific/Kiritimati"), // UTC+14 con il Sole a -10 h
            Triple(-36.85, 174.76, "Pacific/Auckland"),
            Triple(39.47, 75.99, "Asia/Shanghai"), // fuso a +8 h, Sole a +5 h
            Triple(61.2181, -149.9003, "America/Anchorage"),
            Triple(-0.1807, -78.4678, "America/Guayaquil"),
            Triple(-13.83, -171.76, "Pacific/Apia"),
            Triple(51.5, -0.12, "Europe/London"),
        )
        places.forEach { (lat, lng, zone) ->
            SolarEvent.entries.forEach { event ->
                var previous: Instant? = null
                generateSequence(LocalDate.parse("2026-01-01")) { it.plusDays(1) }.take(365).forEach { day ->
                    val found = SolarSchedule.eventsOn(event, lat, lng, ZoneId.of(zone), day)
                    assertEquals(1, found.size, "$zone $event $day: ${found.size} eventi")
                    previous?.let { earlier ->
                        val gap = Duration.between(earlier, found.single())
                        assertTrue(
                            gap >= Duration.ofHours(23) && gap <= Duration.ofHours(25),
                            "$zone $event $day: distanza dal giorno prima $gap",
                        )
                    }
                    previous = found.single()
                }
            }
        }
    }

    @Test
    fun `a recheck instant is not a fire and a real event is`() {
        val trigger = romeSolar(SolarEvent.SUNSET, -30)
        val fire = assertIs<SolarSchedule.Next.Fire>(
            SolarSchedule.next(trigger, instant(rome.third, "2026-06-21", "12:00")),
        )

        assertTrue(SolarSchedule.isFireInstant(trigger, fire.at.toEpochMilli()))
        assertFalse(SolarSchedule.isFireInstant(trigger, fire.at.toEpochMilli() + 60_000))
        assertFalse(SolarSchedule.isFireInstant(trigger, fire.at.toEpochMilli() - 1))
    }

    @Test
    fun `an unresolved location or an out of range field is rejected`() {
        val after = Instant.parse("2026-06-21T10:00:00Z")
        assertFailsWith<IllegalArgumentException> {
            SolarSchedule.next(Trigger.Time(tz = "Europe/Rome", solar = SolarSpec(SolarEvent.SUNSET, 0)), after)
        }
        assertFailsWith<IllegalArgumentException> {
            SolarSchedule.next(Trigger.Time(tz = "Europe/Rome", cron = "0 9 * * *"), after)
        }
        assertFailsWith<IllegalArgumentException> { SolarSchedule.next(romeSolar(SolarEvent.SUNSET, 721), after) }
        assertFailsWith<IllegalArgumentException> { SolarSchedule.next(romeSolar(SolarEvent.SUNSET, -721), after) }
        assertFailsWith<IllegalArgumentException> {
            SolarSchedule.next(solar(SolarEvent.SUNSET, 91.0, 12.0, "Europe/Rome"), after)
        }
        assertFailsWith<IllegalArgumentException> {
            SolarSchedule.next(solar(SolarEvent.SUNSET, 41.0, Double.NaN, "Europe/Rome"), after)
        }
        assertFailsWith<Exception> { SolarSchedule.next(solar(SolarEvent.SUNSET, 41.0, 12.0, "Not/AZone"), after) }
    }
}
