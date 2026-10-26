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
import dev.argus.automation.LogRetentionStore
import dev.argus.automation.RenderLanguageSource
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAuditSink
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomExecutionJournal
import dev.argus.data.RoomJournalMaintenance
import dev.argus.data.RoomDeferredReplyStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.entities.AuditEntity
import dev.argus.data.entities.DeferredReplyEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalEntry
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionStatus
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
import kotlinx.coroutines.flow.first
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
import android.os.Build
import javax.crypto.KeyGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        val viewModel = viewModel(context, RenderLanguageSource { RenderLanguage.EN })
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect(messages::add)
        }

        viewModel.onSendNow("missing")
        awaitUntil("english missing-reply feedback") { messages.isNotEmpty() }

        assertEquals("No deferred reply is associated with this entry.", messages.single())
    }


    // --- B-lingua: il Log si rigenera al cambio di lingua --------------------------------------

    @Test
    fun `log rows regenerate when the phone language changes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var current = RenderLanguage.IT
        val source = RenderLanguageSource { current }
        RoomAuditSink(db.auditDao()).record(AuditEvent(AutomationId("lang-rule"), AuditKind.SUPPRESSED_COOLDOWN, 2_000))
        val viewModel = viewModel(context, source)
        backgroundScope.launch { viewModel.state.collect { } }

        awaitUntil("riga in italiano") {
            viewModel.state.value.entries.singleOrNull()?.summary == "soppressa dal cooldown"
        }

        current = RenderLanguage.EN
        source.refresh()

        awaitUntil("riga in inglese") {
            viewModel.state.value.entries.singleOrNull()?.summary == "suppressed by cooldown"
        }
        assertEquals("Deleted automation", viewModel.state.value.entries.single().automationName)
    }

    @Test
    fun `runtime messages follow the current language at the moment they are produced`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var current = RenderLanguage.IT
        val source = RenderLanguageSource { current }
        val viewModel = viewModel(context, source)
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.collect(messages::add) }

        viewModel.onSendNow("missing")
        awaitUntil("messaggio italiano") { messages.size == 1 }
        current = RenderLanguage.EN
        source.refresh()
        viewModel.onSendNow("missing")
        awaitUntil("messaggio inglese") { messages.size == 2 }

        assertEquals("Nessuna risposta differita per questa voce.", messages[0])
        assertEquals("No deferred reply is associated with this entry.", messages[1])
    }

    // --- B32 (c): il filtro segue gli argomenti della destinazione ---------------------------

    @Test
    fun `the log filter follows the route arguments and a plain reopen shows the full log`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sink = RoomAuditSink(db.auditDao())
        sink.record(AuditEvent(AutomationId("rule-a"), AuditKind.SUPPRESSED_COOLDOWN, 1_000))
        sink.record(AuditEvent(AutomationId("rule-b"), AuditKind.SUPPRESSED_COOLDOWN, 2_000))
        val viewModel = viewModel(context)
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("log completo") { viewModel.state.value.entries.size == 2 }

        // Arrivo dal Dettaglio di una regola: si filtra.
        viewModel.onRouteArguments("rule-a", "Regola A")
        awaitUntil("filtro attivo") {
            viewModel.state.value.filterAutomationName == "Regola A" && viewModel.state.value.entries.size == 1
        }

        // Tocco sul tab Log (stessa destinazione riusata, argomenti vuoti): Log completo.
        viewModel.onRouteArguments(null, null)
        awaitUntil("filtro tolto") {
            viewModel.state.value.filterAutomationName == null && viewModel.state.value.entries.size == 2
        }
    }

    @Test
    fun `a filter removed by the user does not come back when the same arguments are replayed`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sink = RoomAuditSink(db.auditDao())
        sink.record(AuditEvent(AutomationId("rule-a"), AuditKind.SUPPRESSED_COOLDOWN, 1_000))
        sink.record(AuditEvent(AutomationId("rule-b"), AuditKind.SUPPRESSED_COOLDOWN, 2_000))
        val viewModel = viewModel(context)
        backgroundScope.launch { viewModel.state.collect { } }

        viewModel.onRouteArguments("rule-a", "Regola A")
        awaitUntil("filtro attivo") { viewModel.state.value.filterAutomationName == "Regola A" }
        viewModel.onClearFilter()
        awaitUntil("filtro tolto") { viewModel.state.value.entries.size == 2 }

        // Rotazione dello schermo: la destinazione ricompone con gli stessi argomenti.
        viewModel.onRouteArguments("rule-a", "Regola A")

        assertNull(viewModel.state.value.filterAutomationName)
        assertEquals(2, viewModel.state.value.entries.size)
    }

    // --- F11: conservazione, svuota, esporta -------------------------------------------------

    @Test
    fun `retention starts at thirty days and a new choice is stored and applied right away`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = System.currentTimeMillis()
        val sink = RoomAuditSink(db.auditDao())
        sink.record(AuditEvent(AutomationId("r"), AuditKind.SUPPRESSED_COOLDOWN, now - 20L * DAY))
        sink.record(AuditEvent(AutomationId("r"), AuditKind.SUPPRESSED_COOLDOWN, now - 60_000))
        val viewModel = viewModel(context)
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("due righe") { viewModel.state.value.entries.size == 2 }
        assertEquals(30, viewModel.state.value.retentionDays)

        viewModel.onSetRetention(7)

        awaitUntil("la riga di 20 giorni fa esce") { viewModel.state.value.entries.size == 1 }
        assertEquals(7, viewModel.state.value.retentionDays)
        assertEquals(7, LogRetentionStore(context).currentDays())
    }

    @Test
    fun `log rows carry the event instant so days are grouped by timestamp not by text`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        seedAuditRow("rule-day", ExecutionId("exec-day"))
        val viewModel = viewModel(context)
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("riga caricata") { viewModel.state.value.entries.isNotEmpty() }

        // seedAuditRow registra l'evento a 2_000 ms: il Log raggruppa i giorni da questo istante.
        assertEquals(2_000L, viewModel.state.value.entries.single().atMillis)
    }

    @Test
    fun `an unsupported retention value changes nothing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = viewModel(context)
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("stato iniziale") { !viewModel.state.value.loading }

        viewModel.onSetRetention(14)

        assertEquals(30, viewModel.state.value.retentionDays)
        assertEquals(30, LogRetentionStore(context).currentDays())
    }

    @Test
    fun `clearing the log keeps running executions and says so`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = armRule("clear-rule", "Regola da svuotare")
        val store = RoomAutomationStore(db.automationDao())
        val journal = RoomExecutionJournal(db.executionJournalDao())
        val sink = RoomAuditSink(db.auditDao())
        val done = ExecutionId("exec-done")
        store.claimFire(FireClaimRequest(id, TriggerEventId("e-done"), done, 1_000, 0))
        journal.finish(ExecutionCompletion(done, ExecutionStatus.SUCCEEDED, 1_001, 1, 0, 0))
        sink.record(AuditEvent(id, AuditKind.FIRED, 1_001, executionId = done))
        val running = ExecutionId("exec-running")
        store.claimFire(FireClaimRequest(id, TriggerEventId("e-running"), running, 2_000, 0))
        sink.record(AuditEvent(id, AuditKind.FIRED, 2_000, executionId = running))
        sink.record(AuditEvent(id, AuditKind.RULE_ARMED, 900, detail = "approval"))

        val viewModel = viewModel(context)
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.collect(messages::add) }
        backgroundScope.launch { viewModel.state.collect { } }
        awaitUntil("tre righe") { viewModel.state.value.entries.size == 3 }

        viewModel.onClearLog()

        awaitUntil("messaggio di conferma") { messages.isNotEmpty() }
        assertEquals("Log svuotato.", messages.single())
        awaitUntil("resta solo l'esecuzione in corso") { viewModel.state.value.entries.size == 1 }
        assertEquals(
            running.value,
            db.auditDao().recent(10).single().executionId,
        )
    }

    @Test
    fun `export emits a plain text report without personal data`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = armRule("rule-mario-rossi", "Sveglia di Mario Rossi")
        val store = RoomAutomationStore(db.automationDao())
        val journal = RoomExecutionJournal(db.executionJournalDao())
        val executionId = ExecutionId("exec-export")
        store.claimFire(FireClaimRequest(id, TriggerEventId("e-export"), executionId, 1_000, 0))
        journal.recordAction(
            ActionJournalEntry(executionId, 0, "set_wifi", ActionJournalOutcome.SUCCEEDED, 1_000),
        )
        journal.recordAction(
            ActionJournalEntry(
                executionId, 1, "invoke_llm", ActionJournalOutcome.FAILED, 1_001, errorCode = "backend_failed",
            ),
        )
        journal.finish(ExecutionCompletion(executionId, ExecutionStatus.PARTIAL, 1_002, 1, 1, 0))
        RoomAuditSink(db.auditDao()).record(AuditEvent(id, AuditKind.FIRED, 1_002, executionId = executionId))
        // Una riga storica con testo libero nel dettaglio (scritta prima di qualunque filtro).
        db.auditDao().insert(
            AuditEntity(
                automationId = id.value,
                kind = AuditKind.ERROR,
                atMillis = 1_500,
                detail = "Mario Rossi chiamato al 3331234567: pizza stasera",
            ),
        )

        val viewModel = viewModel(context)
        val exported = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.exports.collect(exported::add) }

        viewModel.onExport()
        awaitUntil("testo esportato") { exported.isNotEmpty() }
        val text = exported.single()

        assertTrue(text.contains("android_sdk: ${Build.VERSION.SDK_INT}"), text)
        assertTrue(text.contains(Build.MANUFACTURER), text)
        assertTrue(text.contains("rule ${LogExport.shortRuleId(id.value)}"), text)
        assertTrue(text.contains("FIRED"), text)
        assertTrue(text.contains("action 2 invoke_llm | FAILED | code backend_failed"), text)
        for (leak in listOf("Mario", "Rossi", "rule-mario-rossi", "3331234567", "pizza", "Sveglia")) {
            assertFalse(text.contains(leak), "L'export non deve contenere '$leak':\n$text")
        }
    }

    private suspend fun armRule(id: String, name: String): AutomationId {
        val drafts = RoomDraftRepository(db)
        val created = assertIs<DraftWriteResult.Saved>(
            drafts.create(
                NewDraft(
                    id = DraftId("draft-$id"),
                    automationId = AutomationId(id),
                    draft = AutomationDraft(
                        name = name,
                        trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
                        actions = listOf(Action.SetWifi(false)),
                    ),
                    atMillis = 1,
                ),
            ),
        ).snapshot
        return assertIs<DraftArmResult.Armed>(drafts.arm(created.id, created.revision, created.fingerprint))
            .automation.id
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
        languageSource: RenderLanguageSource = RenderLanguageSource { RenderLanguage.IT },
        clipboard: ClipboardCopier = AndroidClipboardCopier(context, expiryScheduler = { _, _ -> }),
        retention: LogRetentionStore = LogRetentionStore(context),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = ExecutionLogViewModel(
        savedStateHandle = savedStateHandle,
        context = context,
        database = db,
        deferredReplies = DeferredReplyManager(
            RoomDeferredReplyStore(db.deferredReplyDao()),
            cipher,
        ) { NOW_MILLIS },
        clipboard = clipboard,
        languageSource = languageSource,
        retention = retention,
        maintenance = RoomJournalMaintenance(db, logRetentionDays = retention::currentDays),
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
        const val DAY = 24L * 60 * 60 * 1_000
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
