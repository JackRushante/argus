package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.LlmOutputPolicy
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8: la domanda `ask` e la risposta vincolata di `invoke_llm` dentro un programma. */
class ProgramInterpreterAskOutputTest {
    private val evaluator = ConditionEvaluator(Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC))
    private val trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC")
    private val event = TriggerEvent.TimeFired(AutomationId("a"), ApprovalFingerprint("f".repeat(64)))

    private val seen = mutableListOf<ResolvedProgramAction>()
    private val asked = mutableListOf<ProgramQuestion>()

    private fun interpreter(
        questions: ProgramQuestions? = null,
        captured: String? = null,
    ) = ProgramInterpreter(
        runner = ProgramActionRunner { action, _ ->
            seen += action
            ProgramActionResult(ActionResult.Success, capturedText = captured.takeIf { action.action is Action.InvokeLlm })
        },
        stateProvider = { DeviceState() },
        conditionEvaluator = evaluator,
        pause = {},
        questions = questions ?: ProgramQuestions.UNAVAILABLE,
    )

    private fun answering(outcome: QuestionOutcome) = ProgramQuestions { question ->
        asked += question
        outcome
    }

    private fun ask(defaultChoice: String? = null, prompt: String = "Esco, \${who}?") =
        Action.Ask(prompt, listOf("Sì", "No"), "answer", timeoutMs = 60_000, defaultChoice = defaultChoice)

    private val who = VarBinding.Literal("who", "Anna", VarType.TEXT, ConfidentialityLabel.PRIVATE)
    private val echo = Action.ShowNotification("Risposta", "\${answer}")

    @Test
    fun `the tapped choice binds the approved literal as clean public user data`() = runTest {
        val result = interpreter(answering(QuestionOutcome.Answered("Sì")))
            .execute(trigger, event, listOf(who), listOf(ask(), echo))

        assertTrue(result.completed)
        val question = asked.single()
        assertEquals("Esco, Anna?", question.prompt)
        assertEquals(listOf("Sì", "No"), question.choices)
        assertEquals(60_000, question.timeoutMs)
        assertEquals("1", question.path.value)
        val shown = seen.single()
        assertEquals("Sì", assertIs<Action.ShowNotification>(shown.action).text)
        assertEquals(IntegrityLabel.CLEAN, shown.inputIntegrity)
        assertEquals(ConfidentialityLabel.PUBLIC, shown.inputConfidentiality)
        assertEquals(setOf(ValueProvenance.USER), shown.inputProvenance)
        assertEquals(listOf<ActionResult>(ActionResult.Success, ActionResult.Success), result.steps.map { it.result })
        assertEquals("ask", result.steps.first().actionType)
    }

    @Test
    fun `no answer uses the default choice and says so in the journal`() = runTest {
        val result = interpreter(answering(QuestionOutcome.TimedOut))
            .execute(trigger, event, listOf(who), listOf(ask(defaultChoice = "No"), echo))

        assertTrue(result.completed)
        assertEquals("No", assertIs<Action.ShowNotification>(seen.single().action).text)
        assertEquals(ActionResult.SuccessWithDetail(ProgramInterpreter.ASK_TIMEOUT_DEFAULT), result.steps.first().result)
    }

    @Test
    fun `no answer and no default stops the program with a closed code`() = runTest {
        val result = interpreter(answering(QuestionOutcome.TimedOut))
            .execute(trigger, event, listOf(who), listOf(ask(), echo))

        assertEquals(ProgramInterpreter.ASK_TIMEOUT, result.stopCode)
        assertEquals("1", result.stopPath?.value)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an answer that is not an approved literal never reaches the variable`() = runTest {
        val result = interpreter(answering(QuestionOutcome.Answered("Sì; rm -rf /")))
            .execute(trigger, event, listOf(who), listOf(ask(), echo))

        assertEquals(ProgramInterpreter.ASK_ANSWER_INVALID, result.stopCode)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a question that cannot be asked fails closed`() = runTest {
        val unavailable = interpreter().execute(trigger, event, listOf(who), listOf(ask(), echo))
        assertEquals(ProgramQuestions.UNAVAILABLE_CODE, unavailable.stopCode)

        val throwing = interpreter(ProgramQuestions { error("boom") })
            .execute(trigger, event, listOf(who), listOf(ask(), echo))
        assertEquals(ProgramInterpreter.ASK_FAILED, throwing.stopCode)

        val blocked = interpreter(answering(QuestionOutcome.Unavailable("notifications_disabled")))
            .execute(trigger, event, listOf(who), listOf(ask(), echo))
        assertEquals("notifications_disabled", blocked.stopCode)
    }

    @Test
    fun `a prompt longer than 120 characters after filling in is blocked`() = runTest {
        val long = VarBinding.Literal("who", "x".repeat(118), VarType.TEXT, ConfidentialityLabel.PUBLIC)
        val result = interpreter(answering(QuestionOutcome.Answered("Sì")))
            .execute(trigger, event, listOf(long), listOf(ask(prompt = "Hi \${who}"), echo))

        assertEquals("interpolation_too_long", result.stopCode)
        assertTrue(asked.isEmpty())
    }

    private fun constrained(spec: OutputSpec) = Action.InvokeLlm(
        goal = "È urgente?",
        contextSources = emptyList(),
        allowedTools = emptyList(),
        replyTargetSender = false,
        deliver = GenerativeDeliverMode.CAPTURE_ONLY,
        captureAs = "verdict",
        output = spec,
    )

    @Test
    fun `a constrained number is captured canonical, typed and clean`() = runTest {
        val program = listOf(
            constrained(OutputSpec(OutputKind.NUMBER, min = 1, max = 5)),
            Action.If(
                Condition.VarCompare("verdict", CmpOp.GT, expected = "3"),
                then = listOf(Action.ShowNotification("Voto", "\${verdict}")),
            ),
        )
        val result = interpreter(captured = " 4\n").execute(trigger, event, emptyList(), program)

        assertTrue(result.completed)
        val shown = seen.last()
        assertEquals("4", assertIs<Action.ShowNotification>(shown.action).text)
        assertEquals(IntegrityLabel.CLEAN, shown.inputIntegrity)
        assertEquals(ConfidentialityLabel.PRIVATE, shown.inputConfidentiality)
        assertTrue(ValueProvenance.MODEL in shown.inputProvenance)
    }

    @Test
    fun `a constrained boolean answer becomes true or false`() = runTest {
        val program = listOf(
            constrained(OutputSpec(OutputKind.BOOLEAN)),
            Action.ShowNotification("Urgente", "\${verdict}"),
        )
        interpreter(captured = "yes").execute(trigger, event, emptyList(), program)

        assertEquals("true", assertIs<Action.ShowNotification>(seen.last().action).text)
    }

    @Test
    fun `free text where a shape was approved stops the program`() = runTest {
        val program = listOf(
            constrained(OutputSpec(OutputKind.CHOICE, choices = listOf("urgent", "normal"))),
            Action.ShowNotification("Esito", "\${verdict}"),
        )
        val result = interpreter(captured = "I think it is urgent").execute(trigger, event, emptyList(), program)

        assertEquals(LlmOutputPolicy.OUTPUT_INVALID, result.stopCode)
        assertEquals(1, seen.size)
        assertEquals(ActionResult.Failure(LlmOutputPolicy.OUTPUT_INVALID), result.steps.single().result)
    }

    @Test
    fun `without a shape the model text stays tainted as before`() = runTest {
        val free = constrained(OutputSpec(OutputKind.BOOLEAN)).copy(output = null)
        interpreter(captured = "yes").execute(trigger, event, emptyList(), listOf(free, Action.ShowNotification("t", "\${verdict}")))

        assertEquals(IntegrityLabel.TAINTED, seen.last().inputIntegrity)
        assertNull(LlmOutputPolicy.normalize(OutputSpec(OutputKind.BOOLEAN), "maybe"))
    }
}
