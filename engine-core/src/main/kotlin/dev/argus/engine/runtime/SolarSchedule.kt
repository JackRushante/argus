package dev.argus.engine.runtime

import dev.argus.engine.model.SolarEvent
import dev.argus.engine.model.SolarSpec
import dev.argus.engine.model.SolarTriggerPolicy
import dev.argus.engine.model.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Calcolo solare del trigger `time.solar` (0.4.1): alba e tramonto senza rete, con l'algoritmo del
 * calcolatore NOAA (le formule di Meeus già usate dal Global Monitoring Laboratory) e lo zenit
 * ufficiale di 90,833° (raggio solare più rifrazione atmosferica). L'altitudine del luogo non conta.
 * Accuratezza: entro un minuto fino a circa 72° di latitudine, qualche minuto oltre.
 *
 * Gli istanti sono assoluti: il cambio dell'ora lo gestisce il fuso, che serve solo a stabilire a
 * quale giorno civile appartiene un evento (e quindi a contare «oggi», «domani» e i giorni polari).
 */
object SolarSchedule {
    /** Esito della ricerca: un evento vero da consegnare, oppure un semplice ricontrollo. */
    sealed interface Next {
        val at: Instant

        /** Prossimo alba/tramonto (già spostato dell'offset): a quest'ora la regola scatta. */
        data class Fire(override val at: Instant) : Next

        /**
         * Nessun evento nei giorni cercati (notte o giorno polare): a quest'ora NON scatta nulla,
         * si ricalcola e si riprogramma.
         */
        data class Recheck(override val at: Instant) : Next
    }

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val ZENITH_DEGREES = 90.833

    /**
     * Prossimo evento strettamente dopo [after]. Considera gli eventi dei giorni civili (nel fuso del
     * trigger) da ieri a [SolarTriggerPolicy.LOOKAHEAD_DAYS] giorni in avanti: ieri conta perché un
     * offset positivo può portare il suo evento a oggi. Se nessuno cade dopo [after] è notte o
     * giorno polare e il risultato è [Next.Recheck] fra [SolarTriggerPolicy.RECHECK_HOURS] ore.
     *
     * Lancia [IllegalArgumentException] se il trigger non è solare, se la posizione non è ancora
     * stata congelata o se i campi sono fuori intervallo (il validatore lo impedisce a monte).
     */
    fun next(trigger: Trigger.Time, after: Instant): Next {
        val spec = requireNotNull(trigger.solar) { "solar_spec_missing" }
        val lat = requireNotNull(spec.lat) { "solar_location_unresolved" }
        val lng = requireNotNull(spec.lng) { "solar_location_unresolved" }
        require(SolarTriggerPolicy.validLatitude(lat) && SolarTriggerPolicy.validLongitude(lng)) {
            "solar_location_invalid"
        }
        require(SolarTriggerPolicy.validOffset(spec.offsetMinutes)) { "solar_offset_invalid" }
        val zone = ZoneId.of(trigger.tz)

        val today = after.atZone(zone).toLocalDate()
        val lastDay = today.plusDays(SolarTriggerPolicy.LOOKAHEAD_DAYS)
        val best = eventsAround(spec.event, lat, lng, zone, today.minusDays(1), lastDay)
            .map { it.plusSeconds(spec.offsetMinutes * 60L) }
            .filter { it.isAfter(after) }
            .minOrNull()
        return best?.let { Next.Fire(it) }
            ?: Next.Recheck(after.plusSeconds(SolarTriggerPolicy.RECHECK_HOURS * 3_600L))
    }

    /** Prossimo scatto vero (mai un ricontrollo), o null in notte/giorno polare. Per etichette e UI. */
    fun nextFire(trigger: Trigger.Time, after: Instant): Instant? =
        (next(trigger, after) as? Next.Fire)?.at

    /**
     * true se [atMillis] è un vero scatto della regola (alba/tramonto più offset) e non un
     * ricontrollo. Il coordinatore lo usa per non consegnare un ricontrollo alle azioni.
     */
    fun isFireInstant(trigger: Trigger.Time, atMillis: Long): Boolean =
        nextFire(trigger, Instant.ofEpochMilli(atMillis - 1))?.toEpochMilli() == atMillis

    /**
     * Gli eventi (senza offset) che cadono nel giorno civile [day] del fuso [zone], in ordine: di
     * norma uno, nessuno in notte/giorno polare, due nei rari giorni a cavallo della mezzanotte
     * in cui il Sole tramonta poco dopo le 00:00 e di nuovo poco prima delle 24:00.
     */
    fun eventsOn(event: SolarEvent, lat: Double, lng: Double, zone: ZoneId, day: LocalDate): List<Instant> =
        eventsAround(event, lat, lng, zone, day, day)

