package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleRender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleRenderDiffTest {
    private fun row(label: String, detail: String? = null, shell: String? = null) = ActionRow(
        iconKey = "bolt",
        label = label,
        detail = detail,
        isShell = shell != null,
        shellCommand = shell,
        isGenerative = false,
        requiresLiveConfirm = false,
    )

    private fun render(
        trigger: String = "When: every day at 08:00",
        conditions: List<String> = emptyList(),
        actions: List<ActionRow> = listOf(row("Turn Wi-Fi on")),
        cooldown: String? = null,
    ) = RuleRender(
        triggerLine = trigger,
        triggerIconKey = "time",
        conditionLines = conditions,
        actions = actions,
        isGenerative = false,
        privacyNote = null,
        cooldownLine = cooldown,
    )

    private fun armed(name: String, trigger: Trigger, actions: List<Action>, conditions: Condition? = null) =
        Automation(AutomationId(name), name, CreatedBy.LLM, AutomationStatus.ARMED, trigger, actions, conditions = conditions)

    @Test
    fun `the same rule has no changes`() {
        assertEquals(emptyList(), RuleRenderDiff.diff(render(), render()))
    }

    @Test
    fun `a different name or description is not an executable change`() {
        val trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
        val before = RuleRenderMapper.map(armed("Old name", trigger, listOf(Action.SetWifi(true))), language = RenderLanguage.EN)
        val after = RuleRenderMapper.mapDraft(
            dev.argus.engine.model.AutomationDraft("New name", trigger, listOf(Action.SetWifi(true)), rationale = "new prose"),
            language = RenderLanguage.EN,
        )
        assertEquals(emptyList(), RuleRenderDiff.diff(before, after))
    }

    @Test
    fun `a changed value shows the old and the new row`() {
        val trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
        val before = RuleRenderMapper.map(armed("r", trigger, listOf(Action.SetDnd(DndMode.PRIORITY))), language = RenderLanguage.EN)
        val after = RuleRenderMapper.map(armed("r", trigger, listOf(Action.SetDnd(DndMode.TOTAL))), language = RenderLanguage.EN)

        val change = RuleRenderDiff.diff(before, after).single()

        assertEquals(RuleSection.ACTIONS, change.section)
        assertEquals(RuleChangeKind.CHANGED, change.kind)
        assertEquals(RuleRenderDiff.lines(before).getValue(RuleSection.ACTIONS).single(), change.old)
        assertEquals(RuleRenderDiff.lines(after).getValue(RuleSection.ACTIONS).single(), change.new)
        assertTrue(change.old != change.new)
    }

    @Test
    fun `a changed trigger is one changed row in the trigger section`() {
        val change = RuleRenderDiff.diff(render(), render(trigger = "When: every day at 09:00")).single()

        assertEquals(RuleChangeRow(
            RuleSection.TRIGGER,
            RuleChangeKind.CHANGED,
            RenderLine("When: every day at 08:00"),
            RenderLine("When: every day at 09:00"),
        ), change)
    }

    @Test
    fun `an action inserted in the middle is only added, the others stay unchanged`() {
        val before = render(actions = listOf(row("A"), row("B"), row("C")))
        val after = render(actions = listOf(row("A"), row("X"), row("B"), row("C")))

        assertEquals(
            listOf(RuleChangeRow(RuleSection.ACTIONS, RuleChangeKind.ADDED, null, RenderLine("X"))),
            RuleRenderDiff.diff(before, after),
        )
    }

    @Test
    fun `a removed condition is removed, a new cooldown is added`() {
        val before = render(conditions = listOf("Only between 22:00 and 07:00", "Only if Wi-Fi is on"))
        val after = render(conditions = listOf("Only between 22:00 and 07:00"), cooldown = "After it fires, it ignores new events for 1 hour")

        assertEquals(
            listOf(
                RuleChangeRow(
                    RuleSection.COOLDOWN,
                    RuleChangeKind.ADDED,
                    null,
                    RenderLine("After it fires, it ignores new events for 1 hour"),
                ),
                RuleChangeRow(RuleSection.CONDITIONS, RuleChangeKind.REMOVED, RenderLine("Only if Wi-Fi is on"), null),
            ),
            RuleRenderDiff.diff(before, after),
        )
    }

    @Test
    fun `rows come in the order of the rule and replaced runs pair up before the leftovers`() {
        val before = render(actions = listOf(row("A"), row("B"), row("C")))
        val after = render(trigger = "When: at sunset", actions = listOf(row("A"), row("Y")))

        val changes = RuleRenderDiff.diff(before, after)

        assertEquals(
            listOf(
                RuleChangeRow(RuleSection.TRIGGER, RuleChangeKind.CHANGED, RenderLine("When: every day at 08:00"), RenderLine("When: at sunset")),
                RuleChangeRow(RuleSection.ACTIONS, RuleChangeKind.CHANGED, RenderLine("B"), RenderLine("Y")),
                RuleChangeRow(RuleSection.ACTIONS, RuleChangeKind.REMOVED, RenderLine("C"), null),
            ),
            changes,
        )
    }

    @Test
    fun `a shell command change keeps both commands whole`() {
        val long = "settings put global some_key " + "x".repeat(500)
        val before = render(actions = listOf(row("Run command", shell = "echo old")))
        val after = render(actions = listOf(row("Run command", shell = long)))

        val change = RuleRenderDiff.diff(before, after).single()

        assertEquals(RuleChangeKind.CHANGED, change.kind)
        assertEquals("echo old", change.old?.shellCommand)
        assertEquals(long, change.new?.shellCommand)
    }

    @Test
    fun `an action moved inside a block counts as a change of the program`() {
        val leaf = row("Show notification", detail = "Argus: hi")
        val before = render(actions = emptyList()).copy(program = listOf(ProgramNode.Leaf(leaf)))
        val after = render(actions = emptyList()).copy(
            program = listOf(
                ProgramNode.IfNode(
                    title = "If:",
                    conditionLines = listOf("Wi-Fi is on"),
                    thenTitle = "Then:",
                    then = listOf(ProgramNode.Leaf(leaf)),
                    elseTitle = null,
                    orElse = emptyList(),
                ),
            ),
        )

        val changes = RuleRenderDiff.diff(before, after)

        assertEquals(
            listOf(
                RuleChangeRow(
                    RuleSection.ACTIONS,
                    RuleChangeKind.CHANGED,
                    RenderLine("Show notification · Argus: hi", depth = 0),
                    RenderLine("If: Wi-Fi is on", depth = 0),
                ),
                RuleChangeRow(
                    RuleSection.ACTIONS,
                    RuleChangeKind.ADDED,
                    null,
                    RenderLine("Show notification · Argus: hi", depth = 1),
                ),
            ),
            changes,
        )
    }

    @Test
    fun `conditions of a real rule are compared line by line`() {
        val trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
        val before = RuleRenderMapper.map(
            armed("r", trigger, listOf(Action.SetWifi(true)), Condition.StateEquals("wifi", CmpOp.EQ, "off")),
            language = RenderLanguage.EN,
        )
        val after = RuleRenderMapper.map(armed("r", trigger, listOf(Action.SetWifi(true))), language = RenderLanguage.EN)

        val change = RuleRenderDiff.diff(before, after).single()

        assertEquals(RuleSection.CONDITIONS, change.section)
        assertEquals(RuleChangeKind.REMOVED, change.kind)
    }
}
