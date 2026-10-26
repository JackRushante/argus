package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.dao.RuleFireStats
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

/**
 * F10 (0.4.0): query aggregata «scatti, riuscite e fallite» per regola e finestra temporale, letta dal
 * journal delle esecuzioni (`fire_claims`). Dati finti su un database in memoria.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleFireStatsTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var drafts: RoomDraftRepository
    private lateinit var journal: RoomExecutionJournal
    private var sequence = 0

    private val now = 100 * DAY
    private val since7 get() = now - 7 * DAY
    private val since30 get() = now - 30 * DAY

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        drafts = RoomDraftRepository(db)
        journal = RoomExecutionJournal(db.executionJournalDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun stats(id: AutomationId): RuleFireStats =
        db.executionJournalDao().observeRuleFireStats(id.value, since7, since30).first()

    @Test
    fun `without any execution every counter is zero`() = runTest {
        val id = arm("quiet")

        assertEquals(RuleFireStats(0, 0, 0, 0, 0, 0), stats(id))
    }

    @Test
    fun `counts fires succeeded and failed in the 7 and 30 day windows`() = runTest {
        val id = arm("busy")
        fire(id, now - 1 * DAY, ExecutionStatus.SUCCEEDED)
        fire(id, now - 6 * DAY, ExecutionStatus.FAILED)
        fire(id, now - 10 * DAY, ExecutionStatus.SUCCEEDED)
        fire(id, now - 29 * DAY, ExecutionStatus.PARTIAL)
        // Fuori dalla finestra lunga: non conta da nessuna parte.
        fire(id, now - 40 * DAY, ExecutionStatus.SUCCEEDED)

        assertEquals(
            RuleFireStats(
                fires7 = 2, succeeded7 = 1, failed7 = 1,
                fires30 = 4, succeeded30 = 2, failed30 = 2,
            ),
            stats(id),
        )
    }

    @Test
    fun `the window edge is inclusive and one millisecond before it is out`() = runTest {
        val id = arm("edge")
        fire(id, since7, ExecutionStatus.SUCCEEDED)
        fire(id, since7 - 1, ExecutionStatus.SUCCEEDED)
        fire(id, since30 - 1, ExecutionStatus.SUCCEEDED)

        assertEquals(
            RuleFireStats(
                fires7 = 1, succeeded7 = 1, failed7 = 0,
                fires30 = 2, succeeded30 = 2, failed30 = 0,
            ),
            stats(id),
        )
    }

    @Test
    fun `suppressions are not fires`() = runTest {
        val id = arm("suppressed")
        fire(id, now - 1 * DAY, ExecutionStatus.SUCCEEDED)
        fire(id, now - 1 * DAY + 1, ExecutionStatus.SUPPRESSED_COOLDOWN)
        fire(id, now - 1 * DAY + 2, ExecutionStatus.SUPPRESSED_BUDGET)
        fire(id, now - 1 * DAY + 3, ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE)

        assertEquals(
            RuleFireStats(1, 1, 0, 1, 1, 0),
            stats(id),
        )
    }

    @Test
    fun `interrupted and cancelled count as failed while running and deferred only count as fires`() = runTest {
        val id = arm("mixed")
        fire(id, now - 1 * DAY, ExecutionStatus.INTERRUPTED)
        fire(id, now - 2 * DAY, ExecutionStatus.CANCELLED)
        fire(id, now - 3 * DAY, ExecutionStatus.DEFERRED)
        fire(id, now - 4 * DAY, ExecutionStatus.SUBMITTED)
        // Ancora in corso: claim senza completion.
        assertIs<FireClaimResult.Claimed>(
            store.claimFire(claim(id, now - 5 * DAY, ExecutionId("exec-running"))),
        )

        assertEquals(
            RuleFireStats(
                fires7 = 5, succeeded7 = 0, failed7 = 2,
                fires30 = 5, succeeded30 = 0, failed30 = 2,
            ),
            stats(id),
        )
    }

    @Test
    fun `other rules do not leak into the counts`() = runTest {
        val mine = arm("mine")
        val other = arm("other")
        fire(mine, now - 1 * DAY, ExecutionStatus.SUCCEEDED)
        fire(other, now - 1 * DAY, ExecutionStatus.FAILED)
        fire(other, now - 2 * DAY, ExecutionStatus.FAILED)

        assertEquals(RuleFireStats(1, 1, 0, 1, 1, 0), stats(mine))
        assertEquals(RuleFireStats(2, 0, 2, 2, 0, 2), stats(other))
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Un'esecuzione conclusa con [status] reclamata a [atMillis]. */
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

    private companion object {
        const val DAY = 24L * 60 * 60 * 1_000
    }
}
