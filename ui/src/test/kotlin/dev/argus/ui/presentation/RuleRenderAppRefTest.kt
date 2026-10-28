package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Trigger
import dev.argus.ui.model.AppRef
import dev.argus.ui.model.ProgramNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Cambia app»: il render assegna a ogni package sostituibile lo stesso slot di `AppPackages`. */
class RuleRenderAppRefTest {
    private val youtube = "com.google.android.youtube"

    private val draft = AutomationDraft(
        name = "Apri e ferma",
        trigger = Trigger.AppEvent(youtube, AppEventKind.OPENED),
        actions = listOf(
            Action.LaunchApp("com.android.chrome"),
            Action.If(
                condition = Condition.VarCompare("n", CmpOp.GT, expected = "1"),
                then = listOf(Action.AppControl("com.example.game", AppControlOp.FORCE_STOP)),
                orElse = listOf(Action.SetWifi(false)),
            ),
        ),
    )

    @Test
    fun `the app event trigger carries slot zero`() {
        val rule = RuleRenderMapper.mapDraft(draft, language = RenderLanguage.IT)

        assertEquals(AppRef(0, youtube), rule.triggerAppRef)
    }

    @Test
    fun `leaf actions carry the slot of their package, nested ones included`() {
        val rule = RuleRenderMapper.mapDraft(draft, language = RenderLanguage.IT)

        val launch = rule.program[0] as ProgramNode.Leaf
        assertEquals(AppRef(1, "com.android.chrome"), launch.row.appRef)
        val branch = rule.program[1] as ProgramNode.IfNode
        assertEquals(AppRef(2, "com.example.game"), (branch.then[0] as ProgramNode.Leaf).row.appRef)
        assertNull((branch.orElse[0] as ProgramNode.Leaf).row.appRef)
    }

    @Test
    fun `the flat action list carries the same slots`() {
        val rule = RuleRenderMapper.mapDraft(draft, language = RenderLanguage.EN)

        assertEquals(AppRef(1, "com.android.chrome"), rule.actions[0].appRef)
        assertNull(rule.actions[1].appRef)
    }

    @Test
    fun `a rule without app packages has no app refs`() {
        val rule = RuleRenderMapper.mapDraft(
            AutomationDraft("Wi-Fi", Trigger.Immediate, listOf(Action.SetWifi(false))),
            language = RenderLanguage.IT,
        )

        assertNull(rule.triggerAppRef)
        assertNull(rule.actions[0].appRef)
    }

    @Test
    fun `a placeholder package is not offered for change`() {
        val rule = RuleRenderMapper.mapDraft(
            AutomationDraft("x", Trigger.Immediate, listOf(Action.LaunchApp("\${pkg}"))),
            language = RenderLanguage.IT,
        )

        assertNull(rule.actions[0].appRef)
    }
}