    /**
     * Tutti gli eventi il cui giorno civile (nel fuso [zone]) sta fra [from] e [to] inclusi, in
     * ordine. Il calcolo NOAA lavora per giorno UTC e ogni giorno UTC dà un solo evento, ma questo può
     * cadere sul giorno civile prima o dopo (fino a due giorni di scarto): si provano i giorni UTC
     * attorno all'intervallo e si tiene ciò che ricade dentro.
     */
    private fun eventsAround(
        event: SolarEvent,
        lat: Double,
        lng: Double,
        zone: ZoneId,
        from: LocalDate,
        to: LocalDate,
    ): List<Instant> {
        val days = ChronoUnit.DAYS.between(from, to)
        return (-3L..days + 3L)
            .mapNotNull { shift -> utcDayEvent(event, lat, lng, from.plusDays(shift)) }
            .filter { instant ->
                val local = instant.atZone(zone).toLocalDate()
                !local.isBefore(from) && !local.isAfter(to)
            }
            .sorted()
    }

    /** L'evento del ciclo solare legato al giorno UTC [utcDay], arrotondato al minuto; null se polare. */
    internal fun utcDayEvent(event: SolarEvent, lat: Double, lng: Double, utcDay: LocalDate): Instant? {
        val rise = event == SolarEvent.SUNRISE
        // Giorno giuliano a mezzanotte UT: l'epoch Unix (1970-01-01 00:00 UT) è il JD 2440587,5.
        val julianDay = utcDay.toEpochDay() + 2_440_587.5
        val first = eventMinutesUtc(julianDay, lat, lng, rise) ?: return null
        // Seconda passata: i parametri del Sole si rivalutano all'ora dell'evento (come il NOAA).
        val minutes = eventMinutesUtc(julianDay + first / 1_440.0, lat, lng, rise) ?: return null
        val dayStartMillis = utcDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val millis = dayStartMillis + Math.round(minutes * MILLIS_PER_MINUTE)
        return Instant.ofEpochMilli(Math.round(millis / MILLIS_PER_MINUTE.toDouble()) * MILLIS_PER_MINUTE)
    }

    /**
     * Minuti dalla mezzanotte UT del giorno giuliano [julianDay] a cui il Sole attraversa l'orizzonte;
     * può uscire da 0..1440 per longitudini lontane da Greenwich. null se quel giorno non sorge
     * o non tramonta (notte/giorno polare).
     */
    private fun eventMinutesUtc(julianDay: Double, lat: Double, lng: Double, rise: Boolean): Double? {
        val t = (julianDay - 2_451_545.0) / 36_525.0

        val meanLongitude = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
        val meanAnomaly = Math.toRadians(357.52911 + t * (35_999.05029 - 0.0001537 * t))
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val equationOfCenter = sin(meanAnomaly) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * meanAnomaly) * (0.019993 - 0.000101 * t) +
            sin(3 * meanAnomaly) * 0.000289
        val omega = Math.toRadians(125.04 - 1_934.136 * t)
        val apparentLongitude = Math.toRadians(
            meanLongitude + equationOfCenter - 0.00569 - 0.00478 * sin(omega),
        )
        val obliquitySeconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        val obliquity = Math.toRadians(
            23.0 + (26.0 + obliquitySeconds / 60.0) / 60.0 + 0.00256 * cos(omega),
        )
        val declination = asin(sin(obliquity) * sin(apparentLongitude))

        val y = tan(obliquity / 2).let { it * it }
        val l0 = Math.toRadians(meanLongitude)
        val equationOfTimeMinutes = Math.toDegrees(
            y * sin(2 * l0) - 2 * eccentricity * sin(meanAnomaly) +
                4 * eccentricity * y * sin(meanAnomaly) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) -
                1.25 * eccentricity * eccentricity * sin(2 * meanAnomaly),
        ) * 4.0

        val latitude = Math.toRadians(lat)
        val cosine = cos(Math.toRadians(ZENITH_DEGREES)) / (cos(latitude) * cos(declination)) -
            tan(latitude) * tan(declination)
        if (cosine.isNaN() || cosine > 1.0 || cosine < -1.0) return null
        val hourAngle = Math.toDegrees(acos(cosine)).let { if (rise) it else -it }
        return 720.0 - 4.0 * (lng + hourAngle) - equationOfTimeMinutes
    }
}
