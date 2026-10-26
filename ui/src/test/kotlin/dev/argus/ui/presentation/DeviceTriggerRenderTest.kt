package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

/** L'approvazione dei trigger di sistema (0.4.1) è resa dai tipi, in inglese e in italiano. */
class DeviceTriggerRenderTest {
    private fun line(trigger: Trigger, language: RenderLanguage): String = RuleRenderMapper.map(
        Automation(
            AutomationId("r"),
            "regola",
            CreatedBy.LLM,
            AutomationStatus.PENDING_APPROVAL,
            trigger,
            listOf(Action.SetWifi(false)),
        ),
        language = language,
    ).triggerLine

    @Test
    fun `screen`() {
        assertEquals("When: the screen turns on", line(Trigger.Screen(ScreenEvent.ON), RenderLanguage.EN))
        assertEquals("Quando: lo schermo si spegne", line(Trigger.Screen(ScreenEvent.OFF), RenderLanguage.IT))
        assertEquals(
            "Quando: il telefono viene sbloccato",
            line(Trigger.Screen(ScreenEvent.UNLOCKED), RenderLanguage.IT),
        )
    }

    @Test
    fun `battery shows the threshold and its direction`() {
        assertEquals(
            "When: the battery drops below 20% (once each time it crosses)",
            line(Trigger.Battery(below = 20), RenderLanguage.EN),
        )
        assertEquals(
            "Quando: la batteria sale sopra il 80% (una volta a ogni attraversamento)",
            line(Trigger.Battery(above = 80), RenderLanguage.IT),
        )
    }

    @Test
    fun `audio output shows kind and state`() {
        assertEquals(
            "When: wired audio output connected",
            line(Trigger.AudioOutput(AudioOutputKind.WIRED, ConnState.CONNECTED), RenderLanguage.EN),
        )
        assertEquals(
            "Quando: uscita audio Bluetooth scollegata",
            line(Trigger.AudioOutput(AudioOutputKind.BLUETOOTH, ConnState.DISCONNECTED), RenderLanguage.IT),
        )
        assertEquals(
            "When: any personal audio output (wired or Bluetooth) disconnected",
            line(Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.DISCONNECTED), RenderLanguage.EN),
        )
    }

    @Test
    fun `mode change shows the mode and the expected value`() {
        assertEquals(
            "When: Do Not Disturb changes to priority only",
            line(Trigger.ModeChanged(ModeKind.DND, "priority"), RenderLanguage.EN),
        )
        assertEquals(
            "Quando: la suoneria passa a silenzioso",
            line(Trigger.ModeChanged(ModeKind.RINGER, "silent"), RenderLanguage.IT),
        )
        assertEquals(
            "When: the ringer mode changes",
            line(Trigger.ModeChanged(ModeKind.RINGER), RenderLanguage.EN),
        )
    }

    @Test
    fun `media`() {
        assertEquals(
            "When: media starts playing",
            line(Trigger.Media(MediaPlayState.PLAYING), RenderLanguage.EN),
        )
        assertEquals(
            "Quando: finisce la riproduzione multimediale",
            line(Trigger.Media(MediaPlayState.STOPPED), RenderLanguage.IT),
        )
    }

    @Test
    fun `missed call and the sim filter stay visible`() {
        assertEquals(
            "When: missed call from anyone · SIM 2",
            line(Trigger.PhoneState(PhoneEvent.MISSED_CALL, sim = 2), RenderLanguage.EN),
        )
        assertEquals(
            "Quando: SMS ricevuto da chiunque · SIM 1",
            line(Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, sim = 1), RenderLanguage.IT),
        )
        assertEquals(
            "Quando: chiamata persa da +39333",
            line(Trigger.PhoneState(PhoneEvent.MISSED_CALL, number = "+39333"), RenderLanguage.IT),
        )
    }
}
