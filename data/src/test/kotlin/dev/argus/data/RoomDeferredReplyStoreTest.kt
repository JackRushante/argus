package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.entities.DeferredReplyEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireClaimRequest
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDeferredReplyStoreTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomDeferredReplyStore

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomDeferredReplyStore(db.deferredReplyDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `save is first write wins and lookup honours ttl and consumption`() = runTest {
        val executionId = claimedExecution("exec-deferred")
        val entity = entity(executionId, payload = "ciphertext-original")

        assertTrue(store.save(entity))
        assertFalse(
            store.save(entity.copy(payload = "ciphertext-overwrite")),
            "una redelivery non può sovrascrivere il payload originale",
        )
        assertEquals(
            "ciphertext-original",
            assertNotNull(store.firstActionable(executionId.value, nowMillis = 5_000)).payload,
        )

        assertNull(
            store.firstActionable(executionId.value, nowMillis = 10_000),
            "una reply scaduta non è più azionabile",
        )

        assertTrue(store.markConsumed(executionId.value, actionIndex = 0, atMillis = 6_000))
        assertFalse(
            store.markConsumed(executionId.value, actionIndex = 0, atMillis = 6_100),
            "il consumo è one-shot",
        )
        assertNull(store.firstActionable(executionId.value, nowMillis = 5_000))
    }

    @Test
    fun `purge removes expired and consumed rows and clear removes everything`() = runTest {
        val expired = claimedExecution("exec-expired")
        val consumed = claimedExecution("exec-consumed")
        val alive = claimedExecution("exec-alive")
        store.save(entity(expired, expiresAtMillis = 1_000))
        store.save(entity(consumed))
        store.save(entity(alive))
        store.markConsumed(consumed.value, actionIndex = 0, atMillis = 2_000)

        assertEquals(2, store.purgeExpired(nowMillis = 3_000))
        assertNotNull(store.firstActionable(alive.value, nowMillis = 3_000))
        assertEquals(1, db.deferredReplyDao().count())

        assertEquals(1, store.clear())
        assertEquals(0, db.deferredReplyDao().count())
    }

    @Test
    fun `journal retention cascades to deferred payloads`() = runTest {
        val executionId = claimedExecution("exec-cascade", atMillis = 1_200)
        db.executionJournalDao().let { journal ->
            RoomExecutionJournal(journal)
        }
        RoomExecutionJournal(db.executionJournalDao()).finish(
            ExecutionCompletion(executionId, ExecutionStatus.SUCCEEDED, 1_200, 1, 0, 0),
        )
        store.save(entity(executionId, expiresAtMillis = 999_999))

        val result = RoomJournalMaintenance(
            db,
            JournalRetentionPolicy(maxAgeMillis = 1_000, runningStaleAfterMillis = 100),
        ).run(nowMillis = 5_000)

        assertTrue(result.deletedExecutions >= 1)
        assertEquals(0, db.deferredReplyDao().count(), "la FK CASCADE elimina il ciphertext orfano")
    }

    private suspend fun claimedExecution(id: String, atMillis: Long = 1_000): ExecutionId {
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
        assertIs<DraftArmResult.Armed>(drafts.arm(created.id, created.revision, created.fingerprint))
        val executionId = ExecutionId("execution-$id")
        RoomAutomationStore(db.automationDao()).claimFire(
            FireClaimRequest(
                AutomationId(id),
                TriggerEventId("event-$id"),
                executionId,
                atMillis,
                0,
            ),
        )
        return executionId
    }

    private fun entity(
        executionId: ExecutionId,
        payload: String = "ciphertext",
        expiresAtMillis: Long = 9_000,
    ) = DeferredReplyEntity(
        executionId = executionId.value,
        actionIndex = 0,
        packageName = "com.whatsapp",
        createdAtMillis = 1_000,
        expiresAtMillis = expiresAtMillis,
        consumedAtMillis = null,
        payload = payload,
    )
}
