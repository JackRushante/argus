package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Trigger
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

/** 0.7.2: esiti delle ultime esecuzioni concluse di una regola, per l'avviso «fallita 3 volte di fila». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentOutcomesTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var drafts: RoomDraftRepository
    private lateinit var journal: RoomExecutionJournal
    private var sequence = 0

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        drafts = RoomDraftRepository(db)
        journal = RoomExecutionJournal(db.executionJournalDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun outcomes(id: AutomationId, limit: Int = 3): List<ExecutionStatus> =
        db.executionJournalDao().recentOutcomes(id.value, limit)

    @Test
    fun `most recent first and limited`() = runTest {
        val id = arm("rule")
        fire(id, 10, ExecutionStatus.SUCCEEDED)
        fire(id, 20, ExecutionStatus.FAILED)
        fire(id, 30, ExecutionStatus.PARTIAL)
        fire(id, 40, ExecutionStatus.INTERRUPTED)

        assertEquals(
            listOf(ExecutionStatus.INTERRUPTED, ExecutionStatus.PARTIAL, ExecutionStatus.FAILED),
            outcomes(id),
        )
    }

    @Test
    fun `suppressed cancelled and open executions are not outcomes`() = runTest {
        val id = arm("skips")
        fire(id, 10, ExecutionStatus.FAILED)
        fire(id, 20, ExecutionStatus.SUPPRESSED_COOLDOWN)
        fire(id, 30, ExecutionStatus.SUPPRESSED_BUDGET)
        fire(id, 40, ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE)
        fire(id, 50, ExecutionStatus.CANCELLED)
        fire(id, 60, ExecutionStatus.DEFERRED)
        fire(id, 70, ExecutionStatus.SUBMITTED)
        assertIs<FireClaimResult.Claimed>(store.claimFire(claim(id, 80, ExecutionId("exec-running"))))

        assertEquals(listOf(ExecutionStatus.FAILED), outcomes(id))
    }

    @Test
    fun `other rules do not leak in`() = runTest {
        val mine = arm("mine")
        val other = arm("other")
        fire(mine, 10, ExecutionStatus.SUCCEEDED)
        fire(other, 20, ExecutionStatus.FAILED)

        assertEquals(listOf(ExecutionStatus.SUCCEEDED), outcomes(mine))
        assertEquals(listOf(ExecutionStatus.FAILED), outcomes(other))
    }

    // ---- helpers (come RuleFireStatsTest) ------------------------------------------------------

    private suspend fun fire(id: AutomationId, atMillis: Long, status: ExecutionStatus) {
        val executionId = ExecutionId("exec-${sequence++}")
        assertIs<FireClaimResult.Claimed>(store.claimFire(claim(id, atMillis, executionId)))
        journal.finish(ExecutionCompletion(executionId, status, atMillis, 0, 0, 0))
    }

    private fun claim(id: AutomationId, atMillis: Long, executionId: ExecutionId) = FireClaimRequest(
        id,
        TriggerEventId("event-${sequence++}"),
        executionId,
        atMillis,
        0,
    )

    private suspend fun arm(name: String): AutomationId {
        val created = assertIs<DraftWriteResult.Saved>(
            drafts.create(
                NewDraft(
                    id = DraftId("draft-$name"),
                    automationId = AutomationId(name),
                    draft = AutomationDraft(
                        name = name,
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
}
