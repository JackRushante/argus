package dev.argus.engine.runtime

import dev.argus.engine.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineTest {
    private fun clock(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)
    private fun armed(
        id: String,
        t: Trigger,
        acts: List<Action>,
        cond: Condition? = null,
        cooldown: Long = 0,
        prio: Int = 0,
        vars: List<VarBinding> = emptyList(),
    ): Automation {
        val draft = AutomationDraft(
            name = id,
            trigger = t,
            actions = acts,
            vars = vars,
            conditions = cond,
            cooldownMs = cooldown,
        )
        val unsigned = Automation(
            AutomationId(id), id, CreatedBy.LLM, AutomationStatus.ARMED, t, acts, conditions = cond,
            vars = vars,
            cooldownMs = cooldown,
            priority = prio,
            schemaVersion = AutomationSchema.versionFor(draft),
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private fun timeEvent(automation: Automation) = TriggerEvent.TimeFired(
        automation.id,
        requireNotNull(automation.approvalFingerprint),
    )

    private val allowAll = FirePolicy { _, _ -> FirePolicyDecision.Allow }

    private fun envelope(id: String, event: TriggerEvent) =
        TriggerEnvelope(TriggerEventId(id), event)

    private fun engine(
        store: AutomationStore,
        ex: ActionExecutor,
        clockIso: String,
        audit: AuditSink = NoopAuditSink,
        journal: ExecutionJournal = NoopExecutionJournal,
        policy: FirePolicy = allowAll,
        now: () -> Long = { 1_000 },
        resolvedExecutor: ResolvedActionExecutor? = null,
    ) = Engine(
        store = store,
        executor = ex,
        evaluator = ConditionEvaluator(clock(clockIso)),
        matcher = TriggerMatcher(),
        firePolicy = policy,
        audit = audit,
        journal = journal,
        resolvedExecutor = resolvedExecutor,
        now = now,
    )

    @Test
    fun `P4 program executes interpolated branch and journals its stable path`() = runTest {
        val automation = armed(
            id = "p4-branch",
            t = Trigger.Notification("com.whatsapp"),
            acts = listOf(
                Action.If(
                    condition = Condition.VarCompare("body", CmpOp.CONTAINS, expected = "go"),
                    then = listOf(Action.ShowNotification("Argus", "Received: \${body}")),
                ),
            ),
            vars = listOf(
                VarBinding.TriggerPayload(
                    "body",
                    TriggerField.TEXT,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
            ),
        )
        val seen = mutableListOf<Pair<Action, FireContext>>()
        val journal = FakeExecutionJournal()

        val outcome = engine(
            FakeAutomationStore(listOf(automation)),
            ActionExecutor { action, context ->
                seen += action to context
                ActionResult.Success
            },
            "2026-07-18T10:00:00Z",
            journal = journal,
        ).onTrigger(
            envelope(
                "sbn:p4:1",
                TriggerEvent.NotificationPosted("com.whatsapp", text = "please go now"),
            ),
        ) { DeviceState() }.single()

        assertEquals(
            "Received: please go now",
            (seen.single().first as Action.ShowNotification).text,
        )
        assertEquals("1.then.1", seen.single().second.actionPath)
        assertEquals(0, seen.single().second.actionIndex)
        assertEquals(listOf("1.then.1"), journal.actions.map { it.actionPath })
        assertEquals(ActionResult.Success, outcome.results.single())
        assertEquals(ExecutionStatus.SUCCEEDED, journal.completions.single().status)
    }

    @Test
    fun `P4 loop gives every executed leaf a unique ordinal and readable path`() = runTest {
        val automation = armed(
            id = "p4-loop",
            t = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            acts = listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    body = listOf(
                        Action.SetFlashlight(true),
                        Action.Wait(1),
                        Action.SetFlashlight(false),
                    ),
                    maxIterations = 2,
                ),
            ),
        )
        val contexts = mutableListOf<FireContext>()
        val journal = FakeExecutionJournal()

        engine(
            FakeAutomationStore(listOf(automation)),
            ActionExecutor { _, context ->
                contexts += context
                ActionResult.Success
            },
            "2026-07-18T10:00:00Z",
            journal = journal,
        ).onTrigger(envelope("alarm:p4-loop", timeEvent(automation))) { DeviceState() }

        assertEquals(listOf(0, 2, 3, 5), contexts.map { it.actionIndex })
        assertEquals(
            listOf(
                "1.while[1].1", "1.while[1].2", "1.while[1].3",
                "1.while[2].1", "1.while[2].2", "1.while[2].3",
            ),
            journal.actions.map { it.actionPath },
        )
        assertEquals((0..5).toList(), journal.actions.map { it.actionIndex })
    }

    @Test
    fun `P4 generative capture flows through the resolved executor and captures concrete text`() = runTest {
        // P4-D2 slice 2: la barriera p4_generative_unavailable è stata rimossa insieme al wiring. Con
        // il ResolvedActionExecutor la foglia CAPTURE riceve testo concreto (Success + capturedText):
        // l'interprete cattura e journala SUCCEEDED, mai capture_missing.
        val capture = Action.InvokeLlm(
            goal = "riassumi",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            captureAs = "summary",
        )
        val automation = armed(
            id = "p4-capture",
            t = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            acts = listOf(
                Action.If(Condition.BooleanLiteral(true), then = listOf(capture)),
            ),
        )
        val flatExecutor = FakeActionExecutor()
        val journal = FakeExecutionJournal()
        val seen = mutableListOf<ResolvedProgramAction>()
        val resolvedExecutor = ResolvedActionExecutor { action, _ ->
            seen += action
            ProgramActionResult(ActionResult.Success, capturedText = "riassunto")
        }

        val result = engine(
            FakeAutomationStore(listOf(automation)),
            flatExecutor,
            "2026-07-18T10:00:00Z",
            journal = journal,
            resolvedExecutor = resolvedExecutor,
        ).onTrigger(envelope("alarm:p4-capture", timeEvent(automation))) { DeviceState() }
            .single()

        assertTrue(flatExecutor.executed.isEmpty(), "il boundary flat non deve vedere la foglia generativa")
        assertEquals(1, seen.size)
        assertEquals(ActionResult.Success, result.results.single())
        assertEquals(ActionJournalOutcome.SUCCEEDED, journal.actions.single().outcome)
        assertEquals(ExecutionStatus.SUCCEEDED, journal.completions.single().status)
    }

    @Test
    fun `P4 capture without a resolved executor stays capture fail closed`() = runTest {
        // La barriera p4_capture_unavailable RESTA: senza executor risolto il boundary flat sa solo
        // SUBMITTED, quindi una capture non è realizzabile — mai un capture_missing degradato.
        val capture = Action.InvokeLlm(
            goal = "riassumi",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            captureAs = "summary",
        )
        val automation = armed(
            id = "p4-capture-closed",
            t = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            acts = listOf(
                Action.If(Condition.BooleanLiteral(true), then = listOf(capture)),
            ),
        )
        val flatExecutor = FakeActionExecutor()
        val journal = FakeExecutionJournal()

        val result = engine(
            FakeAutomationStore(listOf(automation)),
            flatExecutor,
            "2026-07-18T10:00:00Z",
            journal = journal,
        ).onTrigger(envelope("alarm:p4-capture-closed", timeEvent(automation))) { DeviceState() }
            .single()

        assertTrue(flatExecutor.executed.isEmpty())
        assertEquals(
            "p4_capture_unavailable",
            (result.results.single() as ActionResult.Failure).reason,
        )
        assertEquals("p4_capture_unavailable", journal.actions.single().errorCode)
        assertEquals(ExecutionStatus.FAILED, journal.completions.single().status)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `disabling a waiting P4 program cancels only that rule and continues the batch`() = runTest {
        val waiting = armed(
            id = "p4-waiting",
            t = Trigger.Notification("com.example"),
            acts = listOf(
                Action.Wait(60_000),
                Action.ShowNotification("Argus", "must not run"),
            ),
            prio = 0,
        )
        val next = armed(
            id = "next-rule",
            t = Trigger.Notification("com.example"),
            acts = listOf(Action.ShowNotification("Argus", "next runs")),
            prio = 1,
        )
        val store = FakeAutomationStore(listOf(waiting, next))
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        val executed = mutableListOf<Action>()
        val eventId = TriggerEventId("notification:p4-cancel")
        val pending = async {
            engine(
                store,
                ActionExecutor { action, _ ->
                    executed += action
                    ActionResult.Success
                },
                "2026-07-18T10:00:00Z",
                audit = audit,
                journal = journal,
            ).onTrigger(
                TriggerEnvelope(eventId, TriggerEvent.NotificationPosted("com.example")),
            ) { DeviceState() }
        }

        runCurrent()
        assertTrue(!pending.isCompleted)
        store.disable(waiting.id)
        runCurrent()

        assertEquals(listOf(next.id), pending.await().map { it.automation.id })
        assertEquals(listOf<Action>(Action.ShowNotification("Argus", "next runs")), executed)
        assertEquals(
            ExecutionStatus.CANCELLED,
            journal.completions.first {
                it.executionId == StableExecutionIdFactory.create(waiting.id, eventId)
            }.status,
        )
        assertTrue(audit.events.any { it.automationId == waiting.id && it.detail == "cancelled" })
        assertTrue(audit.events.none { it.automationId == waiting.id && it.kind == AuditKind.FIRED })
    }

    @Test
    fun `time trigger fires deterministic action when condition holds`() = runTest {
        val a = armed(
            "a1",
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
            cond = Condition.StateEquals("ringer", CmpOp.NEQ, "silent"),
        )
        val ex = FakeActionExecutor()
        val store = FakeAutomationStore(listOf(a))
        engine(store, ex, "2026-07-12T21:30:00Z")
            .onTrigger(envelope("alarm:a1:1", timeEvent(a))) {
                DeviceState(values = mapOf("ringer" to "normal"))
            }
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.PRIORITY)), ex.executed)
    }

    @Test
    fun `condition false skips actions and audits event identity`() = runTest {
        val a = armed(
            "a1",
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetWifi(false)),
            cond = Condition.StateEquals("ringer", CmpOp.EQ, "silent"),
        )
        val ex = FakeActionExecutor()
        val store = FakeAutomationStore(listOf(a))
        val audit = FakeAuditSink()
        engine(store, ex, "2026-07-12T21:30:00Z", audit)
            .onTrigger(envelope("alarm:a1:1", timeEvent(a))) {
                DeviceState(values = mapOf("ringer" to "normal"))
            }
        assertEquals(emptyList<Action>(), ex.executed)
        assertEquals(AuditKind.CONDITIONS_NOT_MET, audit.events.single().kind)
        assertEquals(TriggerEventId("alarm:a1:1"), audit.events.single().eventId)
    }

    @Test
    fun `state provider is lazy - not called when nothing matches`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)))
        val ex = FakeActionExecutor()
        val store = FakeAutomationStore(listOf(a))
        var reads = 0
        engine(store, ex, "2026-07-12T10:00:00Z")
            .onTrigger(envelope("sbn:telegram:1", TriggerEvent.NotificationPosted("com.telegram"))) {
                reads++
                DeviceState()
            }
        assertEquals(0, reads)
        assertEquals(emptyList<Action>(), ex.executed)
    }

    @Test
    fun `higher priority executes LAST and wins on shared target`() = runTest {
        val low = armed("low", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), prio = 1)
        val high = armed("high", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(true)), prio = 10)
        val ex = FakeActionExecutor()
        val store = FakeAutomationStore(listOf(high, low))
        engine(store, ex, "2026-07-12T10:00:00Z")
            .onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
        assertEquals(listOf<Action>(Action.SetWifi(false), Action.SetWifi(true)), ex.executed)
    }

    @Test
    fun `exception in one automation is isolated and does not break the batch`() = runTest {
        val bad = armed("bad", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), prio = 0)
        val good = armed("good", Trigger.Notification("com.whatsapp"), listOf(Action.SetBluetooth(true)), prio = 1)
        val store = FakeAutomationStore(listOf(bad, good))
        val audit = FakeAuditSink()
        val throwing = object : ActionExecutor {
            val executed = mutableListOf<Action>()
            override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
                if (ctx.automationId == AutomationId("bad")) throw IllegalStateException("boom")
                executed += action
                return ActionResult.Success
            }
        }
        val outcomes = engine(store, throwing, "2026-07-12T10:00:00Z", audit)
            .onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
        assertEquals(listOf<Action>(Action.SetBluetooth(true)), throwing.executed)
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.first { it.automation.id == AutomationId("bad") }.results.single() is ActionResult.Failure)
        assertTrue(audit.events.any { it.kind == AuditKind.FIRED && it.automationId == AutomationId("bad") })
    }

    @Test
    fun `exception in one action becomes failure and remaining actions still run`() = runTest {
        val a = armed(
            "a1",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetWifi(false), Action.SetBluetooth(true)),
        )
        val executed = mutableListOf<Action>()
        val throwing = object : ActionExecutor {
            override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
                executed += action
                if (action is Action.SetWifi) error("wifi failed")
                return ActionResult.Success
            }
        }
        val outcome = engine(FakeAutomationStore(listOf(a)), throwing, "2026-07-12T10:00:00Z")
            .onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
            .single()
        assertEquals(a.actions, executed)
        assertTrue(outcome.results.first() is ActionResult.Failure)
        assertEquals(ActionResult.Success, outcome.results.last())
    }

    @Test
    fun `cancellation propagates and is never converted to audit error`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)))
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        val cancelling = object : ActionExecutor {
            override suspend fun execute(action: Action, ctx: FireContext): ActionResult =
                throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            engine(
                FakeAutomationStore(listOf(a)),
                cancelling,
                "2026-07-12T10:00:00Z",
                audit,
                journal,
            )
                .onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
        }
        assertEquals(emptyList(), audit.events)
        assertEquals(ExecutionStatus.CANCELLED, journal.completions.single().status)
    }

    @Test
    fun `journal correlates ordered action outcomes and redacts failure detail`() = runTest {
        val a = armed(
            "a1",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetWifi(false), Action.SetBluetooth(true)),
        )
        val journal = FakeExecutionJournal()
        val outcomes = engine(
            FakeAutomationStore(listOf(a)),
            FakeActionExecutor(fail = setOf("SetWifi")),
            "2026-07-12T10:00:00Z",
            journal = journal,
        ).onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }

        val executionId = outcomes.single().executionId
        assertEquals(listOf(0, 1), journal.actions.map { it.actionIndex })
        assertTrue(journal.actions.all { it.executionId == executionId })
        assertEquals(listOf("set_wifi", "set_bluetooth"), journal.actions.map { it.actionType })
        assertEquals(ActionJournalOutcome.FAILED, journal.actions.first().outcome)
        assertEquals("action_failed", journal.actions.first().errorCode)
        val completion = journal.completions.single()
        assertEquals(ExecutionStatus.PARTIAL, completion.status)
        assertEquals(1, completion.failedCount)
        assertEquals(1, completion.succeededCount)
    }

    @Test
    fun `submitted action keeps mixed execution open despite synchronous failure`() = runTest {
        val generative = Action.InvokeLlm(
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
        )
        val automation = armed(
            "a1",
            Trigger.Notification("com.whatsapp"),
            listOf(generative, Action.SetWifi(false)),
        )
        val journal = FakeExecutionJournal()

        engine(
            FakeAutomationStore(listOf(automation)),
            FakeActionExecutor(fail = setOf("SetWifi")),
            "2026-07-12T10:00:00Z",
            journal = journal,
        ).onTrigger(envelope("sbn:wa:mixed", TriggerEvent.NotificationPosted("com.whatsapp"))) {
            DeviceState()
        }

        journal.completions.single().also { completion ->
            assertEquals(ExecutionStatus.SUBMITTED, completion.status)
            assertEquals(1, completion.submittedCount)
            assertEquals(1, completion.failedCount)
        }
    }

    @Test
    fun `audit sink failure cannot change successful execution`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)))
        val outcome = engine(
            FakeAutomationStore(listOf(a)),
            FakeActionExecutor(),
            "2026-07-12T10:00:00Z",
            audit = object : AuditSink {
                override suspend fun record(e: AuditEvent) = error("audit offline")
            },
        ).onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }

        assertEquals(ActionResult.Success, outcome.single().results.single())
    }

    @Test
    fun `same event is claimed once even under concurrent redelivery`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)))
        val ex = FakeActionExecutor()
        val store = FakeAutomationStore(listOf(a))
        val audit = FakeAuditSink()
        val engine = engine(store, ex, "2026-07-12T10:00:00Z", audit)
        val event = envelope("sbn:wa:stable", TriggerEvent.NotificationPosted("com.whatsapp"))

        coroutineScope {
            (1..20).map { async { engine.onTrigger(event) { DeviceState() } } }.awaitAll()
        }

        assertEquals(1, ex.executed.size)
        assertEquals(1, audit.events.count { it.kind == AuditKind.FIRED })
        assertEquals(19, audit.events.count { it.kind == AuditKind.SUPPRESSED_DUPLICATE })
        val fired = audit.events.single { it.kind == AuditKind.FIRED }
        assertEquals(TriggerEventId("sbn:wa:stable"), fired.eventId)
        assertTrue(fired.executionId != null)
    }

    @Test
    fun `cooldown claim is atomic across different events`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), cooldown = 5_000)
        val ex = FakeActionExecutor()
        val store = FakeAutomationStore(listOf(a))
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        var now = 1_000L
        val engine = engine(store, ex, "2026-07-12T10:00:00Z", audit, journal, now = { now })

        engine.onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
        now = 2_000L
        engine.onTrigger(envelope("sbn:wa:2", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }

        assertEquals(1, ex.executed.size)
        assertEquals(1, audit.events.count { it.kind == AuditKind.SUPPRESSED_COOLDOWN })
        assertEquals(
            listOf(ExecutionStatus.SUCCEEDED, ExecutionStatus.SUPPRESSED_COOLDOWN),
            journal.completions.map { it.status },
        )
    }

    @Test
    fun `generative rules get a minimum 60s cooldown even when configured zero`() = runTest {
        val a = armed(
            "a1",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.InvokeLlm("reply", listOf("notification"), listOf("whatsapp_reply"), true)),
            cooldown = 0,
        )
        val ex = FakeActionExecutor()
        val store = FakeAutomationStore(listOf(a))
        var now = 1_000L
        val engine = engine(store, ex, "2026-07-12T10:00:00Z", now = { now })
        engine.onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
        now = 31_000L
        engine.onTrigger(envelope("sbn:wa:2", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
        assertEquals(1, ex.executed.size)
        now = 62_000L
        engine.onTrigger(envelope("sbn:wa:3", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
        assertEquals(2, ex.executed.size)
    }

    @Test
    fun `fire-time policy block pauses rule before state read or action`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)))
        val ex = FakeActionExecutor()
        val store = FakeAutomationStore(listOf(a))
        val audit = FakeAuditSink()
        val revoked = FirePolicy { _, _ -> FirePolicyDecision.Block("capability_revoked", needsReview = true) }
        var stateReads = 0

        engine(store, ex, "2026-07-12T10:00:00Z", audit, policy = revoked)
            .onTrigger(envelope("sbn:wa:1", TriggerEvent.NotificationPosted("com.whatsapp"))) {
                stateReads++
                DeviceState()
            }

        assertEquals(0, stateReads)
        assertEquals(emptyList(), ex.executed)
        assertEquals(AutomationStatus.NEEDS_REVIEW, store.get(a.id)?.status)
        // La quarantena è un evento lifecycle esplicito, poi il blocco fire-time col code esatto.
        assertEquals(
            listOf(AuditKind.RULE_NEEDS_REVIEW, AuditKind.BLOCKED_POLICY),
            audit.events.map { it.kind },
        )
        assertEquals("fire_policy", audit.events.first().detail)
        assertEquals(a.id, audit.events.first().automationId)
    }

    @Test
    fun `execution identity is deterministic and reaches action context and outcome`() = runTest {
        val a = armed(
            "a1",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetWifi(false)),
            prio = 7,
        )
        var captured: FireContext? = null
        val executor = ActionExecutor { _, ctx ->
            captured = ctx
            ActionResult.Success
        }
        val outcome = engine(FakeAutomationStore(listOf(a)), executor, "2026-07-12T10:00:00Z")
            .onTrigger(envelope("sbn:wa:stable", TriggerEvent.NotificationPosted("com.whatsapp"))) { DeviceState() }
            .single()

        assertEquals(TriggerEventId("sbn:wa:stable"), captured?.eventId)
        assertEquals(outcome.executionId, captured?.executionId)
        assertEquals(a.approvalFingerprint, captured?.approvalFingerprint)
        assertEquals(0, captured?.actionIndex)
        assertEquals(7, captured?.priority)
        assertEquals(
            StableExecutionIdFactory.create(a.id, TriggerEventId("sbn:wa:stable")),
            outcome.executionId,
        )
    }

    @Test
    fun `stale scheduled trigger cannot execute a newly approved automation`() = runTest {
        val unsigned = armed(
            "a1",
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.TOTAL)),
        )
        val current = unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
        val executor = FakeActionExecutor()
        val audit = FakeAuditSink()

        engine(FakeAutomationStore(listOf(current)), executor, "2026-07-12T21:30:00Z", audit)
            .onTrigger(
                envelope(
                    "alarm:stale",
                    TriggerEvent.TimeFired(
                        current.id,
                        ApprovalFingerprint("0".repeat(64)),
                    ),
                ),
            ) { DeviceState() }

        assertEquals(emptyList(), executor.executed)
        assertEquals(AuditKind.BLOCKED_POLICY, audit.events.single().kind)
        assertEquals("stale_trigger_registration", audit.events.single().detail)
    }

    @Test
    fun `matching deterministic rule without state needs performs zero reads`() = runTest {
        val automation = armed(
            "no-state",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.ShowNotification("titolo", "testo")),
        )
        var reads = 0
        var actionState: DeviceState? = null
        val executor = ActionExecutor { _, context ->
            actionState = context.state
            ActionResult.Success
        }

        engine(FakeAutomationStore(listOf(automation)), executor, "2026-07-12T10:00:00Z")
            .onTrigger(
                envelope("sbn:wa:no-state", TriggerEvent.NotificationPosted("com.whatsapp")),
            ) { _ ->
                reads++
                DeviceState(values = mapOf(StateKeys.WIFI to "on"))
            }

        assertEquals(0, reads)
        assertEquals(DeviceState(), actionState)
    }

    @Test
    fun `condition requests only its single state key`() = runTest {
        val automation = armed(
            "one-key",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
            cond = Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "normal"),
        )
        val requests = mutableListOf<StateReadRequest>()

        engine(
            FakeAutomationStore(listOf(automation)),
            FakeActionExecutor(),
            "2026-07-12T10:00:00Z",
        ).onTrigger(
            envelope("sbn:wa:one-key", TriggerEvent.NotificationPosted("com.whatsapp")),
        ) { request ->
            requests += request
            DeviceState(values = mapOf(StateKeys.RINGER to "normal"))
        }

        assertEquals(
            listOf(StateReadRequest(keys = setOf(StateKeys.RINGER))),
            requests,
        )
    }

    @Test
    fun `typed condition requests only its exact parametric query`() = runTest {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val automation = armed(
            "query",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.ShowNotification("Argus", "voltaggio")),
            cond = Condition.StateCompare(
                query,
                StateValueType.NUMBER,
                CmpOp.GT,
                "4000",
            ),
        )
        val requests = mutableListOf<StateReadRequest>()

        val outcomes = engine(
            FakeAutomationStore(listOf(automation)),
            FakeActionExecutor(),
            "2026-07-12T10:00:00Z",
        ).onTrigger(
            envelope("sbn:wa:query", TriggerEvent.NotificationPosted("com.whatsapp")),
        ) { request ->
            requests += request
            DeviceState(queryValues = mapOf(query.canonicalId to "4200"))
        }

        assertEquals(1, outcomes.size)
        assertEquals(listOf(StateReadRequest(queries = setOf(query))), requests)
    }

    @Test
    fun `batch cache reads only the missing delta and keeps prior values`() = runTest {
        val wifi = armed(
            "a-wifi",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetWifi(false)),
            cond = Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "on"),
        )
        val wifiAndBattery = armed(
            "b-battery",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetBluetooth(true)),
            cond = Condition.And(
                listOf(
                    Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "on"),
                    Condition.StateEquals(StateKeys.BATTERY, CmpOp.GT, "20"),
                ),
            ),
        )
        val requests = mutableListOf<StateReadRequest>()

        val outcomes = engine(
            FakeAutomationStore(listOf(wifiAndBattery, wifi)),
            FakeActionExecutor(),
            "2026-07-12T10:00:00Z",
        ).onTrigger(
            envelope("sbn:wa:cache", TriggerEvent.NotificationPosted("com.whatsapp")),
        ) { request ->
            requests += request
            DeviceState(
                values = buildMap {
                    if (StateKeys.WIFI in request.keys) put(StateKeys.WIFI, "on")
                    if (StateKeys.BATTERY in request.keys) put(StateKeys.BATTERY, "80")
                },
            )
        }

        assertEquals(listOf(wifi.id, wifiAndBattery.id), outcomes.map { it.automation.id })
        assertEquals(
            listOf(
                StateReadRequest(keys = setOf(StateKeys.WIFI)),
                StateReadRequest(keys = setOf(StateKeys.BATTERY)),
            ),
            requests,
        )
    }

    @Test
    fun `foreground and location are requested independently`() = runTest {
        val foreground = armed(
            "a-foreground",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetWifi(false)),
            cond = Condition.AppInForeground("dev.example"),
        )
        val location = armed(
            "b-location",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetBluetooth(true)),
            cond = Condition.LocationIn(45.0, 9.0, 100.0),
        )
        val requests = mutableListOf<StateReadRequest>()

        val outcomes = engine(
            FakeAutomationStore(listOf(location, foreground)),
            FakeActionExecutor(),
            "2026-07-12T10:00:00Z",
        ).onTrigger(
            envelope("sbn:wa:independent", TriggerEvent.NotificationPosted("com.whatsapp")),
        ) { request ->
            requests += request
            DeviceState(
                foregroundApp = "dev.example".takeIf { request.includeForegroundApp },
                location = GeoPoint(45.0, 9.0).takeIf { request.includeLocation },
            )
        }

        assertEquals(listOf(foreground.id, location.id), outcomes.map { it.automation.id })
        assertEquals(
            listOf(
                StateReadRequest(includeForegroundApp = true),
                StateReadRequest(includeLocation = true),
            ),
            requests,
        )
    }

    @Test
    fun `state provider exception becomes unavailable condition and never fires`() = runTest {
        val automation = armed(
            "state-outage",
            Trigger.Notification("com.whatsapp"),
            listOf(Action.SetWifi(false)),
            cond = Condition.Not(
                Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "on"),
            ),
        )
        val audit = FakeAuditSink()
        val executor = FakeActionExecutor()

        engine(
            FakeAutomationStore(listOf(automation)),
            executor,
            "2026-07-12T10:00:00Z",
            audit = audit,
        ).onTrigger(
            envelope("sbn:wa:outage", TriggerEvent.NotificationPosted("com.whatsapp")),
        ) { _ -> error("reader unavailable") }

        assertEquals(emptyList(), executor.executed)
        assertEquals(AuditKind.CONDITIONS_NOT_MET, audit.events.single().kind)
        assertEquals("condition_state_unavailable", audit.events.single().detail)
    }

    @Test
    fun `only invoke llm actions declaring state receive the legacy state profile`() = runTest {
        val withoutState = Action.InvokeLlm(
            "prima",
            listOf(GenerativeContract.CONTEXT_NOTIFICATION),
            listOf(GenerativeContract.TOOL_WHATSAPP_REPLY),
            true,
        )
        val withState = Action.InvokeLlm(
            "seconda",
            listOf(
                GenerativeContract.CONTEXT_NOTIFICATION,
                GenerativeContract.CONTEXT_STATE,
            ),
            listOf(GenerativeContract.TOOL_WHATSAPP_REPLY),
            true,
        )
        val automation = armed(
            "generative-state",
            Trigger.Notification("com.whatsapp"),
            listOf(withoutState, withState),
        )
        val contexts = mutableListOf<DeviceState>()
        val requests = mutableListOf<StateReadRequest>()
        val executor = ActionExecutor { _, context ->
            contexts += context.state
            ActionResult.Submitted
        }

        engine(FakeAutomationStore(listOf(automation)), executor, "2026-07-12T10:00:00Z")
            .onTrigger(
                envelope("sbn:wa:generative", TriggerEvent.NotificationPosted("com.whatsapp")),
            ) { request ->
                requests += request
                DeviceState(
                    values = request.keys.associateWith { "known" },
                    foregroundApp = "dev.example".takeIf { request.includeForegroundApp },
                )
            }

        assertEquals(listOf(StateReadRequest.LEGACY_GENERATIVE), requests)
        assertEquals(DeviceState(), contexts.first())
        assertEquals(StateKeys.ALL.keys, contexts.last().values.keys)
        assertEquals("dev.example", contexts.last().foregroundApp)
        assertEquals(null, contexts.last().location)
    }

    @Test
    fun `invoke llm v2 requests only fingerprinted parametric readers`() {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val action = Action.InvokeLlmV2(
            goal = "rispondi",
            stateContext = listOf(
                ApprovedStateContext(
                    query = query,
                    valueType = StateValueType.NUMBER,
                    policyVersion = StateQueryPolicy.VERSION,
                    integrity = IntegrityLabel.CLEAN,
                    confidentiality = ConfidentialityLabel.SECRET,
                ),
            ),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 60_000,
        )

        assertEquals(
            StateReadRequest(queries = setOf(query)),
            StateReadPlanner.forAction(action),
        )
    }
}
