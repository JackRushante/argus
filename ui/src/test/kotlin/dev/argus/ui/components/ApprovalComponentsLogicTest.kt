package dev.argus.ui.components

import dev.argus.engine.safety.Severity
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.AppChoiceUi
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.UiWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalComponentsLogicTest {
    private fun leaf(label: String) =
        ProgramNode.Leaf(ActionRow("notify", label, null, false, null, false, false))

    private fun rule(program: List<ProgramNode>, actions: List<ActionRow> = emptyList()) = RuleRender(
        triggerLine = "Quando: ora",
        triggerIconKey = "time",
        conditionLines = emptyList(),
        actions = actions,
        isGenerative = false,
        privacyNote = null,
        program = program,
    )

    @Test
    fun `risk counts separate the errors from the warnings`() {
        val warnings = listOf(
            UiWarning(Severity.ERROR, "a", "x"),
            UiWarning(Severity.WARNING, "b", "y"),
            UiWarning(Severity.WARNING, "c", "z"),
        )

        assertEquals(RiskCounts(errors = 1, warnings = 2), riskCounts(warnings))
        assertEquals(RiskCounts(0, 0), riskCounts(emptyList()))
    }

    @Test
    fun `an error never shares its icon with a warning, and each kind of warning has its own`() {
        val error = riskIcon(UiWarning(Severity.ERROR, "x", "t"))
        val privacy = riskIcon(UiWarning(Severity.WARNING, "privacy_generative", "t"))
        val missing = riskIcon(UiWarning(Severity.WARNING, "app_not_installed", "t"))
        val generic = riskIcon(UiWarning(Severity.WARNING, "radius_small", "t"))

        assertEquals(4, setOf(error, privacy, missing, generic).size)
        assertEquals(privacy, riskIcon(UiWarning(Severity.WARNING, "read_plus_reply", "t")))
    }

    @Test
    fun `the trigger text drops the when prefix in both languages`() {
        assertEquals("notifica da Moglie", triggerText("Quando: notifica da Moglie"))
        assertEquals("notification from Wife", triggerText("When: notification from Wife"))
        assertEquals("Regola salvata con uno schema precedente", triggerText("Regola salvata con uno schema precedente"))
    }

    @Test
    fun `the trigger container shape follows the kind of trigger`() {
        assertEquals(TriggerShape.Circle, triggerShapeFor("time"))
        assertEquals(TriggerShape.Squircle, triggerShapeFor("notification"))
        assertEquals(TriggerShape.Drop, triggerShapeFor("geofence"))
        assertEquals(TriggerShape.Rhombus, triggerShapeFor("phone"))
        assertEquals(TriggerShape.Arch, triggerShapeFor("connectivity"))
        assertEquals(TriggerShape.Cookie, triggerShapeFor("sensor"))
    }

    @Test
    fun `a hand built rule without a program falls back to its flat actions`() {
        val row = ActionRow("notify", "Mostra notifica", null, false, null, false, false)

        assertEquals(listOf(ProgramNode.Leaf(row)), programOf(rule(program = emptyList(), actions = listOf(row))))
    }

    @Test
    fun `a program with an if or a repeat is a full program`() {
        val nodes = listOf(
            leaf("a"),
            ProgramNode.WhileNode("Ripeti", emptyList(), listOf(leaf("b"))),
        )

        assertTrue(hasControlFlow(nodes))
        assertFalse(hasControlFlow(listOf(leaf("a"), leaf("b"))))
    }

    @Test
    fun `the app list filters by name or package without caring about case`() {
        val apps = listOf(
            AppChoiceUi("com.android.chrome", "Chrome"),
            AppChoiceUi("com.google.android.youtube", "YouTube"),
            AppChoiceUi("org.mozilla.firefox", "Firefox"),
        )

        assertEquals(apps, filterAppChoices(apps, ""))
        assertEquals(apps, filterAppChoices(apps, "   "))
        assertEquals(listOf(apps[1]), filterAppChoices(apps, "tube"))
        assertEquals(listOf(apps[2]), filterAppChoices(apps, "MOZILLA"))
        assertEquals(emptyList(), filterAppChoices(apps, "nessuna"))
    }
}
