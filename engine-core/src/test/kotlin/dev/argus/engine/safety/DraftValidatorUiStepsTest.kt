package dev.argus.engine.safety

import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Validazione di `ui_steps` (0.5.1): limiti, package vietati, segnaposto e trigger. */
class DraftValidatorUiStepsTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    private fun codes(issues: List<ValidationIssue>, severity: Severity) =
        issues.filter { it.severity == severity }.map { it.code }
    private fun errors(issues: List<ValidationIssue>) = codes(issues, Severity.ERROR)
    private fun warnings(issues: List<ValidationIssue>) = codes(issues, Severity.WARNING)

    private val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
    private fun draft(
        vararg actions: Action,
        vars: List<VarBinding> = emptyList(),
        trigger: Trigger = time,
    ) = AutomationDraft(name = "r", trigger = trigger, actions = actions.toList(), vars = vars)

    private fun macro(pkg: String? = "com.example.shop", vararg steps: UiStep) =
        Action.UiSteps(pkg, steps.toList().ifEmpty { listOf(UiStep.Click(text = "OK")) })

    @Test
    fun `a well formed macro is valid and always carries the acts-as-the-user warning`() {
        val issues = v.validate(
            draft(
                macro(
                    "com.example.shop",
                    UiStep.WaitFor(text = "Cerca", timeoutMs = 5_000),
                    UiStep.Click(id = "buy"),
                    UiStep.Check("Fatto", present = true),
                ),
            ),
            emptySet(),
        )
        assertEquals(emptyList(), errors(issues))
        assertEquals(listOf("ui_steps_review"), warnings(issues))
    }

    @Test
    fun `a macro on the current screen needs no package`() {
        assertEquals(emptyList(), errors(v.validate(draft(macro(null, UiStep.Back)), emptySet())))
    }

    @Test
    fun `no steps and more than 20 steps are errors`() {
        assertTrue("ui_steps_empty" in errors(v.validate(draft(Action.UiSteps(null, emptyList())), emptySet())))
        val twenty = Action.UiSteps(null, List(20) { UiStep.Back })
        assertEquals(emptyList(), errors(v.validate(draft(twenty), emptySet())))
        val twentyOne = Action.UiSteps(null, List(21) { UiStep.Back })
        assertTrue("ui_steps_too_many" in errors(v.validate(draft(twentyOne), emptySet())))
    }

    @Test
    fun `the package is a literal android package and never one of the forbidden screens`() {
        assertTrue("ui_pkg_invalid" in errors(v.validate(draft(macro("not a package")), emptySet())))
        assertTrue("ui_pkg_invalid" in errors(v.validate(draft(macro("com.example.\${x}")), emptySet())))
        listOf(
            "dev.argus",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        ).forEach { pkg ->
            assertTrue("ui_pkg_forbidden" in errors(v.validate(draft(macro(pkg)), emptySet())), pkg)
        }
    }

    @Test
    fun `every step respects its limits`() {
        fun bad(step: UiStep) = errors(v.validate(draft(macro("com.example.shop", step)), emptySet()))
        assertTrue("ui_step_invalid" in bad(UiStep.Click()))
        assertTrue("ui_step_invalid" in bad(UiStep.Click(text = "OK", index = 99)))
        assertTrue("ui_step_invalid" in bad(UiStep.WaitFor(text = "x", timeoutMs = 20_000)))
        assertTrue("ui_step_invalid" in bad(UiStep.WaitFor(timeoutMs = 1_000)))
        assertTrue("ui_step_invalid" in bad(UiStep.Type("")))
        assertTrue("ui_step_invalid" in bad(UiStep.Check("", true)))
        assertEquals(emptyList(), bad(UiStep.WaitFor(text = "x", timeoutMs = 15_000)))
    }

    @Test
    fun `only the type step takes a placeholder`() {
        val vars = listOf(VarBinding.TriggerPayload("sms", TriggerField.TEXT, null, ConfidentialityLabel.PRIVATE))
        fun with(step: UiStep) = errors(
            v.validate(draft(macro("com.example.shop", step), vars = vars, trigger = Trigger.Immediate), emptySet()),
        )
        assertEquals(emptyList(), with(UiStep.Type("Ciao \${sms}")))
        assertTrue("ui_step_interpolated" in with(UiStep.Click(text = "\${sms}")))
        assertTrue("ui_step_interpolated" in with(UiStep.WaitFor(id = "\${sms}")))
        assertTrue("ui_step_interpolated" in with(UiStep.Check("\${sms}", true)))
    }

    @Test
    fun `a type step with an undeclared variable is an error like any sink`() {
        assertTrue(
            "interpolation_undeclared_var" in
                errors(v.validate(draft(macro("com.example.shop", UiStep.Type("\${nope}"))), emptySet())),
        )
    }

    @Test
    fun `triggers that anyone can cause cannot start a macro`() {
        val m = macro("com.example.shop", UiStep.Click(text = "OK"))
        listOf<Trigger>(
            Trigger.PhoneState(PhoneEvent.SMS_RECEIVED),
            Trigger.PhoneState(PhoneEvent.INCOMING_CALL),
            Trigger.Media(MediaPlayState.PLAYING),
        ).forEach { trigger ->
            assertTrue(
                "ui_steps_external_trigger" in errors(v.validate(draft(m, trigger = trigger), emptySet())),
                trigger.toString(),
            )
        }
        listOf<Trigger>(
            time,
            Trigger.Immediate,
            Trigger.Manual,
            Trigger.Screen(ScreenEvent.ON),
        ).forEach { trigger ->
            assertFalse(
                "ui_steps_external_trigger" in errors(v.validate(draft(m, trigger = trigger), emptySet())),
                trigger.toString(),
            )
        }
    }

    @Test
    fun `a whitelisted whatsapp chat can start a macro like it can start a shell`() {
        val m = macro("com.example.shop", UiStep.Click(text = "OK"))
        val chat = Trigger.Notification(
            pkg = "com.whatsapp",
            conversationId = "jid:42",
            isGroup = false,
        )
        assertFalse(
            "ui_steps_external_trigger" in errors(v.validate(draft(m, trigger = chat), setOf("jid:42"))),
        )
        assertTrue(
            "ui_steps_external_trigger" in errors(v.validate(draft(m, trigger = chat), emptySet())),
        )
    }

    @Test
    fun `a nested macro is validated too`() {
        val nested = Action.If(Condition.BooleanLiteral(true), then = listOf(macro("dev.argus")))
        assertTrue("ui_pkg_forbidden" in errors(v.validate(draft(nested), emptySet())))
    }

    @Test
    fun `the macro time counts against the six hour budget`() {
        val m = macro("com.example.shop", UiStep.WaitFor(text = "x", timeoutMs = 15_000))
        val loop = Action.While(
            condition = Condition.BooleanLiteral(true),
            body = listOf(m),
            maxIterations = 1_000,
        )
        // 1000 giri da 25 s superano le 6 ore.
        assertTrue("time_budget_exceeded" in errors(v.validate(draft(loop), emptySet())))
    }
}
