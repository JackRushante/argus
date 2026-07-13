package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.entities.AutomationEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.safety.ApprovalResult
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.ApprovalWhitelistProvider
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.NewDraft
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDraftRepositoryTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        repository = RoomDraftRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `new draft is revision one and exact reviewed fingerprint arms atomically`() = runTest {
        val saved = assertIs<DraftWriteResult.Saved>(repository.create(candidate("a1"))).snapshot
        assertEquals(1, saved.revision)
        assertTrue(saved.hasValidFingerprint())
        assertEquals(listOf(saved.id), repository.observeAll().first().map { it.id })

        val armed = assertIs<DraftArmResult.Armed>(
            repository.arm(saved.id, saved.revision, saved.fingerprint),
        ).automation
        assertEquals(AutomationStatus.ARMED, armed.status)
        assertTrue(armed.enabled)
        assertEquals(saved.fingerprint, armed.approvalFingerprint)
        assertEquals(listOf(armed.id), store.armed().map { it.id })
        assertNull(repository.get(saved.id))
    }

    @Test
    fun `edit after review increments revision and stale proof cannot arm`() = runTest {
        val first = assertIs<DraftWriteResult.Saved>(repository.create(candidate("a1"))).snapshot
        val edited = assertIs<DraftWriteResult.Saved>(
            repository.revise(
                id = first.id,
                expectedRevision = first.revision,
                draft = first.draft.copy(name = "modificata"),
                priority = 3,
                atMillis = 2_000,
            ),
        ).snapshot
        assertEquals(2, edited.revision)
        assertTrue(edited.fingerprint != first.fingerprint)

        assertEquals(
            DraftArmResult.Stale(currentRevision = 2),
            repository.arm(first.id, first.revision, first.fingerprint),
        )
        assertNull(store.get(first.automationId))
        assertNotNull(repository.get(first.id))
    }

    @Test
    fun `approved automation enters review and is replaced atomically without losing cooldown`() = runTest {
        val first = assertIs<DraftWriteResult.Saved>(repository.create(candidate("a1"))).snapshot
        val original = assertIs<DraftArmResult.Armed>(
            repository.arm(first.id, first.revision, first.fingerprint),
        ).automation
        store.recordFired(original.id, 777)

        val edit = candidate("a1").copy(
            id = DraftId("edit-a1"),
            draft = draft().copy(name = "DND sera modificato", actions = listOf(Action.SetWifi(true))),
            expectedAutomationFingerprint = assertNotNull(original.approvalFingerprint),
        )
        val pending = assertIs<DraftWriteResult.Saved>(repository.create(edit)).snapshot

        assertEquals(AutomationStatus.PENDING_APPROVAL, store.get(original.id)?.status)
        assertTrue(store.armed().isEmpty())

        val replacement = assertIs<DraftArmResult.Armed>(
            repository.arm(pending.id, pending.revision, pending.fingerprint),
        ).automation
        assertEquals("DND sera modificato", replacement.name)
        assertEquals(listOf(Action.SetWifi(true)), replacement.actions)
        assertEquals(777, store.lastFiredAt(replacement.id))
        assertNull(repository.get(pending.id))
    }

    @Test
    fun `editing an approved automation requires its exact current fingerprint`() = runTest {
        val first = assertIs<DraftWriteResult.Saved>(repository.create(candidate("a1"))).snapshot
        val original = assertIs<DraftArmResult.Armed>(
            repository.arm(first.id, first.revision, first.fingerprint),
        ).automation

        val rejected = assertIs<DraftWriteResult.Rejected>(
            repository.create(
                candidate("a1").copy(
                    id = DraftId("edit-a1"),
                    expectedAutomationFingerprint = ApprovalFingerprint("0".repeat(64)),
                ),
            ),
        )

        assertEquals("automation_stale", rejected.code)
        assertEquals(listOf(original.id), store.armed().map { it.id })
    }

    @Test
    fun `concurrent edits with same expected revision allow only one writer`() = runTest {
        val base = assertIs<DraftWriteResult.Saved>(repository.create(candidate("a1"))).snapshot

        val results = coroutineScope {
            listOf("prima", "seconda").map { name ->
                async {
                    repository.revise(
                        base.id,
                        base.revision,
                        base.draft.copy(name = name),
                        base.priority,
                        2_000,
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it is DraftWriteResult.Saved })
        assertEquals(1, results.count { it is DraftWriteResult.Conflict })
    }

    @Test
    fun `tampered draft json is persistently quarantined and never armed`() = runTest {
        val snapshot = assertIs<DraftWriteResult.Saved>(repository.create(candidate("a1"))).snapshot
        db.openHelper.writableDatabase.execSQL(
            "UPDATE pending_drafts SET draftJson = ? WHERE id = ?",
            arrayOf("{ broken json ]", snapshot.id.value),
        )

        assertEquals(
            DraftArmResult.IntegrityFailure,
            repository.arm(snapshot.id, snapshot.revision, snapshot.fingerprint),
        )
        assertEquals("draft_json_invalid", db.draftDao().getById(snapshot.id.value)?.quarantineCode)
        assertTrue(repository.get(snapshot.id)?.integrityError != null)
        assertNull(store.get(snapshot.automationId))
    }

    @Test
    fun `approval service validates current policy before arm`() = runTest {
        val invalid = candidate("invalid").copy(draft = draft().copy(actions = emptyList()))
        val snapshot = assertIs<DraftWriteResult.Saved>(repository.create(invalid)).snapshot
        val service = ApprovalService(
            repository,
            DraftValidator(knownTools = setOf("notify.show")),
            ApprovalWhitelistProvider { emptySet() },
        )

        val result = assertIs<ApprovalResult.ValidationFailed>(
            service.arm(snapshot.id, snapshot.revision, snapshot.fingerprint),
        )
        assertTrue(result.issues.any { it.code == "no_actions" })
        assertNotNull(repository.get(snapshot.id))
        assertNull(store.get(snapshot.automationId))
    }

    @Test
    fun `direct ARMED row without approved fingerprint is quarantined`() = runTest {
        val unsigned = Automation(
            id = AutomationId("unsigned"),
            name = "bypass",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(Action.SetWifi(true)),
            enabled = true,
        )
        db.automationDao().upsert(
            AutomationEntity(
                id = unsigned.id.value,
                name = unsigned.name,
                status = unsigned.status,
                enabled = unsigned.enabled,
                priority = unsigned.priority,
                cooldownMs = unsigned.cooldownMs,
                schemaVersion = unsigned.schemaVersion,
                json = ArgusJson.encodeToString(Automation.serializer(), unsigned),
            ),
        )

        assertEquals(AutomationStatus.NEEDS_REVIEW, store.get(unsigned.id)?.status)
        assertTrue(store.armed().isEmpty())
    }

    private fun candidate(id: String) = NewDraft(
        id = DraftId("draft-$id"),
        automationId = AutomationId(id),
        draft = draft(),
        atMillis = 1_000,
    )

    private fun draft() = AutomationDraft(
        name = "DND sera",
        trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
        actions = listOf(Action.SetWifi(false)),
        rationale = "Richiesto in chat",
    )
}
