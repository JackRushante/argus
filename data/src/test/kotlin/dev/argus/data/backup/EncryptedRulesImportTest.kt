package dev.argus.data.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomDraftRepository
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
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
import java.io.FileNotFoundException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Import di un `.argusbackup` (0.7.2) e testo dell'export completo usato dal backup automatico. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedRulesImportTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private val documents = MapDocuments()
    private val plainFile: Uri = Uri.parse("content://test/rules.json")
    private val encryptedFile: Uri = Uri.parse("content://test/argus-rules-20261116-0310.argusbackup")
    private val salt = ByteArray(16) { 5 }
    private var nextId = 0

    // Poche iterazioni: la derivazione vera è provata in EncryptedBackupFormatTest.
    private val fastDeriver = BackupKeyDeriver { passphrase, salt ->
        BackupKeyDerivation.derive(passphrase, salt, iterations = 1_000)
    }

    private fun service() = RulesBackupService(
        automations = store,
        drafts = repository,
        documents = documents,
        appVersion = { "0.7.2-test" },
        nowMillis = { 1_234_567L },
        newId = { "imp-${++nextId}" },
        keyDeriver = fastDeriver,
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
    fun `the full backup text is byte for byte the plain export without the personal data option`() = runTest {
        armed("a", BackupFixtures.rich())
        armed("b", BackupFixtures.simple("Semplice"))

        service().export(plainFile, stripPersonalData = false)
        val built = assertIs<BuiltBackup.Ready>(service().buildFullBackup())

        assertContentEquals(documents.files.getValue(plainFile), built.bytes)
        assertEquals(2, built.exported)
    }

    @Test
    fun `an encrypted file asks for the passphrase and changes nothing`() = runTest {
        armed("a", BackupFixtures.simple("Semplice"))
        writeEncrypted("cavallo-batteria")

        assertEquals(ImportOutcome.Failed(BackupFailure.PASSPHRASE_REQUIRED), service().import(encryptedFile))
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun `with the right passphrase every rule arrives as a draft to approve, nothing armed`() = runTest {
        armed("a", BackupFixtures.simple("Semplice"))
        armed("b", BackupFixtures.rich())
        val armedBefore = store.armed().map { it.id }
        writeEncrypted("cavallo-batteria")

        val outcome = assertIs<ImportOutcome.Done>(
            service().importEncrypted(encryptedFile, "cavallo-batteria".toCharArray()),
        )

        assertEquals(2, outcome.summary.imported)
        val drafts = repository.observeAll().first()
        assertEquals(2, drafts.size)
        assertTrue(drafts.all { it.createdBy == CreatedBy.IMPORT })
        assertEquals(armedBefore, store.armed().map { it.id })
        assertTrue(store.all().none { it.id.value.startsWith("imp-") })
        assertTrue(store.all().all { it.status == AutomationStatus.ARMED })
    }

    @Test
    fun `a wrong passphrase is a clear failure, not a parse error`() = runTest {
        armed("a", BackupFixtures.simple("Semplice"))
        writeEncrypted("cavallo-batteria")

        assertEquals(
            ImportOutcome.Failed(BackupFailure.WRONG_PASSPHRASE),
            service().importEncrypted(encryptedFile, "cavallo-batteriA".toCharArray()),
        )
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun `a truncated encrypted file is reported as damaged`() = runTest {
        documents.files[encryptedFile] = "ARGUSBK1".toByteArray() + ByteArray(20)

        assertEquals(ImportOutcome.Failed(BackupFailure.PASSPHRASE_REQUIRED), service().import(encryptedFile))
        assertEquals(
            ImportOutcome.Failed(BackupFailure.ENCRYPTED_DAMAGED),
            service().importEncrypted(encryptedFile, "cavallo-batteria".toCharArray()),
        )
    }

    @Test
    fun `plain files keep importing exactly as before, size limit included`() = runTest {
        armed("a", BackupFixtures.simple("Semplice"))
        service().export(plainFile, stripPersonalData = false)

        assertEquals(1, assertIs<ImportOutcome.Done>(service().import(plainFile)).summary.imported)
        // Un file in chiaro passato per la via cifrata si importa come sempre.
        assertEquals(
            1,
            assertIs<ImportOutcome.Done>(service().importEncrypted(plainFile, "x".toCharArray())).summary.imported,
        )
        documents.files[plainFile] = ByteArray(RulesBackupFormat.MAX_FILE_BYTES + 1) { ' '.code.toByte() }
        assertEquals(ImportOutcome.Failed(BackupFailure.FILE_TOO_LARGE), service().import(plainFile))
    }

    private suspend fun writeEncrypted(passphrase: String) {
        val built = assertIs<BuiltBackup.Ready>(service().buildFullBackup())
        val key = BackupKeyDerivation.derive(passphrase.toCharArray(), salt, iterations = 1_000)
        documents.files[encryptedFile] = EncryptedBackupFormat.seal(built.bytes, key, salt, ByteArray(12) { 1 })
    }

    private suspend fun armed(id: String, draft: dev.argus.engine.model.AutomationDraft) {
        val saved = assertIs<DraftWriteResult.Saved>(
            repository.create(
                NewDraft(
                    id = DraftId("draft-$id"),
                    automationId = AutomationId(id),
                    draft = draft,
                    createdBy = CreatedBy.LLM,
                    atMillis = 1_000,
                ),
            ),
        ).snapshot
        assertIs<DraftArmResult.Armed>(repository.arm(saved.id, saved.revision, saved.fingerprint))
    }

    private class MapDocuments : BackupDocumentIo {
        val files = mutableMapOf<Uri, ByteArray>()

        override suspend fun read(source: Uri, maxBytes: Int): ByteArray? {
            val bytes = files[source] ?: throw FileNotFoundException()
            return if (bytes.size > maxBytes) null else bytes
        }

        override suspend fun write(destination: Uri, bytes: ByteArray) {
            files[destination] = bytes
        }
    }
}
