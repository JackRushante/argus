package dev.argus.engine.dryrun

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfirmSpec
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.LlmOutputPolicy
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SecretRef
import dev.argus.engine.model.SpanSlot
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.UserModeSnapshot
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.ActionPath
import dev.argus.engine.runtime.AllowAllFirePolicy
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.ConfirmationCodes
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.ProgramInterpreter
import dev.argus.engine.runtime.SpanPhase
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Prova a secco»: the real matcher, policy, evaluator, interpreter and interpolator, nothing executed. */
class DryRunSimulatorTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-17T18:12:00Z"), ZoneOffset.UTC)
    private val chat = Trigger.Notification("com.whatsapp", conversationId = CONV, isGroup = false)
    private val msg = VarBinding.TriggerPayload("msg", TriggerField.TEXT, confidentiality = ConfidentialityLabel.PRIVATE)
    private val reads = mutableListOf<StateReadRequest>()

    private fun rule(
        trigger: Trigger,
        actions: List<Action>,
        vars: List<VarBinding> = emptyList(),
        conditions: Condition? = null,
        confirm: ConfirmSpec? = null,
        exit: ExitSpec? = null,
        status: AutomationStatus = AutomationStatus.ARMED,
        signed: Boolean = true,
    ): Automation {
        val draft = AutomationDraft("Prova", trigger, actions, vars = vars, conditions = conditions, confirm = confirm, exit = exit)
        val unsigned = Automation(
            AutomationId("dry"), "Prova", CreatedBy.USER, status, trigger, actions,
            vars = vars,
            conditions = conditions,
            enabled = status == AutomationStatus.ARMED,
            confirm = confirm,
            exit = exit,
            schemaVersion = AutomationSchema.versionFor(draft),
        )
        return if (signed) unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned)) else unsigned
    }

    private fun simulator(
        policy: FirePolicy = AllowAllFirePolicy,
        state: DeviceState = DeviceState(),
        facts: DryRunFacts = DryRunFacts(
            whitelistedConversationIds = setOf(CONV),
            privilegedReady = true,
            hasTelephony = true,
            canSendSms = true,
            canCall = true,
        ),
    ) = DryRunSimulator(
        firePolicy = policy,
        readState = { request ->
            reads += request
            request.project(state)
        },
        facts = DryRunFactsSource { facts },
        clock = clock,
        locale = { Locale.ITALY },
    )

    private fun request(
        automation: Automation,
        sample: DryRunSample = DryRunSample(sender = "Marta", text = "Sei libero stasera? urgente"),
        answers: Map<String, DryRunAnswer> = emptyMap(),
        phase: SpanPhase = SpanPhase.START,
    ) = DryRunRequest(automation, phase, sample, answers, defaultText = "testo di prova", conversationLabel = "Marta")

    private fun DryRunReport.leaves() = steps.filterIsInstance<DryRunStep.Leaf>()

    @Test
    fun `the sample message matches and reaches the shell command as one quoted argument`() = runTest {
        val shell = Action.RunShell("cmd notification post -t Urgente argus \${msg}")
        val report = simulator().simulate(request(rule(chat, listOf(shell), vars = listOf(msg))))

        assertEquals(DryRunEnd.COMPLETED, report.end)
        assertEquals(DryRunStep.TriggerCheck(chat, SpanPhase.START, matched = true), report.steps.first())
        val leaf = report.leaves().single()
        assertNull(leaf.code)
        val cmd = assertIs<Action.RunShell>(leaf.resolved).cmd
        val range = leaf.tainted.getValue("cmd").single()
        val external = cmd.substring(range)
        assertTrue(external.startsWith("'") && external.endsWith("'"), external)
        assertTrue("Sei libero stasera? urgente" in external)
        assertEquals("cmd notification post -t Urgente argus ", cmd.substring(0, range.first))
    }

    @Test
    fun `a message from someone else is not the rule's chat and nothing would run`() = runTest {
        val report = simulator().simulate(
            request(rule(chat, listOf(Action.Vibrate(100))), DryRunSample(sender = "Luca", text = "ciao")),
        )
        assertEquals(DryRunEnd.NOT_MATCHED, report.end)
        assertEquals(listOf<DryRunStep>(DryRunStep.TriggerCheck(chat, SpanPhase.START, false)), report.steps)
    }

    @Test
    fun `conditions are read now and an unreadable state fails closed even under not`() = runTest {
        val condition = Condition.Not(Condition.StateEquals("wifi", CmpOp.EQ, "on"))
        val report = simulator().simulate(request(rule(Trigger.Manual, listOf(Action.Vibrate(1)), conditions = condition)))

        assertEquals(DryRunEnd.CONDITIONS_UNKNOWN, report.end)
        val check = report.steps.filterIsInstance<DryRunStep.Conditions>().single()
        assertEquals(ConditionEvaluator.Result.STATE_UNAVAILABLE, check.result)
        assertEquals(ConditionEvaluator.Result.STATE_UNAVAILABLE, check.check.children.single().result)
        assertTrue(report.leaves().isEmpty())
        assertTrue(reads.any { "wifi" in it.keys })
    }

    @Test
    fun `a readable state is evaluated with the value it has now`() = runTest {
        val condition = Condition.And(
            listOf(Condition.TimeWindow("08:00", "22:00", "UTC"), Condition.StateEquals("wifi", CmpOp.EQ, "on")),
        )
        val report = simulator(state = DeviceState(values = mapOf("wifi" to "off")))
            .simulate(request(rule(Trigger.Manual, listOf(Action.Vibrate(1)), conditions = condition)))

        assertEquals(DryRunEnd.CONDITIONS_NOT_MET, report.end)
        val tree = report.steps.filterIsInstance<DryRunStep.Conditions>().single().check
        assertEquals(ConditionEvaluator.Result.MET, tree.children[0].result)
        assertEquals(ConditionEvaluator.Result.NOT_MET, tree.children[1].result)
        assertEquals("off", tree.children[1].observed)
    }

    @Test
    fun `the AI is never called and the chosen constrained answer drives the branch`() = runTest {
        val ask = Action.InvokeLlm(
            goal = "è urgente?",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            deliver = GenerativeDeliverMode.CAPTURE_ONLY,
            captureAs = "urgent",
            output = OutputSpec(OutputKind.BOOLEAN),
        )
        val branch = Action.If(
            Condition.VarCompare("urgent", CmpOp.EQ, expected = "true"),
            then = listOf(Action.Vibrate(500)),
            orElse = listOf(Action.ShowNotification("No", "niente")),
        )
        val automation = rule(Trigger.Manual, listOf(ask, branch))
        val key = DryRunAnswerKeys.forPath(ActionPath("1"))

        val byDefault = simulator().simulate(request(automation))
        assertEquals(DryRunEnd.COMPLETED, byDefault.end)
        assertEquals("true", byDefault.leaves().first().input?.answer)
        assertEquals(SimulatedInputKind.AI_ANSWER, byDefault.leaves().first().input?.kind)
        assertIs<Action.Vibrate>(byDefault.leaves().last().action)

        val no = simulator().simulate(request(automation, answers = mapOf(key to DryRunAnswer.Text("false"))))
        val flow = no.steps.filterIsInstance<DryRunStep.Flow>().single()
        assertEquals(ConditionEvaluator.Result.NOT_MET, flow.result)
        assertIs<Action.ShowNotification>(no.leaves().last().action)
        assertEquals("1.else.1", no.leaves().last().path.value)

        // Validated like a real answer: anything but yes/no is a closed failure, never coerced.
        val invalid = simulator().simulate(request(automation, answers = mapOf(key to DryRunAnswer.Text("forse"))))
        assertEquals(DryRunEnd.STOPPED, invalid.end)
        assertEquals(LlmOutputPolicy.OUTPUT_INVALID, invalid.endCode)
        val stopped = invalid.leaves().single()
        assertEquals(LlmOutputPolicy.OUTPUT_INVALID, stopped.code)
        assertTrue(stopped.stopsRun)
    }

    @Test
    fun `a blocked leaf shows its code and the other actions still run`() = runTest {
        val report = simulator(facts = DryRunFacts(privilegedReady = false))
            .simulate(request(rule(Trigger.Manual, listOf(Action.RunShell("echo hi"), Action.Vibrate(100)))))

        assertEquals(DryRunEnd.COMPLETED, report.end)
        assertEquals(listOf(DryRunCodes.SHIZUKU_UNAVAILABLE, null), report.leaves().map { it.code })
    }

    @Test
    fun `external data in the command position is refused by the interpolator and stops the program`() = runTest {
        val report = simulator().simulate(request(rule(chat, listOf(Action.RunShell("\${msg} --help")), vars = listOf(msg))))

        assertEquals(DryRunEnd.STOPPED, report.end)
        val leaf = report.leaves().single()
        assertEquals("shell_quote_context", leaf.code)
        assertNull(leaf.resolved)
        assertTrue(leaf.stopsRun)
    }

    @Test
    fun `a shell command from a chat that is not whitelisted is refused at fire time`() = runTest {
        val report = simulator(facts = DryRunFacts(privilegedReady = true))
            .simulate(request(rule(chat, listOf(Action.RunShell("echo \${msg}")), vars = listOf(msg))))
        assertEquals(DryRunCodes.SHELL_EXTERNAL_TRIGGER, report.leaves().single().code)
    }

    @Test
    fun `a fire policy block stops before the actions with its code`() = runTest {
        val blocking = object : FirePolicy {
            override suspend fun evaluate(automation: Automation, event: TriggerEvent) =
                FirePolicyDecision.Block("capability_unavailable", needsReview = false)
        }
        val report = simulator(policy = blocking).simulate(request(rule(Trigger.Manual, listOf(Action.Vibrate(1)))))
        assertEquals(DryRunEnd.BLOCKED, report.end)
        assertEquals(DryRunStep.Blocked("capability_unavailable"), report.steps.last())
    }

    @Test
    fun `ask takes the chosen answer, and no answer without a default stops the program`() = runTest {
        val ask = Action.Ask("Esco, \${msg}?", listOf("si", "no"), "answer", timeoutMs = 60_000)
        val echo = Action.ShowNotification("Risposta", "\${answer}")
        val automation = rule(chat, listOf(ask, echo), vars = listOf(msg))
        val key = DryRunAnswerKeys.forPath(ActionPath("1"))

        val answered = simulator().simulate(request(automation, answers = mapOf(key to DryRunAnswer.Text("no"))))
        val question = answered.steps.filterIsInstance<DryRunStep.Question>().single()
        assertEquals("Esco, Sei libero stasera? urgente?", question.prompt)
        assertEquals("Sei libero stasera? urgente", question.prompt!!.substring(question.promptTainted.single()))
        assertEquals("no", assertIs<Action.ShowNotification>(answered.leaves().single().resolved).text)

        val silent = simulator().simulate(request(automation, answers = mapOf(key to DryRunAnswer.NoAnswer)))
        assertEquals(DryRunEnd.STOPPED, silent.end)
        assertEquals(ProgramInterpreter.ASK_TIMEOUT, silent.endCode)
        assertEquals(ProgramInterpreter.ASK_TIMEOUT, silent.steps.filterIsInstance<DryRunStep.Question>().single().code)
    }

    @Test
    fun `a captured http response stays external and a later command quotes it`() = runTest {
        val get = Action.HttpRequest(HttpMethod.GET, "https://example.com/meteo", captureAs = "body")
        val echo = Action.RunShell("echo \${body}")
        val automation = rule(Trigger.Manual, listOf(get, echo))
        val key = DryRunAnswerKeys.forPath(ActionPath("1"))

        val report = simulator().simulate(request(automation, answers = mapOf(key to DryRunAnswer.Text("sole e vento"))))
        assertEquals(DryRunEnd.COMPLETED, report.end)
        assertEquals(SimulatedInputKind.HTTP_RESPONSE, report.leaves().first().input?.kind)
        val shell = report.leaves().last()
        val cmd = assertIs<Action.RunShell>(shell.resolved).cmd
        assertEquals("'sole e vento'", cmd.substring(shell.tainted.getValue("cmd").single()))
    }

    @Test
    fun `the real capture rules apply to the sample body`() = runTest {
        val get = Action.HttpRequest(HttpMethod.GET, "https://example.com/meteo", captureAs = "temp", extract = "/temp")
        val automation = rule(Trigger.Manual, listOf(get, Action.ShowNotification("Meteo", "\${temp}")))
        val key = DryRunAnswerKeys.forPath(ActionPath("1"))

        val ok = simulator().simulate(request(automation, answers = mapOf(key to DryRunAnswer.Text("""{"temp": 21}"""))))
        assertEquals("21", assertIs<Action.ShowNotification>(ok.leaves().last().resolved).text)

        val notJson = simulator().simulate(request(automation, answers = mapOf(key to DryRunAnswer.Text("ventuno"))))
        assertEquals("http_capture_not_json", notJson.leaves().first().code)
    }

    @Test
    fun `a secret shows by name only and a missing one fails closed`() = runTest {
        val call = Action.HttpRequest(
            HttpMethod.GET,
            "https://example.com/api",
            secretHeaders = listOf(SecretRef("Authorization", "ha_token", "Bearer ")),
        )
        val automation = rule(Trigger.Manual, listOf(call))
        val missing = simulator(facts = DryRunFacts()).simulate(request(automation))
        assertEquals(DryRunCodes.HTTP_SECRET_MISSING, missing.leaves().single().code)

        val present = simulator(facts = DryRunFacts(secretNames = setOf("ha_token"))).simulate(request(automation))
        assertNull(present.leaves().single().code)
    }

    @Test
    fun `the sender's number reaches an sms as external data`() = runTest {
        val sms = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)
        val from = VarBinding.TriggerPayload("from", TriggerField.NUMBER, confidentiality = ConfidentialityLabel.PRIVATE)
        val automation = rule(sms, listOf(Action.SendSms("\${from}", "Ricevuto")), vars = listOf(from))
        val report = simulator().simulate(request(automation, DryRunSample(number = "+393331234567", text = "ciao")))

        val leaf = report.leaves().single()
        assertNull(leaf.code)
        assertEquals("+393331234567", assertIs<Action.SendSms>(leaf.resolved).number)
        assertEquals(listOf(0 until 13), leaf.tainted["number"])
    }

    @Test
    fun `loops follow the real bounds and a long loop stops the simulation`() = runTest {
        val three = Action.While(Condition.BooleanLiteral(true), body = listOf(Action.Vibrate(10)), maxIterations = 3)
        val report = simulator().simulate(request(rule(Trigger.Manual, listOf(three))))
        assertEquals(DryRunEnd.COMPLETED, report.end)
        assertEquals(3, report.leaves().size)
        assertEquals(3, report.steps.filterIsInstance<DryRunStep.Flow>().size)
        assertEquals(listOf("1.while[1].1", "1.while[2].1", "1.while[3].1"), report.leaves().map { it.path.value })

        val long = Action.While(Condition.BooleanLiteral(true), body = listOf(Action.Vibrate(10)), maxIterations = 1_000)
        val limited = simulator().simulate(request(rule(Trigger.Manual, listOf(long))))
        assertEquals(DryRunEnd.STEP_LIMIT, limited.end)
        assertEquals(DryRunStep.Stopped(null, DryRunCodes.STEP_LIMIT), limited.steps.last())
    }

    @Test
    fun `waits are shown and never waited`() = runTest {
        val report = simulator().simulate(request(rule(Trigger.Manual, listOf(Action.Wait(3_600_000), Action.Vibrate(1)))))
        assertEquals(DryRunStep.Wait(ActionPath("1"), 3_600_000), report.steps[1])
        assertEquals(DryRunEnd.COMPLETED, report.end)
    }

    @Test
    fun `stored values and the mode the run writes are seen by later steps, in memory only`() = runTest {
        val actions = listOf(
            Action.SetStored("count", StoredOp.INCREMENT),
            Action.If(
                Condition.StoredCompare("count", StateValueType.NUMBER, CmpOp.EQ, "1"),
                then = listOf(Action.SetUserMode("casa")),
            ),
            Action.If(Condition.UserModeIs("Casa"), then = listOf(Action.Vibrate(1))),
            Action.SetUserMode("ufficio"),
        )
        val state = DeviceState(userMode = UserModeSnapshot(modes = listOf("Casa", "Lavoro"), active = "Lavoro"))
        val report = simulator(state = state).simulate(request(rule(Trigger.Manual, actions)))

        assertEquals(DryRunEnd.COMPLETED, report.end)
        assertEquals(listOf(ConditionEvaluator.Result.MET, ConditionEvaluator.Result.MET),
            report.steps.filterIsInstance<DryRunStep.Flow>().map { it.result })
        assertEquals(listOf(null, null, null, "user_mode_unknown"), report.leaves().map { it.code })
    }

    @Test
    fun `the end of a span puts the start values back last first, then runs the end actions`() = runTest {
        val on = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED, "Auto")
        val off = Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED, "Auto")
        val automation = rule(
            on,
            listOf(Action.SetVolume(VolumeStream.MEDIA, 100), Action.SetDnd(DndMode.PRIORITY)),
            exit = ExitSpec(off, actions = listOf(Action.ShowNotification("Fine", "rimesso"))),
        )
        val end = simulator().simulate(request(automation, DryRunSample(name = "Auto"), phase = SpanPhase.EXIT))

        assertEquals(DryRunStep.TriggerCheck(off, SpanPhase.EXIT, true), end.steps.first())
        val restore = end.steps.filterIsInstance<DryRunStep.Restore>().single()
        assertEquals(listOf(SpanSlot.Dnd, SpanSlot.Volume(VolumeStream.MEDIA)), restore.slots)
        assertIs<Action.ShowNotification>(end.leaves().single().action)

        val start = simulator().simulate(request(automation, DryRunSample(name = "Auto"), phase = SpanPhase.START))
        assertEquals(2, start.leaves().size)
        assertTrue(start.steps.none { it is DryRunStep.Restore })
    }

    @Test
    fun `a cancelled confirmation stops before the actions`() = runTest {
        val automation = rule(Trigger.Manual, listOf(Action.Vibrate(1)), confirm = ConfirmSpec(60_000))
        val cancelled = simulator().simulate(
            request(automation, answers = mapOf(DryRunAnswerKeys.CONFIRM to DryRunAnswer.Text(DryRunAnswerKeys.CONFIRM_CANCEL))),
        )
        assertEquals(DryRunEnd.CANCELLED, cancelled.end)
        assertEquals(ConfirmationCodes.CANCELLED, cancelled.endCode)
        assertTrue(cancelled.leaves().isEmpty())

        val run = simulator().simulate(request(automation))
        assertEquals(1, run.leaves().size)
    }

    @Test
    fun `a draft is simulated as if it were armed`() = runTest {
        val draft = rule(Trigger.Manual, listOf(Action.Vibrate(1)), status = AutomationStatus.PENDING_APPROVAL, signed = false)
        val report = simulator().simulate(request(draft))
        assertTrue(report.simulatedAsArmed)
        assertEquals(DryRunEnd.COMPLETED, report.end)
        assertEquals(ApprovalFingerprints.of(draft), report.automation.approvalFingerprint)
    }

    @Test
    fun `an nfc scan is unverified unless the user ticks it, and only then may start a shell command`() = runTest {
        val tag = Trigger.NfcTag("04:A2:2B:1C")
        val automation = rule(tag, listOf(Action.RunShell("echo tag"), Action.Vibrate(1)))
        val unverified = simulator().simulate(request(automation, DryRunSample()))
        assertEquals(listOf(DryRunCodes.SHELL_EXTERNAL_TRIGGER, null), unverified.leaves().map { it.code })

        val verified = simulator().simulate(request(automation, DryRunSample(verifiedScan = true)))
        assertEquals(listOf(null, null), verified.leaves().map { it.code })
    }

    private companion object {
        const val CONV = "wa:marta"
    }
}
