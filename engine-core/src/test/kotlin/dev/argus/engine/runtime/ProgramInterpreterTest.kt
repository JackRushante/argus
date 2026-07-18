package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgramInterpreterTest {
    private val evaluator = ConditionEvaluator(
        Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC),
    )
    private val smsTrigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)
    private val smsEvent = TriggerEvent.PhoneStateChanged(
        PhoneEvent.SMS_RECEIVED,
        number = "+390000000000",
        smsText = "Your code is 123456",
    )

    @Test
    fun `trigger binding extraction drives an if and keeps a readable path`() = runTest {
        val seen = mutableListOf<ResolvedProgramAction>()
        val journal = mutableListOf<ProgramJournalEntry>()
        val interpreter = interpreter(
            runner = ProgramActionRunner { action, _ ->
                seen += action
                ProgramActionResult(ActionResult.Success)
            },
            journal = ProgramExecutionJournal(journal::add),
        )

        val result = interpreter.execute(
            smsTrigger,
            smsEvent,
            bindings = listOf(
                VarBinding.TriggerPayload(
                    "otp",
                    TriggerField.TEXT,
                    extractionRegex = "([0-9]{6})",
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
            ),
            actions = listOf(
                Action.If(
                    Condition.VarCompare("otp", CmpOp.EQ, expected = "123456"),
                    then = listOf(Action.ShowNotification("OTP", "Code: \${otp}")),
                ),
            ),
        )

        assertTrue(result.completed)
        assertEquals(listOf("1.then.1"), result.steps.map { it.path.value })
        assertEquals("Code: 123456", assertIs<Action.ShowNotification>(seen.single().action).text)
        assertEquals(IntegrityLabel.TAINTED, seen.single().inputIntegrity)
        assertEquals(listOf("1.then.1"), journal.map { it.path.value })
    }

    @Test
    fun `while rereads live state on every iteration and wait is journaled`() = runTest {
        val battery = StateQuery.Builtin(StateKeys.BATTERY)
        val readings = ArrayDeque(listOf("1", "2", "3"))
        var readCalls = 0
        val pauses = mutableListOf<Long>()
        val interpreter = ProgramInterpreter(
            runner = ProgramActionRunner { _, _ -> ProgramActionResult(ActionResult.Success) },
            stateProvider = { request ->
                readCalls += 1
                assertEquals(setOf(battery), request.queries)
                DeviceState(queryValues = mapOf(battery.canonicalId to readings.removeFirst()))
            },
            conditionEvaluator = evaluator,
            pause = { pauses += it },
        )

        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            TriggerEvent.TimeFired(
                dev.argus.engine.model.AutomationId("a"),
                dev.argus.engine.model.ApprovalFingerprint("f".repeat(64)),
            ),
            emptyList(),
            listOf(
                Action.While(
                    Condition.StateCompare(
                        battery,
                        StateValueType.NUMBER,
                        CmpOp.LT,
                        "3",
                    ),
                    body = listOf(
                        Action.SetFlashlight(true),
                        Action.Wait(100),
                        Action.SetFlashlight(false),
                    ),
                    maxIterations = 5,
                    delayBetweenMs = 50,
                ),
            ),
        )

        assertTrue(result.completed)
        assertEquals(3, readCalls)
        assertEquals(listOf(100L, 50L, 100L, 50L), pauses)
        assertEquals(
            listOf(
                "1.while[1].1", "1.while[1].2", "1.while[1].3",
                "1.while[2].1", "1.while[2].2", "1.while[2].3",
            ),
            result.steps.map { it.path.value },
        )
    }

    @Test
    fun `capture output is tainted secret and available to the next sink`() = runTest {
        val seen = mutableListOf<ResolvedProgramAction>()
        val interpreter = interpreter(
            runner = ProgramActionRunner { action, _ ->
                seen += action
                if (action.action is Action.RunShell) {
                    ProgramActionResult(ActionResult.Success, capturedText = "shell output")
                } else {
                    ProgramActionResult(ActionResult.Success)
                }
            },
        )

        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            emptyList(),
            listOf(
                Action.RunShell("id", captureAs = "out"),
                Action.ShowNotification("Result", "\${out}"),
            ),
        )

        assertTrue(result.completed)
        val notification = seen[1]
        assertEquals("shell output", assertIs<Action.ShowNotification>(notification.action).text)
        assertEquals(IntegrityLabel.TAINTED, notification.inputIntegrity)
        assertEquals(ConfidentialityLabel.SECRET, notification.inputConfidentiality)
        assertTrue(ValueProvenance.SHELL in notification.inputProvenance)
    }

    @Test
    fun `capture submission without a concrete output blocks the program`() = runTest {
        var calls = 0
        val interpreter = interpreter(
            runner = ProgramActionRunner { _, _ ->
                calls += 1
                ProgramActionResult(ActionResult.Submitted)
            },
        )

        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            emptyList(),
            listOf(
                Action.InvokeLlm(
                    "answer",
                    emptyList(),
                    emptyList(),
                    false,
                    captureAs = "answer",
                ),
                Action.ShowNotification("x", "\${answer}"),
            ),
        )

        assertEquals(1, calls)
        assertEquals("capture_missing", result.stopCode)
        assertEquals("1", result.stopPath?.value)
        assertIs<ActionResult.Failure>(result.steps.single().result)
    }

    @Test
    fun `ordinary action failure does not prevent the next action`() = runTest {
        var calls = 0
        val interpreter = interpreter(
            runner = ProgramActionRunner { _, _ ->
                calls += 1
                ProgramActionResult(
                    if (calls == 1) ActionResult.Failure("expected_failure") else ActionResult.Success,
                )
            },
        )

        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            emptyList(),
            listOf(Action.SetWifi(true), Action.SetBluetooth(true)),
        )

        assertTrue(result.completed)
        assertEquals(2, calls)
        assertIs<ActionResult.Failure>(result.steps.first().result)
        assertEquals(ActionResult.Success, result.steps.last().result)
    }

    @Test
    fun `missing binding and tainted authority stop before unsafe execution`() = runTest {
        var calls = 0
        val interpreter = interpreter(
            runner = ProgramActionRunner { _, _ ->
                calls += 1
                ProgramActionResult(ActionResult.Success)
            },
        )

        val missing = interpreter.execute(
            smsTrigger,
            smsEvent.copy(smsText = null),
            listOf(
                VarBinding.TriggerPayload(
                    "body",
                    TriggerField.TEXT,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
            ),
            listOf(Action.ShowNotification("x", "\${body}")),
        )
        assertEquals("binding_unavailable", missing.stopCode)
        assertEquals(0, calls)

        val blocked = interpreter.execute(
            smsTrigger,
            smsEvent,
            listOf(
                VarBinding.TriggerPayload(
                    "body",
                    TriggerField.TEXT,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
            ),
            listOf(Action.RunShell("echo \${body}")),
        )
        assertEquals("taint_blocked", blocked.stopCode)
        assertEquals(0, calls)
    }

    @Test
    fun `extraction without a match never falls back to the whole external payload`() = runTest {
        var calls = 0
        val interpreter = interpreter(
            runner = ProgramActionRunner { _, _ ->
                calls += 1
                ProgramActionResult(ActionResult.Success)
            },
        )

        val result = interpreter.execute(
            smsTrigger,
            smsEvent.copy(smsText = "message without an otp"),
            listOf(
                VarBinding.TriggerPayload(
                    "otp",
                    TriggerField.TEXT,
                    extractionRegex = "([0-9]{6})",
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
            ),
            listOf(Action.ShowNotification("OTP", "\${otp}")),
        )

        assertEquals("binding_unavailable", result.stopCode)
        assertEquals(0, calls)
    }

    @Test
    fun `mismatched trigger and event fail before binding or execution`() = runTest {
        var calls = 0
        val result = interpreter(
            runner = ProgramActionRunner { _, _ ->
                calls += 1
                ProgramActionResult(ActionResult.Success)
            },
        ).execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            smsEvent,
            emptyList(),
            listOf(Action.SetWifi(true)),
        )

        assertEquals("trigger_mismatch", result.stopCode)
        assertEquals(0, calls)
    }

    @Test
    fun `invalid action in an unchosen branch is rejected by preflight`() = runTest {
        var calls = 0
        val result = interpreter(
            runner = ProgramActionRunner { _, _ ->
                calls += 1
                ProgramActionResult(ActionResult.Success)
            },
        ).execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            emptyList(),
            listOf(
                Action.If(
                    Condition.BooleanLiteral(true),
                    then = listOf(Action.SetWifi(true)),
                    orElse = listOf(Action.Wait(0)),
                ),
            ),
        )

        assertEquals("invalid_program", result.stopCode)
        assertEquals(0, calls)
    }

    @Test
    fun `runtime values and captured output redact their payload from toString`() {
        val secret = "never-log-this-value"
        val value = VarValue(
            secret,
            VarType.TEXT,
            IntegrityLabel.TAINTED,
            ConfidentialityLabel.SECRET,
            setOf(ValueProvenance.SHELL),
        )

        assertFalse(secret in value.toString())
        assertFalse(secret in ProgramActionResult(ActionResult.Success, secret).toString())
    }

    @Test
    fun `hard deadline bounds even a statically invalid oversized loop`() = runTest {
        val interpreter = ProgramInterpreter(
            runner = ProgramActionRunner { _, _ -> ProgramActionResult(ActionResult.Success) },
            stateProvider = { DeviceState() },
            conditionEvaluator = evaluator,
        )

        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            emptyList(),
            listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    listOf(Action.Wait(3_600_000)),
                    maxIterations = 1_000,
                ),
            ),
        )

        assertEquals("deadline_exceeded", result.stopCode)
        assertEquals("1.while[6].1", result.stopPath?.value)
        assertEquals(5, result.steps.size)
    }

    @Test
    fun `external cancellation is never converted into a deadline`() = runTest {
        val interpreter = ProgramInterpreter(
            runner = ProgramActionRunner { _, _ -> ProgramActionResult(ActionResult.Success) },
            stateProvider = { DeviceState() },
            conditionEvaluator = evaluator,
            pause = { throw CancellationException("user disabled") },
        )

        assertFailsWith<CancellationException> {
            interpreter.execute(
                Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
                timeEvent(),
                emptyList(),
                listOf(Action.Wait(10)),
            )
        }
    }

    @Test
    fun `flow evaluator preserves typed variable semantics and unknown`() {
        val state = DeviceState()
        val values = mapOf(
            "n" to VarValue(
                "10",
                VarType.NUMBER,
                IntegrityLabel.CLEAN,
                ConfidentialityLabel.PUBLIC,
                setOf(ValueProvenance.LITERAL),
            ),
        )
        assertEquals(
            ConditionEvaluator.Result.MET,
            evaluator.flowResult(Condition.VarCompare("n", CmpOp.GT, "2"), state, values::get),
        )
        assertEquals(
            ConditionEvaluator.Result.STATE_UNAVAILABLE,
            evaluator.flowResult(Condition.VarCompare("missing", CmpOp.EQ, "x"), state, values::get),
        )
        assertEquals(
            ConditionEvaluator.Result.STATE_UNAVAILABLE,
            evaluator.flowResult(
                Condition.Not(Condition.VarCompare("missing", CmpOp.EQ, "x")),
                state,
                values::get,
            ),
        )
    }

    private fun interpreter(
        runner: ProgramActionRunner,
        journal: ProgramExecutionJournal = NoopProgramExecutionJournal,
    ) = ProgramInterpreter(
        runner = runner,
        stateProvider = { DeviceState() },
        conditionEvaluator = evaluator,
        journal = journal,
        pause = {},
    )

    private fun timeEvent() = TriggerEvent.TimeFired(
        dev.argus.engine.model.AutomationId("a"),
        dev.argus.engine.model.ApprovalFingerprint("f".repeat(64)),
    )
}
