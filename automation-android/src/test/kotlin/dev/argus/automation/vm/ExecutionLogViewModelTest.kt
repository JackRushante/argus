package dev.argus.automation.vm

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.AndroidClipboardCopier
import dev.argus.automation.ClipboardCopier
import dev.argus.automation.DeferredReplyCipher
import dev.argus.automation.DeferredReplyManager
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAuditSink
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomDeferredReplyStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.entities.DeferredReplyEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import javax.crypto.KeyGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionLogViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var lastViewModel: ExecutionLogViewModel
    private val cipher = DeferredReplyCipher(
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> { key } },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        // Il viewModelScope vive su Main: va spento e atteso PRIMA di resetMain, altrimenti
        // "Dispatchers.Main is used concurrently with setting it" (flake reale osservato).
        if (::lastViewModel.isInitialized) {
            runBlocking {
                lastViewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `send now copies a sensitive clip without consuming the reply`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executionId = seedDeferredExecution("rule-e13", "ci vediamo alle 19:30")
        val logId = seedAuditRow("rule-e13", executionId)
        val viewModel = viewModel(context)
        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.messages.collect(messages::add) }
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("righe log caricate") { viewModel.state.value.entries.isNotEmpty() }

        viewModel.onSendNow(logId)
        awaitUntil("primo esito copia") { messages.isNotEmpty() }

        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals("ci vediamo alle 19:30", clip.getItemAt(0).text.toString())
        assertTrue(
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
            "la clip deve essere marcata sensibile",
        )
        assertEquals(1, messages.size)
        assertFalse(
            messages.single().contains("19:30"),
            "il testo della reply non entra nei messaggi UI",
        )

        // Niente è stato segnato: finché non scade si può ricopiare, ogni volta con lo stesso testo.
        clipboard.clearPrimaryClip()
        viewModel.onSendNow(logId)
        awaitUntil("secondo esito copia") { messages.size >= 2 }
        assertEquals(messages.first(), messages.last(), "la seconda copia riesce come la prima")
        assertEquals("ci vediamo alle 19:30", clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test
    fun `send now uses the injected clipboard copier with the literal reply text`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executionId = seedDeferredExecution("rule-copier", "testo da copiare")
        val logId = seedAuditRow("rule-copier", executionId)
        val copier = RecordingClipboardCopier()
        val viewModel = viewModel(context, clipboard = copier)
        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.messages.collect(messages::add) }
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("righe log caricate") { viewModel.state.value.entries.isNotEmpty() }

        viewModel.onSendNow(logId)
        awaitUntil("esito copia") { messages.isNotEmpty() }

        assertEquals(listOf("testo da copiare"), copier.literals)
    }

    @Test
    fun `clipboard failure reports the error and leaves the reply available`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executionId = seedDeferredExecution("rule-fail", "testo")
        val logId = seedAuditRow("rule-fail", executionId)
        val copier = RecordingClipboardCopier(result = ActionResult.Failure("clipboard_write_failed"))
        val viewModel = viewModel(context, clipboard = copier)
        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.messages.collect(messages::add) }
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("righe log caricate") { viewModel.state.value.entries.isNotEmpty() }

        viewModel.onSendNow(logId)
        awaitUntil("esito copia fallita") { messages.isNotEmpty() }
        assertEquals("Impossibile copiare la risposta negli appunti.", messages.last())

        // Non consumata: dopo il ripristino della clipboard la stessa risposta si copia ancora.
        copier.result = ActionResult.Success
        viewModel.onSendNow(logId)
        awaitUntil("copia riuscita al secondo tentativo") { messages.size >= 2 }
        assertEquals(2, copier.literals.size)
        assertTrue(messages.last().startsWith("Risposta copiata"), messages.last())
    }

    @Test
    fun `mark as sent consumes the reply exactly once`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executionId = seedDeferredExecution("rule-mark", "ok")
        val logId = seedAuditRow("rule-mark", executionId)
        val viewModel = viewModel(context)
        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.messages.collect(messages::add) }
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("righe log caricate") { viewModel.state.value.entries.isNotEmpty() }

        viewModel.onMarkSent(logId)
        awaitUntil("segnata come inviata") { messages.isNotEmpty() }
        assertEquals("Segnata come inviata.", messages.last())

        viewModel.onMarkSent(logId)
        awaitUntil("secondo segna come inviata") { messages.size >= 2 }
        assertEquals(
            "La risposta non è più disponibile: scaduta o già consegnata.",
            messages.last(),
            "il consumo è one-shot",
        )

        viewModel.onSendNow(logId)
        awaitUntil("copia dopo il consumo") { messages.size >= 3 }
        assertEquals(
            "La risposta non è più disponibile: scaduta o già consegnata.",
            messages.last(),
            "una risposta segnata come inviata non si ricopia",
        )
    }

    @Test
    fun `mark as sent on an unknown row explains itself`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = viewModel(context)
        val messages = mutableListOf<String>()
        // Unconfined: la sottoscrizione parte subito, prima dell'emit sotto test (SharedFlow senza replay).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.collect(messages::add) }

        viewModel.onMarkSent("999999")
        awaitUntil("esito per riga sconosciuta") { messages.isNotEmpty() }

        assertEquals("Nessuna risposta differita per questa voce.", messages.single())
    }

    @Test
    fun `rows without a deferred reply explain themselves`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executionId = seedDeferredExecution("rule-empty", "testo", expired = true)
        val logId = seedAuditRow("rule-empty", executionId)
        val viewModel = viewModel(context)
        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.messages.collect(messages::add) }
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("righe log caricate") { viewModel.state.value.entries.isNotEmpty() }

        viewModel.onSendNow(logId)
        awaitUntil("esito per reply scaduta") { messages.isNotEmpty() }
        assertEquals("La risposta non è più disponibile: scaduta o già consegnata.", messages.last())

        viewModel.onSendNow("999999")
        awaitUntil("esito per riga sconosciuta") { messages.size >= 2 }
        assertEquals("Nessuna risposta differita per questa voce.", messages.last())
    }

    @Test
    fun `english log actions emit english runtime feedback`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = viewModel(context, RenderLanguage.EN)
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect(messages::add)
        }

        viewModel.onSendNow("missing")
        awaitUntil("english missing-reply feedback") { messages.isNotEmpty() }

        assertEquals("No deferred reply is associated with this entry.", messages.single())
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

    private fun viewModel(
        context: Context,
        language: RenderLanguage = RenderLanguage.IT,
        clipboard: ClipboardCopier = AndroidClipboardCopier(context, expiryScheduler = { _, _ -> }),
    ) = ExecutionLogViewModel(
        savedStateHandle = SavedStateHandle(),
        context = context,
        database = db,
        deferredReplies = DeferredReplyManager(
            RoomDeferredReplyStore(db.deferredReplyDao()),
            cipher,
        ) { NOW_MILLIS },
        clipboard = clipboard,
        language = language,
    ).also { lastViewModel = it }

    private suspend fun seedDeferredExecution(
        id: String,
        text: String,
        expired: Boolean = false,
    ): ExecutionId {
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
            FireClaimRequest(AutomationId(id), TriggerEventId("event-$id"), executionId, 1_000, 0),
        )
        assertTrue(
            RoomDeferredReplyStore(db.deferredReplyDao()).save(
                DeferredReplyEntity(
                    executionId = executionId.value,
                    actionIndex = 0,
                    packageName = "com.whatsapp",
                    createdAtMillis = 1_000,
                    expiresAtMillis = if (expired) NOW_MILLIS - 1 else NOW_MILLIS + 60_000,
                    consumedAtMillis = null,
                    payload = cipher.encrypt(text),
                ),
            ),
        )
        return executionId
    }

    private suspend fun seedAuditRow(id: String, executionId: ExecutionId): String {
        RoomAuditSink(db.auditDao()).record(
            AuditEvent(
                AutomationId(id),
                AuditKind.FIRED,
                2_000,
                executionId = executionId,
            ),
        )
        return db.auditDao().recent(10)
            .first { it.executionId == executionId.value }
            .id
            .toString()
    }

    private companion object {
        const val NOW_MILLIS = 10_000L
    }
}

private class RecordingClipboardCopier(
    var result: ActionResult = ActionResult.Success,
) : ClipboardCopier {
    val literals = mutableListOf<String>()

    override fun copy(event: TriggerEvent, extractionRegex: String?): ActionResult =
        error("non usato dal log")

    override fun copyLiteral(text: String): ActionResult {
        literals += text
        return result
    }
}
