package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalEntry
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.SubmittedActionCompletion
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RoomExecutionJournalTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var drafts: RoomDraftRepository
    private lateinit var journal: RoomExecutionJournal

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        drafts = RoomDraftRepository(db)
        journal = RoomExecutionJournal(db.executionJournalDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `claim starts execution atomically and action results finish it with correlation`() = runTest {
        val automationId = arm("journal", listOf(Action.SetWifi(false), Action.SetBluetooth(true)))
        val executionId = ExecutionId("exec-journal")
        assertEquals(FireClaimResult.Claimed, store.claimFire(claim(automationId, "event-sensitive", executionId, 1_000)))

        val running = db.executionJournalDao().execution(executionId.value)!!
        assertEquals(ExecutionStatus.RUNNING, running.status)
        assertEquals(identifierHash("event-sensitive"), running.eventIdHash)

        journal.recordAction(
            ActionJournalEntry(executionId, 0, "set_wifi", ActionJournalOutcome.SUCCEEDED, 1_001),
        )
        journal.recordAction(
            ActionJournalEntry(
                executionId,
                1,
                "set_bluetooth",
                ActionJournalOutcome.FAILED,
                1_002,
                errorCode = "action_failed",
            ),
        )
        journal.finish(ExecutionCompletion(executionId, ExecutionStatus.PARTIAL, 1_003, 1, 1, 0))

        val finished = db.executionJournalDao().execution(executionId.value)!!
        assertEquals(ExecutionStatus.PARTIAL, finished.status)
        assertEquals(1, finished.succeededCount)
        assertEquals(1, finished.failedCount)
        assertEquals(listOf(0, 1), db.executionJournalDao().actions(executionId.value).map { it.actionIndex })
        assertEquals("action_failed", db.executionJournalDao().actions(executionId.value)[1].errorCode)

        // Retry identico ammesso; una completion discordante non può riscrivere il terminale.
        journal.finish(ExecutionCompletion(executionId, ExecutionStatus.PARTIAL, 1_003, 1, 1, 0))
        assertFailsWith<IllegalStateException> {
            journal.finish(ExecutionCompletion(executionId, ExecutionStatus.PARTIAL, 1_003, 2, 0, 0))
        }
        assertEquals(1, db.executionJournalDao().execution(executionId.value)?.succeededCount)
    }

    @Test
    fun `cooldown and disabled claims are terminal in the same transaction`() = runTest {
        val automationId = arm("suppressed", listOf(Action.SetWifi(false)))
        val first = ExecutionId("exec-first")
        store.claimFire(claim(automationId, "event-1", first, 1_000))
        journal.finish(ExecutionCompletion(first, ExecutionStatus.SUCCEEDED, 1_000, 1, 0, 0))

        val cooldown = ExecutionId("exec-cooldown")
        assertIs<FireClaimResult.Cooldown>(
            store.claimFire(claim(automationId, "event-2", cooldown, 2_000, cooldownMs = 5_000)),
        )
        // L'engine emette la stessa completion: deve essere idempotente col terminale atomico del claim.
        journal.finish(
            ExecutionCompletion(cooldown, ExecutionStatus.SUPPRESSED_COOLDOWN, 2_000, 0, 0, 0),
        )
        assertEquals(
            ExecutionStatus.SUPPRESSED_COOLDOWN,
            db.executionJournalDao().execution(cooldown.value)?.status,
        )

        store.disable(automationId)
        val disabled = ExecutionId("exec-disabled")
        assertEquals(
            FireClaimResult.NotEligible,
            store.claimFire(claim(automationId, "event-3", disabled, 7_000)),
        )
        journal.finish(
            ExecutionCompletion(disabled, ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE, 7_000, 0, 0, 0),
        )
        assertEquals(
            ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE,
            db.executionJournalDao().execution(disabled.value)?.status,
        )
    }

    @Test
    fun `async action waits for submitted handshake and resolves exactly once as deferred`() = runTest {
        val automationId = arm("async-deferred", listOf(Action.InvokeLlm(
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
        )))
        val executionId = ExecutionId("exec-async-deferred")
        store.claimFire(claim(automationId, "event-async", executionId, 1_000))

        val ready = async {
            journal.observeSubmission(executionId, actionIndex = 0)
                .first { it?.ready == true }
        }
        advanceUntilIdle()
        assertFalse(ready.isCompleted)

        journal.recordAction(
            ActionJournalEntry(
                executionId,
                0,
                "invoke_llm",
                ActionJournalOutcome.SUBMITTED,
                1_001,
            ),
        )
        advanceUntilIdle()
        assertFalse(ready.isCompleted, "La sola action row non basta finché l'Engine è RUNNING")

        journal.finish(
            ExecutionCompletion(executionId, ExecutionStatus.SUBMITTED, 1_002, 0, 0, 1),
        )
        assertTrue(ready.await()?.ready == true)

        val completion = SubmittedActionCompletion(
            executionId = executionId,
            actionIndex = 0,
            outcome = ActionJournalOutcome.DEFERRED,
            atMillis = 1_100,
            errorCode = "reply_channel_expired",
        )
        assertTrue(journal.resolveSubmitted(completion))
        assertFalse(journal.resolveSubmitted(completion), "La completion non può essere applicata due volte")

        val action = db.executionJournalDao().actions(executionId.value).single()
        assertEquals(ActionJournalOutcome.DEFERRED, action.outcome)
        assertEquals("reply_channel_expired", action.errorCode)
        val execution = db.executionJournalDao().execution(executionId.value)!!
        assertEquals(ExecutionStatus.DEFERRED, execution.status)
        assertEquals(0, execution.submittedCount)
        assertEquals(1, execution.deferredCount)
    }

    @Test
    fun `async actions keep execution submitted until the last result then recompute aggregate`() = runTest {
        val generative = Action.InvokeLlm(
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
        )
        val automationId = arm("async-aggregate", listOf(generative, generative))
        val executionId = ExecutionId("exec-async-aggregate")
        store.claimFire(claim(automationId, "event-async-aggregate", executionId, 1_000))
        repeat(2) { index ->
            journal.recordAction(
                ActionJournalEntry(
                    executionId,
                    index,
                    "invoke_llm",
                    ActionJournalOutcome.SUBMITTED,
                    1_001,
                ),
            )
        }
        journal.finish(
            ExecutionCompletion(executionId, ExecutionStatus.SUBMITTED, 1_002, 0, 0, 2),
        )

        assertFalse(
            journal.resolveSubmitted(
                SubmittedActionCompletion(
                    executionId,
                    actionIndex = 2,
                    outcome = ActionJournalOutcome.SUCCEEDED,
                    atMillis = 1_050,
                ),
            ),
            "Un indice azione estraneo all'esecuzione non può essere risolto",
        )
        assertTrue(
            journal.resolveSubmitted(
                SubmittedActionCompletion(
                    executionId,
                    actionIndex = 0,
                    outcome = ActionJournalOutcome.SUCCEEDED,
                    atMillis = 1_100,
                ),
            ),
        )
        db.executionJournalDao().execution(executionId.value)!!.also { pending ->
            assertEquals(ExecutionStatus.SUBMITTED, pending.status)
            assertEquals(1, pending.succeededCount)
            assertEquals(1, pending.submittedCount)
        }

        assertTrue(
            journal.resolveSubmitted(
                SubmittedActionCompletion(
                    executionId,
                    actionIndex = 1,
                    outcome = ActionJournalOutcome.FAILED,
                    atMillis = 1_200,
                    errorCode = "brain_failed",
                ),
            ),
        )
        db.executionJournalDao().execution(executionId.value)!!.also { finished ->
            assertEquals(ExecutionStatus.PARTIAL, finished.status)
            assertEquals(1, finished.succeededCount)
            assertEquals(1, finished.failedCount)
            assertEquals(0, finished.submittedCount)
        }
    }

    @Test
    fun `resolveSubmitted con suppressedStatus marca la fire claim SUPPRESSED_BUDGET`() = runTest {
        val automationId = arm("budget-block", listOf(Action.InvokeLlm(
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
        )))
        val executionId = ExecutionId("exec-budget-block")
        store.claimFire(claim(automationId, "event-budget-block", executionId, 1_000))
        journal.recordAction(
            ActionJournalEntry(executionId, 0, "invoke_llm", ActionJournalOutcome.SUBMITTED, 1_001),
        )
        journal.finish(
            ExecutionCompletion(executionId, ExecutionStatus.SUBMITTED, 1_002, 0, 0, 1),
        )

        assertTrue(
            journal.resolveSubmitted(
                SubmittedActionCompletion(
                    executionId = executionId,
                    actionIndex = 0,
                    outcome = ActionJournalOutcome.FAILED,
                    atMillis = 1_100,
                    errorCode = "budget_exceeded",
                    suppressedStatus = ExecutionStatus.SUPPRESSED_BUDGET,
                ),
            ),
        )

        assertEquals(
            ExecutionStatus.SUPPRESSED_BUDGET,
            db.executionJournalDao().status(executionId.value),
        )
    }

    @Test
    fun `resolveSubmitted con azioni miste ignora suppressedStatus`() = runTest {
        val generative = Action.InvokeLlm(
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
        )
        val automationId = arm("budget-mixed", listOf(generative, generative))
        val executionId = ExecutionId("exec-budget-mixed")
        store.claimFire(claim(automationId, "event-budget-mixed", executionId, 1_000))
        repeat(2) { index ->
            journal.recordAction(
                ActionJournalEntry(executionId, index, "invoke_llm", ActionJournalOutcome.SUBMITTED, 1_001),
            )
        }
        journal.finish(
            ExecutionCompletion(executionId, ExecutionStatus.SUBMITTED, 1_002, 0, 0, 2),
        )

        assertTrue(
            journal.resolveSubmitted(
                SubmittedActionCompletion(
                    executionId, 0, ActionJournalOutcome.SUCCEEDED, 1_050,
                ),
            ),
        )
        assertTrue(
            journal.resolveSubmitted(
                SubmittedActionCompletion(
                    executionId = executionId,
                    actionIndex = 1,
                    outcome = ActionJournalOutcome.FAILED,
                    atMillis = 1_100,
                    errorCode = "budget_exceeded",
                    suppressedStatus = ExecutionStatus.SUPPRESSED_BUDGET,
                ),
            ),
        )

        assertEquals(
            ExecutionStatus.PARTIAL,
            db.executionJournalDao().status(executionId.value),
        )
    }

    @Test
    fun `maintenance interrupts stale submitted work and never leaves it pending forever`() = runTest {
        val automationId = arm("stale-submitted", listOf(Action.InvokeLlm(
            "rispondi",
            listOf("notification"),
            listOf("whatsapp_reply"),
            true,
        )))
        val executionId = ExecutionId("exec-stale-submitted")
        store.claimFire(claim(automationId, "event-stale-submitted", executionId, 1_000))
        journal.recordAction(
            ActionJournalEntry(
                executionId,
                0,
                "invoke_llm",
                ActionJournalOutcome.SUBMITTED,
                1_001,
            ),
        )
        journal.finish(
            ExecutionCompletion(executionId, ExecutionStatus.SUBMITTED, 1_002, 0, 0, 1),
        )

        val result = RoomJournalMaintenance(
            db,
            JournalRetentionPolicy(runningStaleAfterMillis = 100),
        ).run(nowMillis = 2_000)

        assertEquals(1, result.interrupted)
        assertEquals(
            ExecutionStatus.INTERRUPTED,
            db.executionJournalDao().execution(executionId.value)?.status,
        )
    }

    @Test
    fun `reactive UI action query is bounded by the recent audit window`() = runTest {
        val automationId = arm("bounded-log", listOf(Action.SetWifi(false)))
        val old = ExecutionId("exec-ui-old")
        val recent = ExecutionId("exec-ui-recent")
        store.claimFire(claim(automationId, "event-ui-old", old, 1_000))
        store.claimFire(claim(automationId, "event-ui-recent", recent, 2_000))
        journal.recordAction(
            ActionJournalEntry(old, 0, "old_action", ActionJournalOutcome.SUCCEEDED, 1_001),
        )
        journal.recordAction(
            ActionJournalEntry(recent, 0, "recent_action", ActionJournalOutcome.SUCCEEDED, 2_001),
        )
        val sink = RoomAuditSink(db.auditDao())
        sink.record(AuditEvent(automationId, AuditKind.FIRED, 1_000, executionId = old))
        sink.record(AuditEvent(automationId, AuditKind.FIRED, 2_000, executionId = recent))

        val visible = db.executionJournalDao().observeRecentActions(auditLimit = 1).first()

        assertEquals(listOf("recent_action"), visible.map { it.actionType })
    }

    @Test
    fun `automation action query is not displaced by newer events from other rules`() = runTest {
        val targetId = arm("target-log", listOf(Action.SetWifi(false)))
        val noiseId = arm("noise-log", listOf(Action.SetBluetooth(true)))
        val targetExecution = ExecutionId("exec-target-ui")
        val noiseExecution = ExecutionId("exec-noise-ui")
        store.claimFire(claim(targetId, "event-target-ui", targetExecution, 1_000))
        store.claimFire(claim(noiseId, "event-noise-ui", noiseExecution, 2_000))
        journal.recordAction(
            ActionJournalEntry(
                targetExecution,
                0,
                "target_action",
                ActionJournalOutcome.SUCCEEDED,
                1_001,
            ),
        )
        journal.recordAction(
            ActionJournalEntry(
                noiseExecution,
                0,
                "noise_action",
                ActionJournalOutcome.SUCCEEDED,
                2_001,
            ),
        )
        val sink = RoomAuditSink(db.auditDao())
        sink.record(AuditEvent(targetId, AuditKind.FIRED, 1_000, executionId = targetExecution))
        sink.record(AuditEvent(noiseId, AuditKind.FIRED, 2_000, executionId = noiseExecution))

        val visible = db.executionJournalDao()
            .observeRecentActionsForAutomation(targetId.value, auditLimit = 1)
            .first()

        assertEquals(listOf("target_action"), visible.map { it.actionType })
    }

    @Test
    fun `maintenance interrupts stale runs and trims executions audit and child actions`() = runTest {
        val automationId = arm("retention", listOf(Action.SetWifi(false)))
        val ids = listOf(ExecutionId("exec-old"), ExecutionId("exec-middle"), ExecutionId("exec-new"))
        val times = listOf(1_200L, 1_300L, 1_900L)
        ids.zip(times).forEachIndexed { index, (executionId, at) ->
            store.claimFire(claim(automationId, "event-$index", executionId, at))
            if (index > 0)
                journal.finish(ExecutionCompletion(executionId, ExecutionStatus.SUCCEEDED, at, 1, 0, 0))
        }
        journal.recordAction(
            ActionJournalEntry(ids[0], 0, "set_wifi", ActionJournalOutcome.SUCCEEDED, 1_201),
        )

        val sink = RoomAuditSink(db.auditDao())
        times.forEachIndexed { index, at ->
            sink.record(AuditEvent(automationId, AuditKind.FIRED, at, executionId = ids[index]))
        }

        val result = RoomJournalMaintenance(
            db,
            JournalRetentionPolicy(
                maxAuditRows = 2,
                maxExecutions = 2,
                maxAgeMillis = 1_000,
                runningStaleAfterMillis = 100,
            ),
        ).run(nowMillis = 2_000)

        assertEquals(JournalMaintenanceResult(1, 1, 1), result)
        assertNull(db.executionJournalDao().execution(ids[0].value))
        assertEquals(2, db.executionJournalDao().executionCount())
        assertEquals(0, db.executionJournalDao().actionCount())
        assertEquals(2, db.auditDao().recent(10).size)
    }

    private suspend fun arm(id: String, actions: List<Action>): AutomationId {
        val created = assertIs<DraftWriteResult.Saved>(
            drafts.create(
                NewDraft(
                    id = DraftId("draft-$id"),
                    automationId = AutomationId(id),
                    draft = AutomationDraft(
                        name = id,
                        trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
                        actions = actions,
                    ),
                    atMillis = 1,
                ),
            ),
        ).snapshot
        return assertIs<DraftArmResult.Armed>(
            drafts.arm(created.id, created.revision, created.fingerprint),
        ).automation.id
    }

    private fun claim(
        automationId: AutomationId,
        eventId: String,
        executionId: ExecutionId,
        atMillis: Long,
        cooldownMs: Long = 0,
    ) = FireClaimRequest(
        automationId,
        TriggerEventId(eventId),
        executionId,
        atMillis,
        cooldownMs,
    )
}
