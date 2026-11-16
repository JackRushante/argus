package dev.argus.automation.vm

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.RenderLanguageSource
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.backup.BackupDocumentIo
import dev.argus.data.backup.BackupRule
import dev.argus.data.backup.RulesBackupCodec
import dev.argus.data.backup.RulesBackupFormat
import dev.argus.data.backup.RulesBackupService
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.ui.model.ReceivedImportState
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 0.7.2, «Importa in Argus»: ciò che arriva da un'altra app si mostra e basta; solo la conferma crea bozze
 * `IMPORT` da approvare, con l'import vero e una sola volta. Nulla si arma.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ReceivedImportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private val viewModels = mutableListOf<ReceivedImportViewModel>()
    private val files = mutableMapOf<Uri, ByteArray>()

    private val rule = AutomationDraft(
        name = "Notte tranquilla",
        trigger = Trigger.Time(cron = "0 23 * * *", tz = "UTC"),
        actions = listOf(Action.ShowNotification("Argus", "buonanotte")),
    )
    private val text = RulesBackupCodec.encode(listOf(BackupRule(true, rule)), "now", "0.7.2")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        repository = RoomDraftRepository(db)
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `received text shows what it holds and writes nothing until confirmed`() = runTest {
        val viewModel = viewModel()
        viewModel.receive(ReceivedPayload.Text(text))
        awaitUntil("anteprima") { viewModel.state.value is ReceivedImportState.Ready }

        val ready = assertIs<ReceivedImportState.Ready>(viewModel.state.value)
        assertEquals(listOf("Notte tranquilla"), ready.preview.ruleNames)
        assertTrue(ready.preview.title.startsWith("1 rule · "), ready.preview.title)
        assertEquals(emptyList(), repository.observeAll().first())

        viewModel.onConfirm()
        awaitUntil("import") { viewModel.state.value is ReceivedImportState.Done }

        val done = assertIs<ReceivedImportState.Done>(viewModel.state.value)
        assertEquals(1, done.importedCount)
        val drafts = repository.observeAll().first()
        assertEquals(listOf("Notte tranquilla"), drafts.map { it.draft.name })
        assertEquals(CreatedBy.IMPORT, drafts.single().createdBy)
        assertEquals(emptyList(), store.armed())

        // Una seconda conferma non importa di nuovo.
        viewModel.onConfirm()
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(1, repository.observeAll().first().size)
    }

    @Test
    fun `a received document of another app is read with the cap and previewed`() = runTest {
        val uri = Uri.parse("content://com.example.files/rule.json")
        files[uri] = text.toByteArray()
        val viewModel = viewModel()
        viewModel.receive(ReceivedPayload.Document(uri))

        awaitUntil("anteprima") { viewModel.state.value is ReceivedImportState.Ready }
        assertEquals(listOf("Notte tranquilla"), assertIs<ReceivedImportState.Ready>(viewModel.state.value).preview.ruleNames)
    }

    @Test
    fun `too large, unreadable, unsupported or not a rule are refused and cannot be confirmed`() = runTest {
        val cases = listOf(
            ReceivedPayload.Text("x".repeat(RulesBackupFormat.MAX_FILE_BYTES + 1)) to "too large",
            ReceivedPayload.Text("ciao") to "not an Argus rule",
            ReceivedPayload.Document(Uri.parse("content://com.example.files/none.json")) to "Unable to read",
            ReceivedPayload.Unsupported to "not an Argus rules export",
        )
        cases.forEach { (payload, expected) ->
            val viewModel = viewModel()
            viewModel.receive(payload)
            awaitUntil("rifiuto di $payload") { viewModel.state.value is ReceivedImportState.Rejected }
            val message = assertIs<ReceivedImportState.Rejected>(viewModel.state.value).message
            assertTrue(expected in message, "$payload: $message")
            viewModel.onConfirm()
        }
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(emptyList(), repository.observeAll().first())
    }

    @Test
    fun `content arrives only once, a second delivery is ignored`() = runTest {
        val viewModel = viewModel()
        viewModel.receive(ReceivedPayload.Text(text))
        awaitUntil("anteprima") { viewModel.state.value is ReceivedImportState.Ready }

        viewModel.receive(ReceivedPayload.Text("ciao"))
        withContext(Dispatchers.Default) { delay(200) }

        assertIs<ReceivedImportState.Ready>(viewModel.state.value)
    }

    // --- aiuti ---------------------------------------------------------------------------------

    private fun viewModel(): ReceivedImportViewModel {
        val service = RulesBackupService(
            automations = store,
            drafts = repository,
            documents = object : BackupDocumentIo {
                override suspend fun read(source: Uri, maxBytes: Int): ByteArray? {
                    val bytes = files[source] ?: throw FileNotFoundException()
                    return if (bytes.size > maxBytes) null else bytes
                }

                override suspend fun write(destination: Uri, bytes: ByteArray) = error("l'import non scrive file")
            },
            appVersion = { "0.7.2" },
        )
        return ReceivedImportViewModel(service, RenderLanguageSource { RenderLanguage.EN })
            .also { viewModels += it }
    }

    /** Il lavoro attraversa executor Room reali: attesa in tempo reale, non virtuale. */
    private suspend fun awaitUntil(label: String, condition: () -> Boolean) {
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (!condition()) delay(20)
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timeout in attesa di: $label", error)
        }
    }
}
