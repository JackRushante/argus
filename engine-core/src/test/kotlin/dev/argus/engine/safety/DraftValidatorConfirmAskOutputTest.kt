package dev.argus.engine.safety

import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8: limiti di `confirm`, `ask` e `invoke_llm.output` nel validator, e le etichette delle loro catture. */
class DraftValidatorConfirmAskOutputTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "web.search"))

    private fun errors(d: AutomationDraft) =
        v.validate(d, emptySet()).filter { it.severity == Severity.ERROR }.map { it.code }

    private fun draft(
        actions: List<Action> = listOf(Action.Vibrate(100)),
        trigger: Trigger = Trigger.Manual,
        confirm: ConfirmSpec? = null,
    ) = AutomationDraft(name = "r", trigger = trigger, actions = actions, confirm = confirm)

    private fun ask(
        prompt: String = "Esco?",
        choices: List<String> = listOf("Sì", "No"),
        timeoutMs: Long = 120_000,
        defaultChoice: String? = null,
    ) = Action.Ask(prompt, choices, "answer", timeoutMs, defaultChoice)

    private fun llm(output: OutputSpec?, captureAs: String? = "verdict", deliver: GenerativeDeliverMode = GenerativeDeliverMode.CAPTURE_ONLY) =
        Action.InvokeLlm(
            goal = "È urgente?",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            deliver = deliver,
            notificationTitle = if (deliver == GenerativeDeliverMode.LOCAL_NOTIFICATION) "Esito" else null,
            captureAs = captureAs,
            output = output,
        )

    // --- confirm -------------------------------------------------------------------------------

    @Test fun `confirm is accepted between 30 seconds and 60 minutes`() {
        assertEquals(emptyList(), errors(draft(confirm = ConfirmSpec(30_000))))
        assertEquals(emptyList(), errors(draft(confirm = ConfirmSpec(3_600_000))))
        assertEquals(emptyList(), errors(draft(confirm = ConfirmSpec())))
        assertEquals(listOf("confirm_timeout_invalid"), errors(draft(confirm = ConfirmSpec(29_999))))
        assertEquals(listOf("confirm_timeout_invalid"), errors(draft(confirm = ConfirmSpec(3_600_001))))
    }

    @Test fun `confirm is refused on rules that switch themselves off after one fire`() {
        listOf(
            Trigger.Immediate,
            Trigger.Time(at = "2026-12-01T08:00", tz = "Europe/Rome"),
            Trigger.Time(afterMs = 600_000, tz = "Europe/Rome"),
        ).forEach { trigger ->
            assertTrue("confirm_one_shot_unsupported" in errors(draft(trigger = trigger, confirm = ConfirmSpec())), "$trigger")
        }
        val cron = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
        assertEquals(emptyList(), errors(draft(trigger = cron, confirm = ConfirmSpec())))
    }

    // --- ask -----------------------------------------------------------------------------------

    @Test fun `a well formed ask passes`() {
        assertEquals(emptyList(), errors(draft(actions = listOf(ask()))))
        assertEquals(emptyList(), errors(draft(actions = listOf(ask(choices = listOf("a", "b", "c", "d"), defaultChoice = "c")))))
        assertEquals(emptyList(), errors(draft(actions = listOf(ask(timeoutMs = 10_000)))))
        assertEquals(emptyList(), errors(draft(actions = listOf(ask(timeoutMs = 3_600_000)))))
    }

    @Test fun `ask choices are 2 to 4 unique short literals without control characters`() {
        listOf(
            listOf("solo"),
            listOf("a", "b", "c", "d", "e"),
            listOf("a", "a"),
            listOf("a", ""),
            listOf("a", " "),
            listOf("a", "x".repeat(25)),
            listOf("a", "b\nc"),
            listOf("a", "b‮c"),
            listOf("a", "\${x}"),
        ).forEach { choices ->
            assertEquals(listOf("ask_choices_invalid"), errors(draft(actions = listOf(ask(choices = choices)))), "$choices")
        }
        assertEquals(emptyList(), errors(draft(actions = listOf(ask(choices = listOf("a", "x".repeat(24)))))))
    }

    @Test fun `ask prompt, timeout and default are bounded`() {
        assertEquals(listOf("ask_prompt_invalid"), errors(draft(actions = listOf(ask(prompt = " ")))))
        assertEquals(listOf("ask_prompt_invalid"), errors(draft(actions = listOf(ask(prompt = "x".repeat(121))))))
        assertEquals(listOf("ask_prompt_invalid"), errors(draft(actions = listOf(ask(prompt = "a\u0007b")))))
        assertEquals(emptyList(), errors(draft(actions = listOf(ask(prompt = "x".repeat(120))))))
        assertEquals(listOf("ask_timeout_invalid"), errors(draft(actions = listOf(ask(timeoutMs = 9_999)))))
        assertEquals(listOf("ask_timeout_invalid"), errors(draft(actions = listOf(ask(timeoutMs = 3_600_001)))))
        assertEquals(listOf("ask_default_invalid"), errors(draft(actions = listOf(ask(defaultChoice = "Forse")))))
    }

    @Test fun `the ask answer is clean, so it may even name a shell command`() {
        // Un valore TAINTED nella posizione del comando è un errore (D1); la scelta dell'utente no.
        val d = draft(actions = listOf(ask(choices = listOf("true", "false")), Action.RunShell("\${answer}")))
        assertFalse("interpolation_shell_command_position" in errors(d))
        val compared = draft(
            actions = listOf(
                ask(),
                Action.If(Condition.VarCompare("answer", CmpOp.EQ, expected = "Sì"), then = listOf(Action.Vibrate(100))),
            ),
        )
        assertEquals(emptyList(), errors(compared))
    }

    // --- invoke_llm.output -----------------------------------------------------------------------

    @Test fun `constrained outputs pass with a capture`() {
        listOf(
            OutputSpec(OutputKind.CHOICE, choices = listOf("urgent", "normal")),
            OutputSpec(OutputKind.CHOICE, choices = (1..8).map { "c$it" }),
            OutputSpec(OutputKind.NUMBER, min = 1, max = 5),
            OutputSpec(OutputKind.NUMBER, min = -3, max = -3),
            OutputSpec(OutputKind.BOOLEAN),
        ).forEach { spec ->
            assertEquals(emptyList(), errors(draft(actions = listOf(llm(spec)))), "$spec")
        }
    }

    @Test fun `constrained outputs refuse malformed shapes`() {
        mapOf(
            OutputSpec(OutputKind.CHOICE, choices = listOf("solo")) to "llm_output_choices_invalid",
            OutputSpec(OutputKind.CHOICE, choices = (1..9).map { "c$it" }) to "llm_output_choices_invalid",
            OutputSpec(OutputKind.CHOICE, choices = listOf("Urgent", "normal")) to "llm_output_choices_invalid",
            OutputSpec(OutputKind.CHOICE, choices = listOf("a b", "c")) to "llm_output_choices_invalid",
            OutputSpec(OutputKind.CHOICE, choices = listOf("a", "a")) to "llm_output_choices_invalid",
            OutputSpec(OutputKind.CHOICE, choices = listOf("a", "x".repeat(25))) to "llm_output_choices_invalid",
            OutputSpec(OutputKind.CHOICE, choices = listOf("a", "b"), min = 1) to "llm_output_bounds_forbidden",
            OutputSpec(OutputKind.NUMBER, min = 1) to "llm_output_bounds_required",
            OutputSpec(OutputKind.NUMBER, max = 1) to "llm_output_bounds_required",
            OutputSpec(OutputKind.NUMBER, min = 5, max = 1) to "llm_output_bounds_invalid",
            OutputSpec(OutputKind.NUMBER, min = 0, max = Long.MAX_VALUE) to "llm_output_bounds_invalid",
            OutputSpec(OutputKind.NUMBER, choices = listOf("a", "b"), min = 0, max = 1) to "llm_output_choices_forbidden",
            OutputSpec(OutputKind.BOOLEAN, choices = listOf("a", "b")) to "llm_output_choices_forbidden",
            OutputSpec(OutputKind.BOOLEAN, max = 1) to "llm_output_bounds_forbidden",
        ).forEach { (spec, code) ->
            assertEquals(listOf(code), errors(draft(actions = listOf(llm(spec)))), "$spec")
        }
    }

    @Test fun `a constrained output must be captured`() {
        val boolean = OutputSpec(OutputKind.BOOLEAN)
        assertTrue("llm_output_requires_capture" in errors(draft(actions = listOf(llm(boolean, deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION, captureAs = null)))))
        assertTrue("llm_output_requires_capture" in errors(draft(actions = listOf(llm(boolean, deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION)))))
    }

    @Test fun `the goal leaves room for the shape instruction`() {
        val boolean = OutputSpec(OutputKind.BOOLEAN)
        val long = llm(boolean).copy(goal = "x".repeat(3_990))
        assertEquals(listOf("goal_invalid"), errors(draft(actions = listOf(long))))
        assertEquals(emptyList(), errors(draft(actions = listOf(long.copy(output = null)))))
    }

    @Test fun `the captured answer has the type of its shape and can be compared`() {
        val number = draft(
            actions = listOf(
                llm(OutputSpec(OutputKind.NUMBER, min = 1, max = 5)),
                Action.If(Condition.VarCompare("verdict", CmpOp.GT, expected = "3"), then = listOf(Action.Vibrate(100))),
            ),
        )
        assertEquals(emptyList(), errors(number))
        val boolean = draft(
            actions = listOf(
                llm(OutputSpec(OutputKind.BOOLEAN)),
                Action.If(Condition.VarCompare("verdict", CmpOp.EQ, expected = "true"), then = listOf(Action.Vibrate(100))),
            ),
        )
        assertEquals(emptyList(), errors(boolean))
        // Un booleano non si confronta con un testo qualunque: il tipo è BOOLEAN.
        val wrong = boolean.copy(
            actions = listOf(
                llm(OutputSpec(OutputKind.BOOLEAN)),
                Action.If(Condition.VarCompare("verdict", CmpOp.EQ, expected = "forse"), then = listOf(Action.Vibrate(100))),
            ),
        )
        assertTrue("var_compare_type_invalid" in errors(wrong))
        // Una risposta vincolata è CLEAN; il testo libero del modello no.
        val shell = draft(
            actions = listOf(llm(OutputSpec(OutputKind.CHOICE, choices = listOf("on", "off"))), Action.RunShell("\${verdict}")),
        )
        assertFalse("interpolation_shell_command_position" in errors(shell))
        val free = draft(actions = listOf(llm(null), Action.RunShell("\${verdict}")))
        assertTrue("interpolation_shell_command_position" in errors(free))
    }
}
