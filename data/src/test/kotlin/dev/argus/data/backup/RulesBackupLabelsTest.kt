package dev.argus.data.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.RoomRuleLabelStore
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Etichette nel backup (0.7.2): viaggiano nell'export completo e tornano all'import; la versione del formato
 * resta 1 e la voce di ogni regola resta `{"enabled","draft"}`, così un file nuovo si importa anche nelle
 * versioni precedenti (senza etichette) e un file vecchio si importa qui come prima.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesBackupLabelsTest {
    private lateinit var db: ArgusDatabase
    private lateinit var repository: RoomDraftRepository
    private lateinit var labels: RoomRuleLabelStore
    private val documents = MemoryDocuments()
    private val file: Uri = Uri.parse("content://test/rules.json")
    private var nextId = 0

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repository = RoomDraftRepository(db)
        labels = RoomRuleLabelStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun service() = RulesBackupService(
        automations = RoomAutomationStore(db.automationDao()),
        drafts = repository,
        documents = documents,
        appVersion = { "0.7.2-test" },
        nowMillis = { 1_000L },
        newId = { "imp-${++nextId}" },
        labels = { labels.snapshot() },
    )

    // --- codec ---------------------------------------------------------------------------------

    @Test
    fun `labels round-trip beside the rules, never inside a rule entry`() {
        val text = RulesBackupCodec.encode(
            listOf(
                BackupRule(true, BackupFixtures.simple("a"), labels = listOf("Casa", "Lavoro")),
                BackupRule(false, BackupFixtures.simple("b")),
                BackupRule(true, BackupFixtures.simple("c"), labels = listOf("Auto")),
            ),
            "now",
            "0.7.2",
        )
        val root = Json.parseToJsonElement(text).jsonObject
        root.getValue("rules").jsonArray.forEach { entry ->
            assertEquals(setOf("enabled", "draft"), entry.jsonObject.keys, "voce leggibile dalle versioni precedenti")
        }
        assertEquals(1, root.getValue("version").toString().toInt())

        val decoded = assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(text)).entries
            .map { assertIs<DecodedEntry.Rule>(it).rule.labels }
        assertEquals(listOf(listOf("Casa", "Lavoro"), emptyList(), listOf("Auto")), decoded)
    }

    @Test
    fun `a file without labels is written exactly as before`() {
        val text = RulesBackupCodec.encode(listOf(BackupRule(true, BackupFixtures.simple())), "now", "0.7.2")
        assertFalse("labels" in Json.parseToJsonElement(text).jsonObject.keys)
    }

    @Test
    fun `a file written by an older version imports with no labels`() {
        val old = RulesBackupCodec.encode(listOf(BackupRule(true, BackupFixtures.simple())), "now", "0.4.0")
        val rule = assertIs<DecodedEntry.Rule>(
            assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(old)).entries.single(),
        ).rule
        assertEquals(emptyList(), rule.labels)
    }

    @Test
    fun `a broken labels section is ignored and never discards a rule`() {
        val rule = Json.parseToJsonElement(
            RulesBackupCodec.encode(listOf(BackupRule(true, BackupFixtures.simple())), "now", "0.7.2"),
        ).jsonObject.getValue("rules").jsonArray.single().toString()
        listOf(
            """"labels":"Casa"""",
            """"labels":[{"rule":"0","names":["Casa"]}]""",
            """"labels":[{"rule":7,"names":["Casa"]}]""",
            """"labels":[{"rule":0,"names":"Casa"}]""",
            """"labels":[42,{"rule":0}]""",
        ).forEach { section ->
            val text = """{"format":"argus-rules","version":1,"rules":[$rule],$section}"""
            val entry = assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(text), section).entries.single()
            assertEquals(emptyList(), assertIs<DecodedEntry.Rule>(entry, section).rule.labels, section)
        }
    }

    @Test
    fun `labels read from a file pass the same checks as typed ones`() {
        val rule = Json.parseToJsonElement(
            RulesBackupCodec.encode(listOf(BackupRule(true, BackupFixtures.simple())), "now", "0.7.2"),
        ).jsonObject.getValue("rules").jsonArray.single().toString()
        val names = listOf(" Casa ", "casa", "", "x".repeat(40), "Ca‮sa", "A", "B", "C", "D", "E", 3)
            .joinToString(",") { if (it is String) JsonPrimitive(it).toString() else it.toString() }
        val text = """{"format":"argus-rules","version":1,"rules":[$rule],"labels":[{"rule":0,"names":[$names]}]}"""

        val entry = assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(text)).entries.single()
        assertEquals(listOf("Casa", "A", "B", "C", "D"), assertIs<DecodedEntry.Rule>(entry).rule.labels)
    }

    // --- servizio ------------------------------------------------------------------------------

    @Test
    fun `the full export carries the labels and the import brings them back on the new drafts`() = runTest {
        armed("r1", "Arrivo a casa")
        armed("r2", "Buonanotte")
        labels.add(AutomationId("r1"), "Casa")
        labels.add(AutomationId("r1"), "Wi-Fi")

        assertIs<ExportOutcome.Done>(service().export(file, stripPersonalData = false))
        db.automationDao().delete("r1")
        db.automationDao().delete("r2")
        val outcome = assertIs<ImportOutcome.Done>(service().import(file))

        assertEquals(2, outcome.summary.imported)
        val imported = repository.observeAll().first().associateBy { it.draft.name }
        val byRule = labels.observe().first()
        assertEquals(listOf("Casa", "Wi-Fi"), byRule[imported.getValue("Arrivo a casa").automationId.value])
        assertEquals(null, byRule[imported.getValue("Buonanotte").automationId.value])
        assertTrue(imported.values.all { it.createdBy == CreatedBy.IMPORT })
    }

    @Test
    fun `the export without personal data leaves the labels out`() = runTest {
        armed("r1", "Chiama Moglie")
        labels.add(AutomationId("r1"), "Moglie")

        assertIs<ExportOutcome.Done>(service().export(file, stripPersonalData = true))

        assertFalse("labels" in Json.parseToJsonElement(documents.text(file)).jsonObject.keys)
    }

    private suspend fun armed(id: String, name: String) {
        val saved = assertIs<DraftWriteResult.Saved>(
            repository.create(
                NewDraft(DraftId("draft-$id"), AutomationId(id), BackupFixtures.simple(name), atMillis = 1_000),
            ),
        ).snapshot
        assertIs<DraftArmResult.Armed>(repository.arm(saved.id, saved.revision, saved.fingerprint))
    }

    private class MemoryDocuments : BackupDocumentIo {
        private val files = mutableMapOf<Uri, ByteArray>()

        fun text(uri: Uri): String = String(requireNotNull(files[uri]))

        override suspend fun read(source: Uri, maxBytes: Int): ByteArray? =
            requireNotNull(files[source]).takeIf { it.size <= maxBytes }

        override suspend fun write(destination: Uri, bytes: ByteArray) {
            files[destination] = bytes
        }
    }
}
