package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 0.8 `ask`: una domanda porta con sé dove si è fermato il programma e con quali variabili, così una
 * risposta (o la scadenza) arrivata dopo la morte del processo fa proseguire il programma da lì.
 */
class ProgramInterpreterAskResumeTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC)
    private val evaluator = ConditionEvaluator(clock)
    private val trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC")
    private val event = TriggerEvent.TimeFired(AutomationId("a"), ApprovalFingerprint("f".repeat(64)))

    private val seen = mutableListOf<ResolvedProgramAction>()
    private val asked = mutableListOf<ProgramQuestion>()

    private fun interpreter(questions: ProgramQuestions = ProgramQuestions.UNAVAILABLE, programClock: Clock = clock) =
        ProgramInterpreter(
            runner = ProgramActionRunner { action, _ ->
                seen += action
                ProgramActionResult(ActionResult.Success)
            },
            stateProvider = { DeviceState() },
            conditionEvaluator = evaluator,
            pause = {},
            clock = programClock,
            questions = questions,
        )

    private fun answering(vararg outcomes: QuestionOutcome): ProgramQuestions {
        val queue = ArrayDeque(outcomes.toList())
        return ProgramQuestions { question ->
            asked += question
            queue.removeFirstOrNull() ?: QuestionOutcome.Unavailable("no_more_answers")
        }
    }

    private fun ask(defaultChoice: String? = null) =
        Action.Ask("Esco, \${who}?", listOf("Sì", "No"), "answer", timeoutMs = 60_000, defaultChoice = defaultChoice)

    private val who = VarBinding.Literal("who", "Anna", VarType.TEXT, ConfidentialityLabel.PRIVATE)
    private val echo = Action.ShowNotification("Risposta", "\${answer}")
    private val texts get() = seen.map { assertIs<Action.ShowNotification>(it.action).text }

    @Test
    fun `a question carries where the program stopped and its variables`() = runTest {
        interpreter(answering(QuestionOutcome.Answered("Sì")))
            .execute(trigger, event, listOf(who), listOf(Action.ShowNotification("Prima", "x"), ask(), echo))

        val continuation = assertNotNull(asked.single().continuation)
        assertEquals("2", continuation.askPath.value)
        assertEquals("Anna", continuation.variables.getValue("who").text)
        assertEquals(clock.millis(), continuation.startedAtMillis)
        assertTrue(continuation.loops.isEmpty())
    }

    @Test
    fun `resuming runs only what comes after the question, with the answer`() = runTest {
        interpreter(answering(QuestionOutcome.Answered("Sì")))
            .execute(trigger, event, listOf(who), listOf(Action.ShowNotification("Prima", "x"), ask(), echo))
        val continuation = assertNotNull(asked.single().continuation)
        seen.clear()

        val result = interpreter().resume(
            trigger, event, listOf(who), listOf(Action.ShowNotification("Prima", "x"), ask(), echo),
            continuation, QuestionOutcome.Answered("No"),
        )

        assertTrue(result.completed, "${result.stopCode}")
        assertEquals(listOf("No"), texts)
        assertEquals(listOf("2", "3"), result.steps.map { it.path.value })
        assertEquals("ask", result.steps.first().actionType)
    }

    @Test
    fun `resuming inside an if branch does not evaluate the condition again`() = runTest {
        val flag = VarBinding.Literal("flag", "on", VarType.TEXT, ConfidentialityLabel.PUBLIC)
        val program = listOf(
            Action.If(Condition.VarCompare("flag", CmpOp.EQ, expected = "on"), then = listOf(ask(), echo)),
            Action.ShowNotification("Dopo", "fine"),
        )
        interpreter(answering(QuestionOutcome.Answered("Sì"))).execute(trigger, event, listOf(who, flag), program)
        val continuation = assertNotNull(asked.single().continuation)
        assertEquals("1.then.1", continuation.askPath.value)
        seen.clear()

        // La condizione ora non varrebbe più: il programma è già dentro il ramo e ci resta.
        val changed = ProgramContinuation(
            continuation.askPath,
            continuation.variables + ("flag" to continuation.variables.getValue("flag").copy(text = "off")),
            continuation.loops,
            continuation.generativeCalls,
            continuation.startedAtMillis,
        )
        val result = interpreter().resume(trigger, event, listOf(who, flag), program, changed, QuestionOutcome.Answered("No"))

        assertTrue(result.completed, "${result.stopCode}")
        assertEquals(listOf("No", "fine"), texts)
    }

    @Test
    fun `resuming inside a while goes on from the same iteration`() = runTest {
        val loop = Action.While(Condition.BooleanLiteral(true), body = listOf(ask(), echo), maxIterations = 3)
        interpreter(
            answering(QuestionOutcome.Answered("Sì"), QuestionOutcome.Answered("Sì"), QuestionOutcome.Answered("Sì")),
        ).execute(trigger, event, listOf(who), listOf(loop))
        val second = assertNotNull(asked[1].continuation)
        assertEquals("1.while[2].1", second.askPath.value)
        assertEquals(LoopPosition(iteration = 2, effective = 3), second.loops.getValue("1"))
        seen.clear()
        asked.clear()

        val result = interpreter(answering(QuestionOutcome.Answered("Sì")))
            .resume(trigger, event, listOf(who), listOf(loop), second, QuestionOutcome.Answered("No"))

        assertTrue(result.completed, "${result.stopCode}")
        assertEquals(listOf("No", "Sì"), texts)
        assertEquals("1.while[3].1", asked.single().path.value)
    }

    @Test
    fun `restored variables keep their labels`() = runTest {
        val tainted = VarValue("ciao", VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, setOf(ValueProvenance.NOTIFICATION))
        val program = listOf(ask(), Action.ShowNotification("Msg", "\${who}"))
        val continuation = ProgramContinuation(ActionPath("1"), mapOf("who" to tainted), emptyMap(), 0, clock.millis())

        val result = interpreter().resume(trigger, event, listOf(who), program, continuation, QuestionOutcome.Answered("Sì"))

        assertTrue(result.completed, "${result.stopCode}")
        assertEquals(IntegrityLabel.TAINTED, seen.single().inputIntegrity)
        assertEquals(setOf(ValueProvenance.NOTIFICATION), seen.single().inputProvenance)
    }

    @Test
    fun `a timeout after a restart follows the same rules as before`() = runTest {
        val program = listOf(ask(defaultChoice = "No"), echo)
        val continuation = ProgramContinuation(ActionPath("1"), emptyMap(), emptyMap(), 0, clock.millis())
        val withDefault = interpreter().resume(trigger, event, emptyList(), program, continuation, QuestionOutcome.TimedOut)
        assertTrue(withDefault.completed)
        assertEquals(listOf("No"), texts)

        val noDefault = interpreter().resume(trigger, event, emptyList(), listOf(ask(), echo), continuation, QuestionOutcome.TimedOut)
        assertEquals(ProgramInterpreter.ASK_TIMEOUT, noDefault.stopCode)
        assertEquals(1, seen.size)
    }

    @Test
    fun `a continuation that does not fit the approved program is refused`() = runTest {
        val program = listOf(Action.ShowNotification("Prima", "x"), ask(), echo)
        val notAnAsk = ProgramContinuation(ActionPath("1"), emptyMap(), emptyMap(), 0, clock.millis())
        val missing = ProgramContinuation(ActionPath("9"), emptyMap(), emptyMap(), 0, clock.millis())
        val stranger = ProgramContinuation(
            ActionPath("2"),
            mapOf("intruder" to VarValue("x", VarType.TEXT, IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, emptySet())),
            emptyMap(),
            0,
            clock.millis(),
        )
        val loopless = ProgramContinuation(ActionPath("1.while[2].1"), emptyMap(), emptyMap(), 0, clock.millis())
        val loopProgram = listOf(Action.While(Condition.BooleanLiteral(true), body = listOf(ask(), echo), maxIterations = 3))

        listOf(
            interpreter().resume(trigger, event, listOf(who), program, notAnAsk, QuestionOutcome.Answered("Sì")),
            interpreter().resume(trigger, event, listOf(who), program, missing, QuestionOutcome.Answered("Sì")),
            interpreter().resume(trigger, event, listOf(who), program, stranger, QuestionOutcome.Answered("Sì")),
            interpreter().resume(trigger, event, listOf(who), loopProgram, loopless, QuestionOutcome.Answered("Sì")),
        ).forEach { result -> assertEquals(ProgramInterpreter.ASK_RESUME_INVALID, result.stopCode) }
        assertTrue(seen.isEmpty())

        val invalid = interpreter().resume(trigger, event, listOf(who), program, ProgramContinuation(ActionPath("2"), emptyMap(), emptyMap(), 0, clock.millis()), QuestionOutcome.Answered("Forse"))
        assertEquals(ProgramInterpreter.ASK_ANSWER_INVALID, invalid.stopCode)
    }

    @Test
    fun `the six hour deadline counts from the original start`() = runTest {
        val continuation = ProgramContinuation(
            ActionPath("1"), emptyMap(), emptyMap(), 0,
            clock.millis() - ProgramInterpreter.MAX_EXECUTION_MILLIS - 1,
        )
        val result = interpreter().resume(trigger, event, emptyList(), listOf(ask(), echo), continuation, QuestionOutcome.Answered("Sì"))
        assertEquals("deadline_exceeded", result.stopCode)
        assertTrue(seen.isEmpty())
    }
}
