package dev.argus.engine.runtime

import dev.argus.engine.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    ): Automation {
        val unsigned = Automation(
            AutomationId(id), id, CreatedBy.LLM, AutomationStatus.ARMED, t, acts, cond,
            cooldownMs = cooldown, priority = prio,
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
    ) = Engine(
        store = store,
        executor = ex,
        evaluator = ConditionEvaluator(clock(clockIso)),
        matcher = TriggerMatcher(),
        firePolicy = policy,
        audit = audit,
        journal = journal,
        now = now,
    )

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
        assertEquals(AuditKind.BLOCKED_POLICY, audit.events.single().kind)
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
}
