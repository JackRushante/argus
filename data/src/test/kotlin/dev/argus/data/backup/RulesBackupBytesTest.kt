package dev.argus.data.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomDraftRepository
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.toDraft
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.7.2: import di byte in memoria (testo incollato o ricevuto, modelli), anteprima e condivisione di una regola.
 * Tutto passa dalla stessa lettura del file di backup: limite prima del contenuto, UTF-8 e decodifica strette,
 * bozze `IMPORT` da approvare.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesBackupBytesTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private val documents = MemoryDocuments()
    private var nextId = 0

    private fun service() = RulesBackupService(
        automations = store,
        drafts = repository,
        documents = documents,
        appVersion = { "0.7.2-test" },
        nowMillis = { 1_000L },
        newId = { "id-${++nextId}" },
    )

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        repository = RoomDraftRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `imported bytes become drafts to approve and the outcome names them`() = runTest {
        val bytes = file(BackupFixtures.simple("Prima"), BackupFixtures.simple("Seconda"))

        val outcome = assertIs<ImportOutcome.Done>(service().importBytes(bytes))

        assertEquals(2, outcome.summary.imported)
        val drafts = repository.observeAll().first()
        assertEquals(drafts.map { it.id.value }.toSet(), outcome.draftIds.toSet())
        assertTrue(drafts.all { it.createdBy == CreatedBy.IMPORT })
        assertEquals(emptyList(), store.armed())
    }

    @Test
    fun `the preview names the rules and counts the discarded ones without writing anything`() = runTest {
        val bad = """{"enabled":true,"draft":{"name":"x","trigger":{"type":"nope"},"actions":[]}}"""
        val good = """{"enabled":true,"draft":""" +
            ArgusJson.encodeToString(AutomationDraft.serializer(), BackupFixtures.simple("Buona")) + "}"
        val bytes = """{"format":"argus-rules","version":1,"exportedAt":"now","appVersion":"0.7.2","rules":[$good,$bad]}"""
            .toByteArray()

        val preview = assertIs<ImportPreview.Ready>(service().preview(bytes))

        assertEquals(listOf("Buona"), preview.ruleNames)
        assertEquals(mapOf(RuleDiscardReason.INVALID_RULE to 1), preview.discarded)
        assertEquals(bytes.size, preview.sizeBytes)
        assertEquals(emptyList(), repository.observeAll().first())
    }

    @Test
    fun `over the size cap is refused before the content is read`() = runTest {
        // Non è nemmeno JSON: se il contenuto venisse letto prima, l'esito sarebbe NOT_JSON.
        val tooBig = ByteArray(RulesBackupFormat.MAX_FILE_BYTES + 1) { '['.code.toByte() }

        assertEquals(ImportPreview.Rejected(BackupFailure.FILE_TOO_LARGE), service().preview(tooBig))
        assertEquals(ImportOutcome.Failed(BackupFailure.FILE_TOO_LARGE), service().importBytes(tooBig))
        assertNull(RulesBackupFormat.utf8WithinCap("x".repeat(RulesBackupFormat.MAX_FILE_BYTES + 1)))
        // Due byte per carattere: entro il limite in caratteri, fuori in byte.
        assertNull(RulesBackupFormat.utf8WithinCap("è".repeat(RulesBackupFormat.MAX_FILE_BYTES / 2 + 1)))
        assertEquals(3, RulesBackupFormat.utf8WithinCap("abc")?.size)
    }

    @Test
    fun `the preview refuses what the import refuses`() = runTest {
        assertEquals(ImportPreview.Rejected(BackupFailure.NOT_JSON), service().preview("ciao".toByteArray()))
        assertEquals(ImportPreview.Rejected(BackupFailure.WRONG_FORMAT), service().preview("{}".toByteArray()))
        assertEquals(
            ImportPreview.Rejected(BackupFailure.NOT_JSON),
            service().preview(byteArrayOf(0xC3.toByte(), 0x28)),
        )
    }

    @Test
    fun `names in the preview are cleaned for display only`() = runTest {
        val spoof = "Sveglia‮gnp.exe\n\tnuova   riga"
        val long = "L".repeat(300)
        val preview = assertIs<ImportPreview.Ready>(
            service().preview(file(BackupFixtures.simple(spoof), BackupFixtures.simple(long))),
        )

        // Via l'inversione di direzione; a capo e tabulazione diventano un solo spazio.
        assertEquals("Svegliagnp.exe nuova riga", preview.ruleNames[0])
        assertEquals(RulesBackupService.PREVIEW_NAME_CHARS, preview.ruleNames[1].length)
        assertTrue(preview.ruleNames[1].endsWith("…"))
    }

    @Test
    fun `reading a document applies the cap and reports failures`() = runTest {
        val uri = Uri.parse("content://test/a.json")
        documents.files[uri] = "abc".toByteArray()
        assertEquals("abc", String(assertIs<DocumentRead.Bytes>(service().read(uri)).bytes))

        documents.files[uri] = ByteArray(RulesBackupFormat.MAX_FILE_BYTES + 1)
        assertEquals(DocumentRead.Failed(BackupFailure.FILE_TOO_LARGE), service().read(uri))

        assertEquals(DocumentRead.Failed(BackupFailure.READ_FAILED), service().read(Uri.parse("content://test/none")))
        documents.readFailure = SecurityException("no grant")
        assertEquals(DocumentRead.Failed(BackupFailure.READ_FAILED), service().read(uri))
    }

    // --- condivisione di una regola --------------------------------------------------------------

    @Test
    fun `a shared rule is the backup file with one rule, and imports back as the same draft`() = runTest {
        val armed = armed("r", BackupFixtures.rich())
        val texts = assertNotNull(RuleSharing(documents, { "0.7.2" }, { 0L }).texts(armed))

        val full = assertIs<ImportOutcome.Done>(service().importBytes(texts.full.text.toByteArray()))
        val stripped = assertIs<ImportOutcome.Done>(service().importBytes(texts.stripped.compactText.toByteArray()))

        val drafts = repository.observeAll().first().associateBy { it.id.value }
        assertEquals(armed.toDraft(), drafts.getValue(full.draftIds.single()).draft)
        assertEquals(PersonalDataStripper.strip(armed.toDraft()), drafts.getValue(stripped.draftIds.single()).draft)
        // Stesso documento, con e senza spazi.
        assertEquals(Json.parseToJsonElement(texts.full.text), Json.parseToJsonElement(texts.full.compactText))
        assertTrue('\n' !in texts.full.compactText)
        // Le etichette sono l'organizzazione di chi condivide: una regola condivisa non le porta, in nessuna versione.
        listOf(texts.full.text, texts.stripped.text).forEach { assertTrue("\"labels\"" !in it) }
    }

    @Test
    fun `an encrypted backup pasted or received is refused with its own reason`() = runTest {
        val encrypted = "ARGUSBK1".toByteArray(Charsets.US_ASCII) + ByteArray(64)
        assertTrue(EncryptedBackupFormat.hasMagic(encrypted))

        assertEquals(ImportPreview.Rejected(BackupFailure.PASSPHRASE_REQUIRED), service().preview(encrypted))
        assertEquals(ImportOutcome.Failed(BackupFailure.PASSPHRASE_REQUIRED), service().importBytes(encrypted))
    }

    @Test
    fun `a draft or an unreadable rule has nothing to share`() = runTest {
        val armed = armed("r", BackupFixtures.simple())
        val pending = armed.copy(status = dev.argus.engine.model.AutomationStatus.PENDING_APPROVAL)
        val placeholder = armed.copy(status = dev.argus.engine.model.AutomationStatus.NEEDS_REVIEW, actions = emptyList())

        assertNull(RuleShareCodec.encode(pending, stripPersonalData = true, "now", "0.7.2"))
        assertNull(RuleShareCodec.encode(placeholder, stripPersonalData = false, "now", "0.7.2"))
    }

    @Test
    fun `saving a shared rule writes the file and reports a failure`() = runTest {
        val sharing = RuleSharing(documents, { "0.7.2" })
        val uri = Uri.parse("content://test/rule.json")

        assertTrue(sharing.save(uri, "testo"))
        assertEquals("testo", String(assertNotNull(documents.files[uri])))

        documents.writeFailure = IOException("disk full")
        assertEquals(false, sharing.save(uri, "altro"))
    }

    @Test
    fun `the file name of a shared rule keeps letters and digits of its name`() {
        assertEquals("argus-rule-notte-tranquilla.json", RulesBackupFormat.shareFileName("Notte tranquilla"))
        assertEquals("argus-rule-caffe-alle-8.json", RulesBackupFormat.shareFileName("Caffè alle 8!"))
        assertEquals("argus-rule.json", RulesBackupFormat.shareFileName("???"))
        assertTrue(RulesBackupFormat.shareFileName("x".repeat(200)).length <= "argus-rule-.json".length + 40)
    }

    // --- aiuti ---------------------------------------------------------------------------------

    private fun file(vararg drafts: AutomationDraft): ByteArray =
        RulesBackupCodec.encode(drafts.map { BackupRule(true, it) }, "now", "0.7.2").toByteArray()

    private suspend fun armed(id: String, draft: AutomationDraft): Automation {
        val saved = assertIs<DraftWriteResult.Saved>(
            repository.create(NewDraft(DraftId("draft-$id"), AutomationId(id), draft, atMillis = 1_000)),
        ).snapshot
        return assertIs<DraftArmResult.Armed>(repository.arm(saved.id, saved.revision, saved.fingerprint)).automation
    }

    private class MemoryDocuments : BackupDocumentIo {
        val files = mutableMapOf<Uri, ByteArray>()
        var readFailure: Exception? = null
        var writeFailure: Exception? = null

        override suspend fun read(source: Uri, maxBytes: Int): ByteArray? {
            readFailure?.let { throw it }
            val bytes = files[source] ?: throw FileNotFoundException()
            return if (bytes.size > maxBytes) null else bytes
        }

        override suspend fun write(destination: Uri, bytes: ByteArray) {
            writeFailure?.let { throw it }
            files[destination] = bytes
        }
    }
}
