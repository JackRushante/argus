package dev.argus.data.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomDraftRepository
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.safety.DraftId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** «Usa questo modello» (0.7.2): il modello passa dallo stesso import di un file e diventa una bozza da approvare. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplateImportTest {
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
    fun `a template becomes an imported draft through the import pipeline, and arms nothing`() = runTest {
        val catalog = RuleTemplateCatalog { path -> File("src/main/assets", path).inputStream() }
        val service = RulesBackupService(
            automations = store,
            drafts = repository,
            documents = object : BackupDocumentIo {
                override suspend fun read(source: Uri, maxBytes: Int): ByteArray? = error("non usato")
                override suspend fun write(destination: Uri, bytes: ByteArray) = error("non usato")
            },
            appVersion = { "0.7.2" },
        )
        val bytes = assertNotNull(catalog.ruleBytes("battery-low-saver", italian = true, zone = ZoneId.of("UTC")))

        val outcome = assertIs<ImportOutcome.Done>(service.importBytes(bytes))

        val draft = assertNotNull(repository.get(DraftId(outcome.draftIds.single())))
        assertEquals(CreatedBy.IMPORT, draft.createdBy)
        assertEquals("Batteria al 20%: risparmio", draft.draft.name)
        assertEquals(Trigger.Battery(below = 20), draft.draft.trigger)
        assertEquals(emptyList(), store.armed())
    }
}
