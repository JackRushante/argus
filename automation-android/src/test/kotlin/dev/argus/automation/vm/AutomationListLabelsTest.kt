package dev.argus.automation.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.AlarmDeliveryResult
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.ArmedAutomationRegistrar
import dev.argus.automation.AutomationEnablementCoordinator
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ReconcileReport
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.apps.InstalledAppCatalog
import dev.argus.automation.apps.InstalledAppEntry
import dev.argus.automation.labels.LabelPauseStore
import dev.argus.automation.labels.RoomRuleLabelDirectory
import dev.argus.automation.manual.FakePauseStore
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.RoomRuleLabelStore
import dev.argus.engine.model.Action
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.RuleLabels
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.shizuku.ShizukuGateway
import dev.argus.ui.model.AutomationListState
import dev.argus.ui.model.LabelChipUi
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.RuleSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Etichette, ricerca e pausa per gruppo sulla lista Automazioni (0.7.2): chip con i conteggi sull'elenco
 * completo, filtro per etichetta insieme a quello di stato, gruppo in pausa sulle righe, etichetta che sparisce
 * con l'ultima regola, e i dati della riga che la ricerca usa (azioni e nomi delle app).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationListLabelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private lateinit var labels: RoomRuleLabelStore
    private val pauses = MemoryLabelPause()
    private lateinit var lastViewModel: AutomationListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        repository = RoomDraftRepository(db)
        labels = RoomRuleLabelStore(db)
    }

    @After
    fun tearDown() {
        if (::lastViewModel.isInitialized) {
            runBlocking { lastViewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `no labels yet means only All with the total`() = runTest {
        armed("r1")
        armed("r2")
        val latest = collectState(viewModel())

        awaitUntil("lista") { !latest().loading && latest().rows.size == 2 }
        assertEquals(emptyList(), latest().labels)
        assertEquals(2, latest().totalCount)
        assertNull(latest().selectedLabel)
    }

    @Test
    fun `a label filters the list together with the status filter, counts stay on the full list`() = runTest {
        armed("r1")
        armed("r2")
        armed("r3")
        store.disable(AutomationId("r3"))
        labels.add(AutomationId("r1"), "Casa")
        labels.add(AutomationId("r3"), "casa")
        labels.add(AutomationId("r2"), "Lavoro")
        val viewModel = viewModel()
        val latest = collectState(viewModel)
        awaitUntil("chip") { latest().labels.size == 2 }

        assertEquals(
            listOf(
                LabelChipUi("Casa", count = 2, activeCount = 1, waitingCount = 0, paused = false),
                LabelChipUi("Lavoro", count = 1, activeCount = 1, waitingCount = 0, paused = false),
            ),
            latest().labels,
        )

        viewModel.onSelectLabel("CASA")
        awaitUntil("filtro Casa") { latest().rows.size == 2 }
        assertEquals("Casa", latest().selectedLabel)
        assertEquals(setOf("r1", "r3"), latest().rows.map { it.id }.toSet())
        assertEquals(3, latest().totalCount)
        assertEquals(2, latest().summary?.armedCount, "il riepilogo resta sull'elenco completo")

        viewModel.onFilter(StatusFilter.DISABLED)
        awaitUntil("Casa e disattivate") { latest().rows.map { it.id } == listOf("r3") }

        viewModel.onFilter(StatusFilter.ALL)
        viewModel.onSelectLabel(null)
        awaitUntil("tutte") { latest().rows.size == 3 }
    }

    @Test
    fun `pausing a group marks its rules and resuming clears them`() = runTest {
        armed("r1")
        armed("r2")
        labels.add(AutomationId("r1"), "Casa")
        labels.add(AutomationId("r1"), "Lavoro")
        labels.add(AutomationId("r2"), "Lavoro")
        val viewModel = viewModel()
        val latest = collectState(viewModel)
        awaitUntil("chip") { latest().labels.size == 2 }

        viewModel.onSetLabelPaused("Casa", true)
        awaitUntil("Casa in pausa") { latest().rows.single { it.id == "r1" }.groupPaused }
        assertEquals(false, latest().rows.single { it.id == "r2" }.groupPaused)
        assertTrue(latest().labels.single { it.label == "Casa" }.paused)

        viewModel.onSetLabelPaused("casa", false)
        awaitUntil("ripresa") { latest().rows.none { it.groupPaused } }
    }

    @Test
    fun `a label that loses its last rule disappears and the selection goes back to All`() = runTest {
        armed("r1")
        armed("r2")
        labels.add(AutomationId("r1"), "Casa")
        val viewModel = viewModel()
        val latest = collectState(viewModel)
        awaitUntil("chip") { latest().labels.isNotEmpty() }
        viewModel.onSelectLabel("Casa")
        viewModel.onSetLabelPaused("Casa", true)
        awaitUntil("filtro") { latest().rows.size == 1 && latest().labels.single().paused }

        // La regola viene eliminata mentre il suo gruppo è in pausa.
        store.delete(AutomationId("r1"))

        awaitUntil("etichetta sparita") { latest().labels.isEmpty() && latest().rows.size == 1 }
        assertNull(latest().selectedLabel)
        assertEquals("r2", latest().rows.single().id)
    }

    @Test
    fun `an imported draft shows its labels and counts as waiting`() = runTest {
        armed("r1")
        labels.add(AutomationId("r1"), "Casa")
        repository.createAll(listOf(newDraft("imp")), mapOf(DraftId("draft-imp") to listOf("casa")))
        val latest = collectState(viewModel())

        awaitUntil("bozza con etichetta") { latest().labels.singleOrNull()?.count == 2 }
        val chip = latest().labels.single()
        assertEquals(1, chip.activeCount)
        assertEquals(1, chip.waitingCount)
        val draftRow = latest().rows.single { it.status == StatusBadge.PENDING_APPROVAL }
        assertEquals(listOf("Casa"), draftRow.labels)
    }

    @Test
    fun `rows carry the action summary and the app names that the search reads`() = runTest {
        armed(
            "yt",
            AutomationDraft(
                name = "Pausa video",
                trigger = Trigger.AppEvent("com.google.android.youtube", AppEventKind.OPENED),
                actions = listOf(Action.ShowNotification("Argus", "basta video")),
            ),
        )
        armed("r2")
        val catalog = object : InstalledAppCatalog {
            override suspend fun launchableApps() = listOf(InstalledAppEntry("YouTube", "com.google.android.youtube"))
            override suspend fun isInstalled(packageName: String) = true
            override suspend fun missing(packages: Collection<String>) = emptySet<String>()
        }
        val latest = collectState(viewModel(appCatalog = catalog))
        awaitUntil("righe") { latest().rows.size == 2 }

        val video = latest().rows.single { it.id == "yt" }
        assertEquals(listOf("YouTube"), video.appNames)
        assertTrue(!video.actionSummary.isNullOrBlank())
        assertEquals(listOf("yt"), RuleSearch.filter(latest().rows, "youtube").map { it.id })
        assertEquals(emptyList(), RuleSearch.filter(latest().rows, "zzz"))
    }

    // --- aiuti ---

    private class MemoryLabelPause : LabelPauseStore {
        private val state = MutableStateFlow(emptySet<String>())
        override val pausedKeys: StateFlow<Set<String>> = state
        override fun setPaused(label: String, paused: Boolean) {
            val key = RuleLabels.key(label)
            state.value = if (paused) state.value + key else state.value - key
        }

        override fun retainOnly(keysInUse: Set<String>) {
            state.value = state.value intersect keysInUse
        }
    }

    private fun newDraft(id: String, draft: AutomationDraft? = null) = NewDraft(
        id = DraftId("draft-$id"),
        automationId = AutomationId(id),
        draft = draft ?: AutomationDraft(
            name = "regola $id",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "test")),
        ),
        createdBy = CreatedBy.IMPORT,
        atMillis = 1_000,
    )

    private suspend fun armed(id: String, draft: AutomationDraft? = null) {
        val first = assertIs<DraftWriteResult.Saved>(repository.create(newDraft(id, draft))).snapshot
        assertIs<DraftArmResult.Armed>(repository.arm(first.id, first.revision, first.fingerprint))
    }

    private fun viewModel(appCatalog: InstalledAppCatalog = dev.argus.automation.apps.EmptyInstalledAppCatalog): AutomationListViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
        val snapshots = FirePolicySnapshotProvider {
            FirePolicySnapshot(knownTools = emptySet(), availableCapabilities = emptySet(), whitelistedConversationIds = emptySet())
        }
        val approvals = ApprovalFlow(
            drafts = repository,
            approvals = ApprovalService(repository, DraftValidator(emptySet()), whitelist),
            automations = store,
            capabilities = snapshots,
            location = CurrentLocationProvider { null },
            registrar = ArmedAutomationRegistrar { true },
        )
        return AutomationListViewModel(
            store = store,
            drafts = repository,
            approvals = approvals,
            enablement = AutomationEnablementCoordinator(store, NoopAlarms()),
            shizuku = ShizukuGateway(context),
            database = db,
            whitelist = whitelist,
            savedStateHandle = SavedStateHandle(),
            context = context,
            languageSource = RenderLanguageSource { RenderLanguage.EN },
            pause = FakePauseStore(),
            labels = RoomRuleLabelDirectory(labels, pauses),
            appCatalog = appCatalog,
        ).also { lastViewModel = it }
    }

    private fun TestScope.collectState(viewModel: AutomationListViewModel): () -> AutomationListState {
        var latest = viewModel.state.value
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { latest = it }
        }
        return { latest }
    }

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

    private class NoopAlarms : TimeAlarmRuntime {
        override suspend fun onAlarm(
            automationId: AutomationId,
            approvalFingerprint: ApprovalFingerprint,
            eventAtMillis: Long,
        ): AlarmDeliveryResult = AlarmDeliveryResult.Ignored

        override suspend fun reconcile(reason: ReconcileReason): ReconcileReport =
            ReconcileReport(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
