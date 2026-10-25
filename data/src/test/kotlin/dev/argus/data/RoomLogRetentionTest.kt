package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.entities.ObservedConversationEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** F11: conservazione configurabile e «Svuota il log» che non tocca dedup, cooldown e recuperi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomLogRetentionTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var journal: RoomExecutionJournal
    private lateinit var sink: RoomAuditSink

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        journal = RoomExecutionJournal(db.executionJournalDao())
        sink = RoomAuditSink(db.auditDao())
    }

    @After
    fun tearDown() = db.close()

    private val day = 24L * 60 * 60 * 1_000
    private val now = 200L * day

    // --- conservazione --------------------------------------------------------------------

    @Test
    fun `the default retention is the thirty days maintenance already used`() {
        assertEquals(30, JournalRetentionPolicy.DEFAULT_RETENTION_DAYS)
        assertEquals(30L * day, JournalRetentionPolicy().maxAgeMillis)
    }

    @Test
    fun `maintenance without a provider keeps the policy age`() = runTest {
        val id = arm("no-provider")
        finished(id, "old", now - 20 * day, ExecutionStatus.SUCCEEDED)
        finished(id, "older", now - 40 * day, ExecutionStatus.SUCCEEDED)

        RoomJournalMaintenance(db).run(now)

        assertNotNull(db.executionJournalDao().execution("exec-old"))
        assertNull(db.executionJournalDao().execution("exec-older"))
        assertEquals(listOf("exec-old"), db.auditDao().recent(10).map { it.executionId })
    }

    @Test
    fun `the retention provider decides how old audit and executions may get`() = runTest {
        val id = arm("provider")
        finished(id, "d5", now - 5 * day, ExecutionStatus.SUCCEEDED)
        finished(id, "d20", now - 20 * day, ExecutionStatus.SUCCEEDED)
        finished(id, "d60", now - 60 * day, ExecutionStatus.SUCCEEDED)
        finished(id, "d100", now - 100 * day, ExecutionStatus.SUCCEEDED)

        var days = 7
        val maintenance = RoomJournalMaintenance(db, logRetentionDays = { days })

        maintenance.run(now)
        assertEquals(listOf("exec-d5"), db.auditDao().recent(10).map { it.executionId })
        assertNull(db.executionJournalDao().execution("exec-d20"))

        // Una conservazione piu' lunga non resuscita nulla ma smette di cancellare.
        days = 90
        finished(id, "n60", now - 60 * day, ExecutionStatus.SUCCEEDED)
        finished(id, "n100", now - 100 * day, ExecutionStatus.SUCCEEDED)
        maintenance.run(now)
        assertNotNull(db.executionJournalDao().execution("exec-n60"))
        assertNull(db.executionJournalDao().execution("exec-n100"))
    }

    @Test
    fun `a provider returning garbage falls back to the default`() = runTest {
        val id = arm("garbage")
        finished(id, "d20", now - 20 * day, ExecutionStatus.SUCCEEDED)
        finished(id, "d40", now - 40 * day, ExecutionStatus.SUCCEEDED)

        RoomJournalMaintenance(db, logRetentionDays = { -3 }).run(now)

        assertNotNull(db.executionJournalDao().execution("exec-d20"))
        assertNull(db.executionJournalDao().execution("exec-d40"))
    }

    @Test
    fun `retention days leave the observed conversation picker on its own age`() = runTest {
        val conversations = db.observedConversationDao()
        conversations.insertIfAbsent(observed("c-20", now - 20 * day))
        conversations.insertIfAbsent(observed("c-40", now - 40 * day))

        RoomJournalMaintenance(db, logRetentionDays = { 7 }).run(now)

        assertEquals(listOf("c-20"), conversations.recent(10).map { it.conversationId })
    }

    // --- svuota il log --------------------------------------------------------------------

    @Test
    fun `clearing the log removes concluded audit and actions and keeps open and recoverable work`() = runTest {
        val id = arm("clear")
        val statuses = mapOf(
            "ok" to ExecutionStatus.SUCCEEDED,
            "partial" to ExecutionStatus.PARTIAL,
            "failed" to ExecutionStatus.FAILED,
            "cancelled" to ExecutionStatus.CANCELLED,
            "interrupted" to ExecutionStatus.INTERRUPTED,
            "cooldown" to ExecutionStatus.SUPPRESSED_COOLDOWN,
            "submitted" to ExecutionStatus.SUBMITTED,
            "deferred" to ExecutionStatus.DEFERRED,
        )
        statuses.entries.forEachIndexed { index, (name, status) ->
            finished(id, name, 1_000L + index, status)
        }
        // Un'esecuzione ancora in corso: claim RUNNING senza completion.
        store.claimFire(claim(id, "running", ExecutionId("exec-running"), 5_000))
        journal.recordAction(
            ActionJournalEntry(ExecutionId("exec-running"), 0, "set_wifi", ActionJournalOutcome.SUCCEEDED, 5_001),
        )
        sink.record(AuditEvent(id, AuditKind.FIRED, 5_000, executionId = ExecutionId("exec-running")))
        // Eventi senza esecuzione (lifecycle, condizioni non soddisfatte).
        sink.record(AuditEvent(id, AuditKind.RULE_ARMED, 900, detail = "approval"))
        sink.record(AuditEvent(id, AuditKind.CONDITIONS_NOT_MET, 901))

        val result = RoomJournalMaintenance(db).clearLog()

        // 6 esecuzioni concluse + 2 eventi senza esecuzione; 6 righe azione delle concluse.
        assertEquals(8, result.deletedAuditRows)
        assertEquals(6, result.deletedActions)
        val remainingAudit = db.auditDao().recent(50).mapNotNull { it.executionId }.toSet()
        assertEquals(setOf("exec-running", "exec-submitted", "exec-deferred"), remainingAudit)
        assertEquals(3, db.auditDao().recent(50).size)
        val remainingActions = db.executionJournalDao().observeRecentActions(50).first()
            .map { it.executionId }.toSet()
        assertEquals(setOf("exec-running", "exec-submitted", "exec-deferred"), remainingActions)
    }

    @Test
    fun `clearing the log keeps dedup claims and the cooldown timestamp`() = runTest {
        val id = arm("dedup")
        finished(id, "once", 1_000, ExecutionStatus.SUCCEEDED, cooldownMs = 60_000)
        val claimsBefore = db.executionJournalDao().executionCount()
        assertEquals(1_000, store.lastFiredAt(id))

        RoomJournalMaintenance(db).clearLog()

        // Nessuna riga di claim persa: lo stesso evento resta un duplicato dopo lo svuotamento.
        assertEquals(claimsBefore, db.executionJournalDao().executionCount())
        assertEquals(
            FireClaimResult.Duplicate(ExecutionId("exec-once")),
            store.claimFire(claim(id, "once", ExecutionId("exec-redelivered"), 2_000)),
        )
        // Il cooldown resta quello di prima.
        assertEquals(1_000, store.lastFiredAt(id))
        assertIs<FireClaimResult.Cooldown>(
            store.claimFire(claim(id, "later", ExecutionId("exec-later"), 2_000, cooldownMs = 60_000)),
        )
    }

    @Test
    fun `clearing an empty log is a no-op`() = runTest {
        val result = RoomJournalMaintenance(db).clearLog()
        assertEquals(0, result.deletedAuditRows)
        assertEquals(0, result.deletedActions)
    }

    // --- helper ---------------------------------------------------------------------------

    private suspend fun finished(
        id: AutomationId,
        name: String,
        at: Long,
        status: ExecutionStatus,
        cooldownMs: Long = 0,
    ) {
        val executionId = ExecutionId("exec-$name")
        store.claimFire(claim(id, name, executionId, at, cooldownMs))
        journal.recordAction(
            ActionJournalEntry(executionId, 0, "set_wifi", ActionJournalOutcome.SUCCEEDED, at),
        )
        journal.finish(ExecutionCompletion(executionId, status, at, 1, 0, 0))
        sink.record(AuditEvent(id, AuditKind.FIRED, at, executionId = executionId))
    }

    private fun observed(id: String, seenAt: Long) = ObservedConversationEntity(
        conversationId = id,
        packageName = "com.example.chat",
        displayName = "chat $id",
        isGroup = false,
        lastSeenAtMillis = seenAt,
    )

    private suspend fun arm(id: String): AutomationId {
        val drafts = RoomDraftRepository(db)
        val created = assertIs<DraftWriteResult.Saved>(
            drafts.create(
                NewDraft(
                    id = DraftId("draft-$id"),
                    automationId = AutomationId(id),
                    draft = AutomationDraft(
                        name = id,
                        trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
                        actions = listOf(Action.SetWifi(false)),
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
    ) = FireClaimRequest(automationId, TriggerEventId("event-$eventId"), executionId, atMillis, cooldownMs)
}
