package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 0.8 `ask`: dopo la morte del processo la risposta (o la scadenza) fa proseguire il programma da dove si
 * era fermato, solo se la regola è ancora quella approvata e armata, e una volta sola.
 */
class AskResumeEngineTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC)
    private val ask = Action.Ask("Accendo?", listOf("Sì", "No"), "answer", timeoutMs = 60_000)
    private val echo = Action.ShowNotification("Risposta", "\${answer}")

    private fun rule(actions: List<Action> = listOf(ask, echo)): Automation {
        val unsigned = Automation(
            AutomationId("q1"), "Domanda", CreatedBy.USER, AutomationStatus.ARMED, Trigger.Manual, actions,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private class RecordingAsker(private val outcome: QuestionOutcome) : QuestionAsker {
        val asked = mutableListOf<Pair<QuestionContext, ProgramQuestion>>()
        override suspend fun ask(context: QuestionContext, question: ProgramQuestion): QuestionOutcome {
            asked += context to question
            return outcome
        }
    }

    private inner class Rig(rules: List<Automation>, val asker: QuestionAsker = QuestionAsker.UNAVAILABLE) {
        val store = FakeAutomationStore(rules)
        val executor = FakeActionExecutor()
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        val engine = Engine(
            store = store,
            executor = executor,
            evaluator = ConditionEvaluator(clock),
            matcher = TriggerMatcher(),
            firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
            audit = audit,
            journal = journal,
            programClock = clock,
            questions = asker,
            now = { 1_000 },
        )
    }

    private fun envelope(rule: Automation) =
        TriggerEnvelope(TriggerEventId("manual:1"), TriggerEvent.ManualFired(rule.id, requireNotNull(rule.approvalFingerprint)))

    private fun resumed(
        rule: Automation,
        outcome: QuestionOutcome = QuestionOutcome.Answered("Sì"),
        continuation: ProgramContinuation = ProgramContinuation(ActionPath("1"), emptyMap(), emptyMap(), 0, clock.millis()),
    ) = ResumedQuestion(
        questionId = "b".repeat(32),
        automationId = rule.id,
        approvalFingerprint = requireNotNull(rule.approvalFingerprint),
        envelope = envelope(rule),
        continuation = continuation,
        outcome = outcome,
    )

    @Test
    fun `a question knows the event of its run, so it can go on after a restart`() = runTest {
        val rule = rule()
        val asker = RecordingAsker(QuestionOutcome.Answered("No"))
        val rig = Rig(listOf(rule), asker)
        val envelope = envelope(rule)

        rig.engine.onTrigger(envelope) { DeviceState() }

        val (context, question) = asker.asked.single()
        assertSame(envelope, context.envelope)
        assertEquals("1", assertNotNull(question.continuation).askPath.value)
    }

    @Test
    fun `the answer after a restart runs the rest of the program once`() = runTest {
        val rule = rule()
        val rig = Rig(listOf(rule))
        val resumed = resumed(rule)

        val outcome = assertNotNull(rig.engine.onQuestionResumed(resumed) { DeviceState() })

        assertEquals(listOf<Action>(Action.ShowNotification("Risposta", "Sì")), rig.executor.executed)
        assertEquals(resumed.runEventId, outcome.eventId)
        assertEquals("resumed:" + "b".repeat(32), resumed.runEventId.value)
        assertEquals(AuditKind.FIRED, rig.audit.events.last().kind)
        assertEquals(ExecutionStatus.SUCCEEDED, rig.journal.completions.last().status)

        assertNull(rig.engine.onQuestionResumed(resumed) { DeviceState() })
        assertEquals(1, rig.executor.executed.size)
        assertEquals(AuditKind.SUPPRESSED_DUPLICATE, rig.audit.events.last().kind)
    }

    @Test
    fun `no answer and no default after a restart is a cancelled run, not an error`() = runTest {
        val rule = rule()
        val rig = Rig(listOf(rule))

        rig.engine.onQuestionResumed(resumed(rule, QuestionOutcome.TimedOut)) { DeviceState() }

        assertTrue(rig.executor.executed.isEmpty())
        assertEquals(ExecutionStatus.CANCELLED, rig.journal.completions.last().status)
        val stop = rig.audit.events.last()
        assertEquals(AuditKind.SUPPRESSED_NOT_ELIGIBLE, stop.kind)
        assertEquals(ProgramInterpreter.ASK_TIMEOUT, stop.detail)
    }

    @Test
    fun `a rule that changed or was switched off does not go on`() = runTest {
        val approved = rule()
        val edited = rule(actions = listOf(ask, Action.ShowNotification("Altro", "\${answer}")))
        listOf(
            Rig(listOf(edited)),
            Rig(listOf(approved.copy(status = AutomationStatus.DISABLED, enabled = false))),
            Rig(emptyList()),
        ).forEach { rig ->
            assertNull(rig.engine.onQuestionResumed(resumed(approved)) { DeviceState() })
            assertTrue(rig.executor.executed.isEmpty())
            val audit = rig.audit.events.single()
            assertEquals(AuditKind.SUPPRESSED_NOT_ELIGIBLE, audit.kind)
            assertEquals(ProgramInterpreter.ASK_RESUME_STALE, audit.detail)
        }
    }

    @Test
    fun `a continuation that does not point at an ask stops closed`() = runTest {
        val rule = rule()
        val rig = Rig(listOf(rule))
        val wrong = ProgramContinuation(ActionPath("2"), emptyMap(), emptyMap(), 0, clock.millis())

        rig.engine.onQuestionResumed(resumed(rule, continuation = wrong)) { DeviceState() }

        assertTrue(rig.executor.executed.isEmpty())
        assertEquals(ProgramInterpreter.ASK_RESUME_INVALID, rig.audit.events.last().detail)
        assertIs<ExecutionStatus>(rig.journal.completions.last().status)
        assertEquals(ExecutionStatus.FAILED, rig.journal.completions.last().status)
    }
}
