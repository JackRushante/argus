package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.ConfidentialityLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validatore dei trigger di sistema senza Shizuku (0.4.1). */
class DraftValidatorSystemTriggersTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))

    private fun errors(trigger: Trigger, actions: List<Action> = listOf(Action.SetWifi(false))) =
        v.validate(AutomationDraft(name = "regola", trigger = trigger, actions = actions), emptySet())
            .filter { it.severity == Severity.ERROR }
            .map { it.code }

    @Test
    fun `screen audio output and media have nothing to validate`() {
        assertEquals(emptyList(), errors(Trigger.Screen(ScreenEvent.ON)))
        assertEquals(emptyList(), errors(Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED)))
        assertEquals(emptyList(), errors(Trigger.Media(MediaPlayState.PLAYING)))
    }

    @Test
    fun `battery needs exactly one threshold between 1 and 99`() {
        assertEquals(emptyList(), errors(Trigger.Battery(below = 20)))
        assertEquals(emptyList(), errors(Trigger.Battery(above = 99)))
        assertEquals(emptyList(), errors(Trigger.Battery(below = 1)))
        assertTrue("battery_spec" in errors(Trigger.Battery()))
        assertTrue("battery_spec" in errors(Trigger.Battery(below = 20, above = 80)))
        assertTrue("battery_threshold_invalid" in errors(Trigger.Battery(below = 0)))
        assertTrue("battery_threshold_invalid" in errors(Trigger.Battery(above = 100)))
        assertTrue("battery_threshold_invalid" in errors(Trigger.Battery(below = -5)))
    }

    @Test
    fun `mode changed accepts only the values of its own mode`() {
        assertEquals(emptyList(), errors(Trigger.ModeChanged(ModeKind.DND, "priority")))
        assertEquals(emptyList(), errors(Trigger.ModeChanged(ModeKind.RINGER, "silent")))
        assertEquals(emptyList(), errors(Trigger.ModeChanged(ModeKind.RINGER)))
        assertTrue("mode_to_invalid" in errors(Trigger.ModeChanged(ModeKind.DND, "silent")))
        assertTrue("mode_to_invalid" in errors(Trigger.ModeChanged(ModeKind.RINGER, "total")))
        assertTrue("mode_to_invalid" in errors(Trigger.ModeChanged(ModeKind.RINGER, "")))
    }

    @Test
    fun `the sim filter accepts only slot 1 or 2 on any phone event`() {
        PhoneEvent.entries.forEach { event ->
            assertEquals(emptyList(), errors(Trigger.PhoneState(event, sim = 1)), event.name)
            assertEquals(emptyList(), errors(Trigger.PhoneState(event, sim = 2)), event.name)
            assertTrue("sim_invalid" in errors(Trigger.PhoneState(event, sim = 0)), event.name)
            assertTrue("sim_invalid" in errors(Trigger.PhoneState(event, sim = 3)), event.name)
        }
    }

    @Test
    fun `a missed call is a call event and takes no sms text filter`() {
        assertEquals(emptyList(), errors(Trigger.PhoneState(PhoneEvent.MISSED_CALL, number = "333")))
        assertTrue("sms_text_match_invalid" in errors(Trigger.PhoneState(PhoneEvent.MISSED_CALL, textMatch = "x")))
    }

    @Test
    fun `a missed call exposes the number to a program variable`() {
        val draft = AutomationDraft(
            name = "persa",
            trigger = Trigger.PhoneState(PhoneEvent.MISSED_CALL),
            actions = listOf(Action.ShowNotification("Persa", "Da \${chi}")),
            vars = listOf(
                VarBinding.TriggerPayload(
                    "chi",
                    TriggerField.NUMBER,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
            ),
        )
        assertEquals(
            emptyList(),
            v.validate(draft, emptySet()).filter { it.severity == Severity.ERROR }.map { it.code },
        )
    }

    @Test
    fun `autonomous shell is allowed from device triggers but not from media`() {
        val shell = listOf<Action>(Action.RunShell("id"))
        assertEquals(emptyList(), errors(Trigger.Screen(ScreenEvent.OFF), shell))
        assertEquals(emptyList(), errors(Trigger.Battery(below = 15), shell))
        assertEquals(emptyList(), errors(Trigger.AudioOutput(AudioOutputKind.WIRED, ConnState.CONNECTED), shell))
        assertEquals(emptyList(), errors(Trigger.ModeChanged(ModeKind.DND), shell))
        // Qualunque app o pagina web può far partire una riproduzione: non è un'identità fidata.
        assertTrue("shell_external_trigger" in errors(Trigger.Media(MediaPlayState.PLAYING), shell))
        // Mittente SMS e caller ID restano esclusi, anche per la chiamata persa.
        assertTrue("shell_external_trigger" in errors(Trigger.PhoneState(PhoneEvent.MISSED_CALL), shell))
    }
}
