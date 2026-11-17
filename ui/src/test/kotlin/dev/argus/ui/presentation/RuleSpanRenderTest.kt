package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VolumeStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 0.8: una regola-intervallo si approva come una regola sola. La fine elenca, dai tipi delle azioni d'inizio, i
 * valori che rimette, e dice che un valore cambiato a mano nel frattempo torna com'era.
 */
class RuleSpanRenderTest {
    private val car = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED, "Auto")
    private val carOff = Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED, "Auto")

    private fun rule(actions: List<Action>, exit: ExitSpec) = Automation(
        AutomationId("span"), "In auto", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL, car, actions, exit = exit,
    )

    @Test
    fun `the end lists the values it puts back, from the types, once each`() {
        val actions = listOf(
            Action.SetVolume(VolumeStream.MEDIA, 100),
            Action.If(Condition.BooleanLiteral(true), then = listOf(Action.SetDnd(DndMode.PRIORITY))),
            Action.SetVolume(VolumeStream.MEDIA, 90),
            Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "600000"),
            Action.Vibrate(100),
        )
        val en = assertNotNull(RuleRenderMapper.map(rule(actions, ExitSpec(carOff)), language = RenderLanguage.EN).exitLine)
        assertTrue(en.startsWith("Until this happens: "), en)
        assertTrue("(media volume, Do Not Disturb, the setting \"screen_off_timeout\")" in en, en)
        assertTrue("A value you change by hand in the meantime is still put back as it was" in en, en)
        assertFalse("end actions" in en, en)

        val it = assertNotNull(RuleRenderMapper.map(rule(actions, ExitSpec(carOff)), language = RenderLanguage.IT).exitLine)
        assertTrue(it.startsWith("Finché non succede questo: "), it)
        assertTrue("Alla fine: rimette com'era" in it, it)
        assertTrue("(volume multimediale, Non disturbare, l'impostazione \"screen_off_timeout\")" in it, it)
        assertTrue("Anche un valore cambiato a mano nel frattempo torna com'era" in it, it)
    }

    @Test
    fun `an end without restore says so, and its actions are shown below`() {
        val render = RuleRenderMapper.map(
            rule(listOf(Action.SetWifi(true)), ExitSpec(carOff, restore = false, actions = listOf(Action.SetWifi(false)))),
            language = RenderLanguage.IT,
        )
        val line = assertNotNull(render.exitLine)
        assertTrue("Alla fine: non rimette i valori di prima" in line, line)
        assertTrue("poi esegue le azioni di fine qui sotto" in line, line)
        assertEquals(1, render.exitProgram.size)
    }

    @Test
    fun `an AI action at the end makes the rule generative, with its privacy note`() {
        val ai = Action.InvokeLlm(
            goal = "riassumi la giornata",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
            notificationTitle = "Oggi",
        )
        val render = RuleRenderMapper.map(
            rule(listOf(Action.SetWifi(true)), ExitSpec(carOff, actions = listOf(ai))),
            language = RenderLanguage.EN,
        )
        assertTrue(render.isGenerative)
        assertNotNull(render.privacyNote)
        assertNotNull(render.cooldownLine, "the generative minimum pause is shown")
    }
}
