package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.SolarEvent
import dev.argus.engine.model.SolarSpec
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validatore della variante solare del trigger `time` (0.4.1, F9). */
class DraftValidatorSolarTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))

    private fun errors(trigger: Trigger) =
        v.validate(
            AutomationDraft(name = "regola", trigger = trigger, actions = listOf(Action.SetFlashlight(true))),
            emptySet(),
        ).filter { it.severity == Severity.ERROR }.map { it.code }

    private fun solar(spec: SolarSpec, tz: String = "Europe/Rome") = Trigger.Time(tz = tz, solar = spec)

    @Test
    fun `a solar trigger without a position is a valid draft`() {
        assertEquals(emptyList(), errors(solar(SolarSpec(SolarEvent.SUNSET, -30))))
        assertEquals(emptyList(), errors(solar(SolarSpec(SolarEvent.SUNRISE))))
        assertEquals(emptyList(), errors(solar(SolarSpec(SolarEvent.SUNRISE, 0)).copy(precision = TimePrecision.EXACT)))
    }

    @Test
    fun `a located solar trigger is valid`() {
        assertEquals(emptyList(), errors(solar(SolarSpec(SolarEvent.SUNSET, 15, lat = 45.5, lng = 9.2))))
        assertEquals(emptyList(), errors(solar(SolarSpec(SolarEvent.SUNSET, 0, lat = -89.9, lng = -179.9))))
    }

    @Test
    fun `the offset is limited to twelve hours before or after`() {
        assertEquals(emptyList(), errors(solar(SolarSpec(SolarEvent.SUNSET, 720))))
        assertEquals(emptyList(), errors(solar(SolarSpec(SolarEvent.SUNSET, -720))))
        assertTrue("solar_offset_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, 721))))
        assertTrue("solar_offset_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, -721))))
        assertTrue("solar_offset_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, Int.MAX_VALUE))))
    }

    @Test
    fun `the position is both coordinates in range or none`() {
        assertTrue("solar_location_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, 0, lat = 45.0))))
        assertTrue("solar_location_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, 0, lng = 9.0))))
        assertTrue("solar_location_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, 0, 91.0, 9.0))))
        assertTrue("solar_location_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, 0, 45.0, -181.0))))
        assertTrue("solar_location_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, 0, Double.NaN, 9.0))))
        assertTrue(
            "solar_location_invalid" in errors(solar(SolarSpec(SolarEvent.SUNSET, 0, 45.0, Double.POSITIVE_INFINITY))),
        )
    }

    @Test
    fun `exactly one of cron at afterMs and solar`() {
        val spec = SolarSpec(SolarEvent.SUNSET, 0)
        assertTrue("time_spec" in errors(Trigger.Time(cron = "0 9 * * *", tz = "UTC", solar = spec)))
        assertTrue("time_spec" in errors(Trigger.Time(at = "2099-01-01T10:00", tz = "UTC", solar = spec)))
        assertTrue("time_spec" in errors(Trigger.Time(afterMs = 60_000, tz = "UTC", solar = spec)))
        assertTrue("time_spec" in errors(Trigger.Time(tz = "UTC")))
        assertEquals(emptyList(), errors(Trigger.Time(tz = "UTC", solar = spec)))
    }

    @Test
    fun `the time zone of a solar trigger still has to be valid`() {
        assertTrue("tz_invalid" in errors(solar(SolarSpec(SolarEvent.SUNRISE, 0), tz = "Not/AZone")))
    }
}
