package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

/** 0.8: l'approvazione di una geofence DWELL mostra intera l'attesa, in inglese e in italiano. */
class GeofenceDwellRenderTest {
    private fun line(trigger: Trigger, language: RenderLanguage) = RuleRenderMapper.map(
        Automation(
            AutomationId("d"),
            "a casa da un po'",
            CreatedBy.LLM,
            AutomationStatus.PENDING_APPROVAL,
            trigger,
            listOf(Action.SetWifi(true)),
        ),
        language = language,
    ).triggerLine

    @Test
    fun `a dwell shows the place and how long the phone must stay`() {
        val here = Trigger.Geofence(
            radiusM = 150.0,
            transition = Transition.DWELL,
            loiteringDelayMs = 90 * 60_000L,
            resolveCurrentLocation = true,
        )
        assertEquals(
            "When you have been at the current location (±150 m) for 1 hour and 30 minutes",
            line(here, RenderLanguage.EN),
        )
        assertEquals("Quando sei nella posizione attuale (±150 m) da 1 ora e 30 minuti", line(here, RenderLanguage.IT))

        val place = here.copy(lat = 45.4642, lng = 9.19, resolveCurrentLocation = false, loiteringDelayMs = 600_000L)
        assertEquals("Quando sei in 45.4642,9.19 (±150 m) da 10 minuti", line(place, RenderLanguage.IT))
    }
}
