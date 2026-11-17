package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.provenance
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Il titolo dell'evento di calendario come payload del trigger: dato esterno. */
class CalendarPayloadTest {
    private val trigger = Trigger.CalendarEvent(CalendarPhase.START)
    private val binding = VarBinding.TriggerPayload("titolo", TriggerField.TITLE, confidentiality = ConfidentialityLabel.PRIVATE)

    private fun fired(title: String?) = TriggerEvent.CalendarEventFired(
        AutomationId("a"),
        CalendarPhase.START,
        ApprovalFingerprint("f".repeat(64)),
        title = title,
    )

    private fun interpreter(seen: MutableList<ResolvedProgramAction>) = ProgramInterpreter(
        runner = ProgramActionRunner { action, _ ->
            seen += action
            ProgramActionResult(ActionResult.Success)
        },
        stateProvider = { DeviceState() },
        conditionEvaluator = ConditionEvaluator(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
        pause = {},
    )

    @Test
    fun `the event title reaches the actions as tainted calendar data`() = runTest {
        val seen = mutableListOf<ResolvedProgramAction>()
        val result = interpreter(seen).execute(
            trigger,
            fired("Dentista"),
            listOf(binding),
            listOf(Action.ShowNotification("Agenda", "Ora: \${titolo}")),
        )
        assertEquals(true, result.completed)
        assertEquals("Ora: Dentista", assertIs<Action.ShowNotification>(seen.single().action).text)
        assertEquals(IntegrityLabel.TAINTED, seen.single().inputIntegrity)
        assertEquals(setOf(ValueProvenance.CALENDAR), binding.provenance(trigger))
    }

    @Test
    fun `an event without a title has no payload`() = runTest {
        val seen = mutableListOf<ResolvedProgramAction>()
        val result = interpreter(seen).execute(
            trigger,
            fired(null),
            listOf(binding),
            listOf(Action.ShowNotification("Agenda", "Ora: \${titolo}")),
        )
        assertFalse(result.completed)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `the fired event never prints the title`() {
        assertFalse("Dentista" in fired("Dentista").toString())
    }
}
