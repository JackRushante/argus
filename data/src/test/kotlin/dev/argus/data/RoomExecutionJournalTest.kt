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
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
