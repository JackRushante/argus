package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.backup.BackupFixtures
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.RuleLabels
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
import kotlin.test.assertTrue

/**
 * Etichette delle regole (0.7.2): organizzazione, mai comportamento. Aggiungerle o toglierle non tocca la regola
 * (stessa impronta, nessuna bozza, nessuna approvazione); i limiti valgono su regole salvate e bozze importate;
 * eliminare la regola elimina le sue etichette.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomRuleLabelStoreTest {
    private lateinit var db: ArgusDatabase
    private lateinit var automations: RoomAutomationStore
    private lateinit var drafts: RoomDraftRepository
    private lateinit var labels: RoomRuleLabelStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        automations = RoomAutomationStore(db.automationDao())
        drafts = RoomDraftRepository(db)
        labels = RoomRuleLabelStore(db) { clock++ }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `adding and removing a label never touches the rule or its approval`() = runTest {
        val rule = armed("r1")
        val before = db.automationDao().getById("r1")

        assertEquals(LabelAddResult.Added("Casa", isNewLabel = true), labels.add(rule.id, "  Casa "))
        assertEquals(mapOf("r1" to listOf("Casa")), labels.observe().first())
        assertEquals(before, db.automationDao().getById("r1"), "riga della regola identica: json, stato, impronta")
        assertEquals(rule.approvalFingerprint, automations.get(rule.id)?.approvalFingerprint)
        assertTrue(drafts.observeAll().first().isEmpty(), "nessuna bozza da approvare")

        assertTrue(labels.remove(rule.id, "CASA"))
        assertEquals(emptyMap(), labels.observe().first())
        assertEquals(before, db.automationDao().getById("r1"))
    }

    @Test
    fun `the same label is compared without case and keeps the spelling already in use`() = runTest {
        armed("r1")
        armed("r2")
        labels.add(AutomationId("r1"), "Casa")

        assertEquals(LabelAddResult.AlreadyPresent, labels.add(AutomationId("r1"), "casa"))
        assertEquals(LabelAddResult.Added("Casa", isNewLabel = false), labels.add(AutomationId("r2"), "CASA"))
        assertEquals(listOf("Casa"), labels.snapshot()["r2"])
    }

    @Test
    fun `an invalid label or a missing rule is refused`() = runTest {
        armed("r1")
        assertEquals(LabelAddResult.Invalid, labels.add(AutomationId("r1"), "   "))
        assertEquals(LabelAddResult.Invalid, labels.add(AutomationId("r1"), "x".repeat(25)))
        assertEquals(LabelAddResult.MissingRule, labels.add(AutomationId("ghost"), "Casa"))
        assertTrue(labels.snapshot().isEmpty())
    }

    @Test
    fun `a rule holds at most five labels`() = runTest {
        armed("r1")
        (1..5).forEach { assertIs<LabelAddResult.Added>(labels.add(AutomationId("r1"), "L$it")) }

        assertEquals(LabelAddResult.RuleFull, labels.add(AutomationId("r1"), "L6"))
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5"), labels.snapshot()["r1"])
    }

    @Test
    fun `at most twenty distinct labels, but an existing one can always be reused`() = runTest {
        (1..4).forEach { armed("r$it") }
        (0 until RuleLabels.MAX_DISTINCT).forEach { index ->
            assertIs<LabelAddResult.Added>(labels.add(AutomationId("r${index / 5 + 1}"), "L$index"))
        }
        armed("r5")

        assertEquals(LabelAddResult.TooManyLabels, labels.add(AutomationId("r5"), "Nuova"))
        assertEquals(LabelAddResult.Added("L3", isNewLabel = false), labels.add(AutomationId("r5"), "l3"))
    }

    @Test
    fun `deleting a rule deletes its labels, and a label with no rule left disappears`() = runTest {
        armed("r1")
        armed("r2")
        labels.add(AutomationId("r1"), "Casa")
        labels.add(AutomationId("r1"), "Lavoro")
        labels.add(AutomationId("r2"), "Casa")

        automations.delete(AutomationId("r1"))

        assertEquals(mapOf("r2" to listOf("Casa")), labels.observe().first())
        assertEquals(emptySet(), labels.keysOf(AutomationId("r1")))
        assertEquals(LabelAddResult.Added("Lavoro", isNewLabel = true), labels.add(AutomationId("r2"), "Lavoro"))
    }

    @Test
    fun `labels of an imported draft are listed, survive the approval and stay out of its fingerprint`() = runTest {
        val plain = assertIs<DraftWriteResult.Saved>(drafts.create(newDraft("a2", "d2"))).snapshot
        drafts.delete(plain.id, plain.revision)
        val results = drafts.createAll(
            listOf(newDraft("a2", "d2")),
            mapOf(DraftId("d2") to listOf("Casa", "casa", "Auto")),
        )
        val labelled = assertIs<DraftWriteResult.Saved>(results.single()).snapshot

        assertEquals(plain.fingerprint, labelled.fingerprint, "stessa bozza, stessa impronta, etichette o no")
        assertEquals(plain.draft, labelled.draft)
        assertEquals(mapOf("a2" to listOf("Casa", "Auto")), labels.observe().first())

        val armed = assertIs<DraftArmResult.Armed>(drafts.arm(labelled.id, labelled.revision, labelled.fingerprint))
        assertEquals(listOf("Casa", "Auto"), labels.snapshot()["a2"])
        assertEquals(setOf("casa", "auto"), labels.keysOf(armed.automation.id))
        assertTrue(db.draftDao().allLabels().isEmpty())
    }

    @Test
    fun `imported labels respect the twenty distinct labels and reuse existing spellings`() = runTest {
        armed("r1")
        (0 until RuleLabels.MAX_DISTINCT - 1).forEach { index ->
            if (index % 5 == 0 && index > 0) armed("r${index / 5 + 1}")
            labels.add(AutomationId("r${index / 5 + 1}"), "L$index")
        }

        drafts.createAll(
            listOf(newDraft("a1", "d1"), newDraft("a2", "d2")),
            mapOf(DraftId("d1") to listOf("Prima", "l0"), DraftId("d2") to listOf("Seconda", "Prima")),
        )

        val listed = labels.snapshot()
        assertEquals(listOf("Prima", "L0"), listed["a1"])
        assertEquals(listOf("Prima"), listed["a2"], "la ventunesima distinta si perde, la regola no")
    }

    @Test
    fun `an edit draft never carries labels`() = runTest {
        val rule = armed("r1")
        labels.add(rule.id, "Casa")

        drafts.createAll(
            listOf(
                newDraft("r1", "edit").copy(
                    draft = BackupFixtures.simple("v2"),
                    expectedAutomationFingerprint = rule.approvalFingerprint,
                ),
            ),
            mapOf(DraftId("edit") to listOf("Altro")),
        )

        assertEquals(mapOf("r1" to listOf("Casa")), labels.snapshot())
    }

    private fun newDraft(automationId: String, draftId: String) = NewDraft(
        id = DraftId(draftId),
        automationId = AutomationId(automationId),
        draft = BackupFixtures.simple(),
        createdBy = CreatedBy.IMPORT,
        atMillis = 1_000,
    )

    private suspend fun armed(id: String): Automation {
        val saved = assertIs<DraftWriteResult.Saved>(drafts.create(newDraft(id, "draft-$id"))).snapshot
        return assertIs<DraftArmResult.Armed>(drafts.arm(saved.id, saved.revision, saved.fingerprint)).automation
    }
}
