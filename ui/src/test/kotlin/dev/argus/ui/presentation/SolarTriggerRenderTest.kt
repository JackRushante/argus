package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.SolarEvent
import dev.argus.engine.model.SolarSpec
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

/** L'approvazione del trigger solare (0.4.1, F9) è resa dai tipi, in inglese e in italiano. */
class SolarTriggerRenderTest {
    private fun line(
        spec: SolarSpec,
        language: RenderLanguage,
        precision: TimePrecision = TimePrecision.FLEXIBLE,
    ): String = RuleRenderMapper.map(
        Automation(
            AutomationId("r"),
            "regola",
            CreatedBy.LLM,
            AutomationStatus.PENDING_APPROVAL,
            Trigger.Time(tz = "Europe/Rome", precision = precision, solar = spec),
            listOf(Action.SetFlashlight(true)),
        ),
        language = language,
    ).triggerLine

    private val here = "at the phone's position, read when you activate the rule"
    private val qui = "alla posizione del telefono, letta quando attivi la regola"

    @Test
    fun `thirty minutes before sunset`() {
        val spec = SolarSpec(SolarEvent.SUNSET, -30)
        assertEquals("Every day, 30 min before sunset · $here", line(spec, RenderLanguage.EN))
        assertEquals("Ogni giorno, 30 min prima del tramonto · $qui", line(spec, RenderLanguage.IT))
    }

    @Test
    fun `after sunrise and exactly at sunrise or sunset`() {
        assertEquals(
            "Every day, 1 h 30 min after sunrise · $here",
            line(SolarSpec(SolarEvent.SUNRISE, 90), RenderLanguage.EN),
        )
        assertEquals(
            "Ogni giorno, 2 h dopo l'alba · $qui",
            line(SolarSpec(SolarEvent.SUNRISE, 120), RenderLanguage.IT),
        )
        assertEquals("Every day at sunrise · $here", line(SolarSpec(SolarEvent.SUNRISE, 0), RenderLanguage.EN))
        assertEquals("Ogni giorno al tramonto · $qui", line(SolarSpec(SolarEvent.SUNSET, 0), RenderLanguage.IT))
        assertEquals(
            "Every day, 12 h before sunrise · $here",
            line(SolarSpec(SolarEvent.SUNRISE, -720), RenderLanguage.EN),
        )
        assertEquals(
            "Ogni giorno, 1 min dopo il tramonto · $qui",
            line(SolarSpec(SolarEvent.SUNSET, 1), RenderLanguage.IT),
        )
    }

    @Test
    fun `an armed rule shows the frozen position`() {
        val spec = SolarSpec(SolarEvent.SUNSET, -30, lat = 45.5, lng = -9.0)
        assertEquals("Every day, 30 min before sunset · near 45.5, -9.0", line(spec, RenderLanguage.EN))
        assertEquals("Ogni giorno, 30 min prima del tramonto · vicino a 45.5, -9.0", line(spec, RenderLanguage.IT))
    }

    @Test
    fun `exact precision is still declared`() {
        assertEquals(
            "Every day at sunset · $here · exact time",
            line(SolarSpec(SolarEvent.SUNSET, 0), RenderLanguage.EN, TimePrecision.EXACT),
        )
    }
}
