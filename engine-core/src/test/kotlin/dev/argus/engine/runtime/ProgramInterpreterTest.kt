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
import kotlin.test.assertNull
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
    fun `random_int is engine-generated CLEAN and drives an if and a bounded while`() = runTest {
        val seen = mutableListOf<ResolvedProgramAction>()
        // Seam deterministico: il motore "estrae" 4 (pari). Nessuna Random reale nei test.
        val even = ProgramInterpreter(
            runner = ProgramActionRunner { action, _ ->
                seen += action
                ProgramActionResult(ActionResult.Success)
            },
            stateProvider = { DeviceState() },
            conditionEvaluator = evaluator,
            pause = {},
            randomInt = { bound ->
                assertEquals(10, bound) // il motore usa max come bound, valore in [0, max).
                4
            },
        )

        val result = even.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            bindings = listOf(VarBinding.RandomInt("r", max = 10)),
            actions = listOf(
                Action.If(
                    Condition.VarCompare("r", CmpOp.IS_EVEN),
                    then = listOf(
                        Action.While(
                            Condition.VarCompare("r", CmpOp.IS_EVEN),
                            body = listOf(Action.ShowNotification("tick", "v=\${r}")),
                            maxIterations = 4,
                        ),
                    ),
                    orElse = listOf(Action.ShowNotification("odd", "dispari")),
                ),
            ),
        )

        assertTrue(result.completed)
        // Ramo PARI scelto, e il while (condizione sempre vera perché il valore è fisso) conta
        // esattamente maxIterations volte.
        assertEquals(
            listOf(
                "1.then.1.while[1].1", "1.then.1.while[2].1",
                "1.then.1.while[3].1", "1.then.1.while[4].1",
            ),
            result.steps.map { it.path.value },
        )
        val notification = seen.first()
        // Nasce CLEAN, non segreto, provenienza ENGINE, tipo numerico reso "4".
        assertEquals("v=4", assertIs<Action.ShowNotification>(notification.action).text)
        assertEquals(IntegrityLabel.CLEAN, notification.inputIntegrity)
        assertEquals(ConfidentialityLabel.PUBLIC, notification.inputConfidentiality)
        assertTrue(ValueProvenance.ENGINE in notification.inputProvenance)

        // Con un valore DISPARI lo stesso programma prende il ramo else: IS_EVEN pilota il branch.
        val oddSeen = mutableListOf<ResolvedProgramAction>()
        val odd = ProgramInterpreter(
            runner = ProgramActionRunner { action, _ ->
                oddSeen += action
                ProgramActionResult(ActionResult.Success)
            },
            stateProvider = { DeviceState() },
            conditionEvaluator = evaluator,
            pause = {},
            randomInt = { 3 },
        )
        val oddResult = odd.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            bindings = listOf(VarBinding.RandomInt("r", max = 10)),
            actions = listOf(
                Action.If(
                    Condition.VarCompare("r", CmpOp.IS_EVEN),
                    then = listOf(
                        Action.While(
                            Condition.VarCompare("r", CmpOp.IS_EVEN),
                            body = listOf(Action.ShowNotification("tick", "v=\${r}")),
                            maxIterations = 4,
                        ),
                    ),
                    orElse = listOf(Action.ShowNotification("odd", "dispari")),
                ),
            ),
        )
        assertTrue(oddResult.completed)
        assertEquals(listOf("1.else.1"), oddResult.steps.map { it.path.value })
        assertEquals("dispari", assertIs<Action.ShowNotification>(oddSeen.single().action).text)
    }

    @Test
    fun `while iteration count comes from a NUMBER variable`() = runTest {
        val interpreter = interpreter(
            runner = ProgramActionRunner { _, _ -> ProgramActionResult(ActionResult.Success) },
        )
        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            bindings = listOf(VarBinding.Literal("n", "3", VarType.NUMBER, ConfidentialityLabel.PUBLIC)),
            actions = listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    body = listOf(Action.SetFlashlight(true)),
                    maxIterationsVar = "n",
                ),
            ),
        )
        assertTrue(result.completed)
        assertEquals(
            listOf("1.while[1].1", "1.while[2].1", "1.while[3].1"),
            result.steps.map { it.path.value },
        )
    }

    @Test
    fun `while with a non-integer variable count fails closed`() = runTest {
        var calls = 0
        val interpreter = interpreter(
            runner = ProgramActionRunner { _, _ ->
                calls += 1
                ProgramActionResult(ActionResult.Success)
            },
        )
        // "3.5" è un NUMBER valido come binding ma non coercibile a intero ⇒ conteggio ignoto.
        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            bindings = listOf(VarBinding.Literal("n", "3.5", VarType.NUMBER, ConfidentialityLabel.PUBLIC)),
            actions = listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    body = listOf(Action.SetFlashlight(true)),
                    maxIterationsVar = "n",
                ),
            ),
        )
        assertEquals("while_count_unavailable", result.stopCode)
        assertEquals("1", result.stopPath?.value)
        assertEquals(0, calls)
    }

    @Test
    fun `while variable count above the ceiling is clamped to 1000`() = runTest {
        var calls = 0
        val interpreter = interpreter(
            runner = ProgramActionRunner { _, _ ->
                calls += 1
                ProgramActionResult(ActionResult.Success)
            },
        )
        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            bindings = listOf(VarBinding.Literal("n", "5000", VarType.NUMBER, ConfidentialityLabel.PUBLIC)),
            actions = listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    body = listOf(Action.SetFlashlight(true)),
                    maxIterationsVar = "n",
                ),
            ),
        )
        assertTrue(result.completed)
        // 5000 clampato a 1000: esattamente 1000 iterazioni eseguite.
        assertEquals(1000, calls)
        assertEquals(1000, result.steps.size)
    }

    @Test
    fun `random_int drives the number of flashlight blinks in a while`() = runTest {
        // Il caso d'uso: lampeggia la torcia il numero-di-volte di un random_int.
        val seen = mutableListOf<ResolvedProgramAction>()
        val interpreter = ProgramInterpreter(
            runner = ProgramActionRunner { action, _ ->
                seen += action
                ProgramActionResult(ActionResult.Success)
            },
            stateProvider = { DeviceState() },
            conditionEvaluator = evaluator,
            pause = {},
            randomInt = { bound ->
                assertEquals(5, bound)
                3 // il motore "estrae" 3 lampeggi.
            },
        )
        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            bindings = listOf(VarBinding.RandomInt("blinks", max = 5)),
            actions = listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    body = listOf(Action.SetFlashlight(true), Action.SetFlashlight(false)),
                    maxIterationsVar = "blinks",
                ),
            ),
        )
        assertTrue(result.completed)
        // 3 giri × (on, off) = 6 foglie.
        assertEquals(
            listOf(
                "1.while[1].1", "1.while[1].2",
                "1.while[2].1", "1.while[2].2",
                "1.while[3].1", "1.while[3].2",
            ),
            result.steps.map { it.path.value },
        )
        assertEquals(6, seen.size)
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
    fun `missing binding stops, but tainted authority now executes in aggressive posture`() = runTest {
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

        // Posture AGGRESSIVO: un payload TAINTED interpolato in RunShell.cmd non è più bloccato
        // dall'interpolatore; l'azione risolve ed esegue. Lo shell-gating sui mittenti resta
        // enforced altrove (DraftValidator / FirePolicy / ShizukuActionExecutor), non qui.
        val executed = interpreter.execute(
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
        assertTrue(executed.completed)
        assertNull(executed.stopCode)
        assertEquals(1, calls)
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
    fun `approved action path resolves nested branches and ignores loop iteration identity`() {
        val leaf = Action.SetFlashlight(true)
        val tree = listOf(
            Action.If(
                Condition.BooleanLiteral(true),
                then = listOf(
                    Action.While(
                        Condition.BooleanLiteral(true),
                        body = listOf(leaf),
                        maxIterations = 3,
                    ),
                ),
            ),
        )

        assertEquals(leaf, ActionPath("1.then.1.while[999].1").resolve(tree))
        assertEquals(null, ActionPath("1.else.1").resolve(tree))
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

    @Test
    fun `text operands coerce to numbers for GT and LT and fail closed when non numeric`() {
        val state = DeviceState()
        fun textVar(value: String) = VarValue(
            value,
            VarType.TEXT,
            IntegrityLabel.CLEAN,
            ConfidentialityLabel.PUBLIC,
            setOf(ValueProvenance.LITERAL),
        )
        val values = mapOf(
            "captured" to textVar("7"),
            "low" to textVar("3"),
            "word" to textVar("abc"),
        )

        // device-found: l'LLM risponde un numero, catturato come TEXT, poi confrontato con GT.
        // "7" > "5" -> TRUE numericamente.
        assertEquals(
            ConditionEvaluator.Result.MET,
            evaluator.flowResult(Condition.VarCompare("captured", CmpOp.GT, "5"), state, values::get),
        )
        // "3" > "5" -> FALSE.
        assertEquals(
            ConditionEvaluator.Result.NOT_MET,
            evaluator.flowResult(Condition.VarCompare("low", CmpOp.GT, "5"), state, values::get),
        )
        // LT simmetrico: "3" < "5" -> TRUE, "7" < "5" -> FALSE.
        assertEquals(
            ConditionEvaluator.Result.MET,
            evaluator.flowResult(Condition.VarCompare("low", CmpOp.LT, "5"), state, values::get),
        )
        assertEquals(
            ConditionEvaluator.Result.NOT_MET,
            evaluator.flowResult(Condition.VarCompare("captured", CmpOp.LT, "5"), state, values::get),
        )
        // TEXT non numerico -> fail-closed FALSE (NOT_MET), mai STATE_UNAVAILABLE, mai eccezione.
        assertEquals(
            ConditionEvaluator.Result.NOT_MET,
            evaluator.flowResult(Condition.VarCompare("word", CmpOp.GT, "5"), state, values::get),
        )
        assertEquals(
            ConditionEvaluator.Result.NOT_MET,
            evaluator.flowResult(Condition.VarCompare("word", CmpOp.LT, "5"), state, values::get),
        )
        // Anche il letterale non numerico è fail-closed (nessun crash).
        assertEquals(
            ConditionEvaluator.Result.NOT_MET,
            evaluator.flowResult(Condition.VarCompare("captured", CmpOp.GT, "cinque"), state, values::get),
        )
        // Confronto fra due var TEXT: "7" > "3" -> TRUE.
        assertEquals(
            ConditionEvaluator.Result.MET,
            evaluator.flowResult(
                Condition.VarCompare("captured", CmpOp.GT, expectedVar = "low"),
                state,
                values::get,
            ),
        )
    }

    // --- B27: i cicli non gonfiano il journal ---------------------------------------------

    private fun threeLeafLoop(iterations: Int) = Action.While(
        Condition.BooleanLiteral(true),
        body = listOf(Action.SetFlashlight(true), Action.SetFlashlight(false), Action.SetWifi(true)),
        maxIterations = iterations,
    )

    private suspend fun runLoop(
        iterations: Int,
        runner: ProgramActionRunner = ProgramActionRunner { _, _ -> ProgramActionResult(ActionResult.Success) },
    ): Pair<ProgramExecutionResult, List<ProgramJournalEntry>> {
        val journal = mutableListOf<ProgramJournalEntry>()
        val result = interpreter(runner, ProgramExecutionJournal(journal::add)).execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            emptyList(),
            listOf(threeLeafLoop(iterations)),
        )
        return result to journal
    }

    @Test
    fun `a thousand iterations of three leaves write at most 101 journal entries`() = runTest {
        val (result, journal) = runLoop(1_000)

        assertTrue(result.completed)
        // Lo stato in memoria resta completo: l'esito dell'esecuzione non dipende dal journal.
        assertEquals(3_000, result.steps.size)
        assertEquals(101, journal.size)
        // Le prime 100 voci sono i primi 100 passaggi, in ordine.
        assertEquals("1.while[1].1", journal.first().path.value)
        assertEquals("1.while[34].1", journal[99].path.value)
        val summary = journal.last()
        assertEquals(OmittedSteps.ACTION_TYPE, summary.actionType)
        assertEquals(ActionJournalOutcome.SUCCEEDED, summary.outcome)
        assertEquals(2_900, OmittedSteps.countFrom(summary.errorCode))
        // La voce di riepilogo ha un path valido: quello del primo passaggio omesso.
        assertEquals("1.while[34].2", summary.path.value)
    }

    @Test
    fun `a failure after the limit is still journaled`() = runTest {
        val (result, journal) = runLoop(1_000) { _, path ->
            if (path.value == "1.while[900].2") {
                ProgramActionResult(ActionResult.Failure("boom_code"))
            } else {
                ProgramActionResult(ActionResult.Success)
            }
        }

        assertTrue(result.completed)
        val failed = journal.filter { it.outcome == ActionJournalOutcome.FAILED }
        assertEquals(listOf("1.while[900].2"), failed.map { it.path.value })
        assertEquals("boom_code", failed.single().errorCode)
        // 100 scritte + 1 fallita + riepilogo; il fallito non conta come omesso.
        assertEquals(102, journal.size)
        assertEquals(2_899, OmittedSteps.countFrom(journal.last().errorCode))
    }

    @Test
    fun `a submitted leaf after the limit is still journaled because async resolution needs its row`() = runTest {
        val (_, journal) = runLoop(200) { _, path ->
            if (path.value == "1.while[150].3") {
                ProgramActionResult(ActionResult.Submitted)
            } else {
                ProgramActionResult(ActionResult.Success)
            }
        }

        assertEquals(
            listOf("1.while[150].3"),
            journal.filter { it.outcome == ActionJournalOutcome.SUBMITTED }.map { it.path.value },
        )
    }

    @Test
    fun `a program within the limit journals every leaf and no summary`() = runTest {
        val (result, journal) = runLoop(33) // 99 foglie
        assertEquals(99, result.steps.size)
        assertEquals(99, journal.size)
        assertTrue(journal.none { it.actionType == OmittedSteps.ACTION_TYPE })

        val (_, over) = runLoop(34) // 102 foglie: 100 scritte + 2 omesse
        assertEquals(101, over.size)
        assertEquals(2, OmittedSteps.countFrom(over.last().errorCode))
    }

    @Test
    fun `exactly one hundred leaves write no summary`() = runTest {
        val journal = mutableListOf<ProgramJournalEntry>()
        val result = interpreter(
            ProgramActionRunner { _, _ -> ProgramActionResult(ActionResult.Success) },
            ProgramExecutionJournal(journal::add),
        ).execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            emptyList(),
            listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    body = listOf(Action.SetFlashlight(true)),
                    maxIterations = 100,
                ),
            ),
        )
        assertEquals(100, result.steps.size)
        assertEquals(100, journal.size)
        assertTrue(journal.none { it.actionType == OmittedSteps.ACTION_TYPE })
    }

    @Test
    fun `the summary is written even when the program stops mid loop`() = runTest {
        val battery = StateQuery.DumpsysField("battery", "level")
        var reads = 0
        val journal = mutableListOf<ProgramJournalEntry>()
        val interpreter = ProgramInterpreter(
            runner = ProgramActionRunner { _, _ -> ProgramActionResult(ActionResult.Success) },
            // Dopo 50 letture lo stato non e' piu' disponibile: il ciclo si ferma.
            stateProvider = {
                reads += 1
                if (reads <= 50) DeviceState(queryValues = mapOf(battery.canonicalId to "1")) else DeviceState()
            },
            conditionEvaluator = evaluator,
            journal = ProgramExecutionJournal(journal::add),
            pause = {},
        )

        val result = interpreter.execute(
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            timeEvent(),
            emptyList(),
            listOf(
                Action.While(
                    Condition.StateCompare(battery, StateValueType.NUMBER, CmpOp.LT, "3"),
                    body = listOf(Action.SetFlashlight(true), Action.SetFlashlight(false), Action.SetWifi(true)),
                    maxIterations = 1_000,
                ),
            ),
        )

        assertEquals("condition_state_unavailable", result.stopCode)
        assertEquals(150, result.steps.size)
        assertEquals(101, journal.size)
        assertEquals(50, OmittedSteps.countFrom(journal.last().errorCode))
    }

    @Test
    fun `omitted steps code round trips and rejects foreign codes`() {
        assertEquals(7, OmittedSteps.countFrom(OmittedSteps.code(7)))
        assertNull(OmittedSteps.countFrom(null))
        assertNull(OmittedSteps.countFrom("action_failed"))
        assertNull(OmittedSteps.countFrom("omitted_"))
        assertNull(OmittedSteps.countFrom("omitted_x9"))
        assertNull(OmittedSteps.countFrom("omitted_99999999999999999999"))
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
