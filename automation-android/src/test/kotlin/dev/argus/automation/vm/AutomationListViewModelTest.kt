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
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.entities.AutomationEntity
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftDeleteResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.shizuku.ShizukuGateway
import dev.argus.ui.model.AutomationListState
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * D5 lato lista: una modifica in attesa su una regola armata/disattivata è un badge sulla riga della
 * regola (che continua a scattare), non una riga a sé; il tocco sul badge porta alla bozza.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private lateinit var lastViewModel: AutomationListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        repository = RoomDraftRepository(db)
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
    fun `a pending edit is a badge on the armed rule row not a separate row`() = runTest {
        val (rule, editId) = armedRuleWithEdit("r1")
        val latest = collectState(viewModel())

        awaitUntil("riga con badge") { latest().rows.singleOrNull()?.pendingEditDraftId == editId }

        val row = latest().rows.single()
        assertEquals(rule.id.value, row.id)
        assertEquals(StatusBadge.ARMED, row.status)
        assertTrue(row.enabled, "la regola continua a scattare: il toggle resta attivo")
        assertEquals(editId, row.pendingEditDraftId)
        assertEquals(1, latest().pendingCount, "la modifica in attesa conta come in attesa")
        assertTrue(latest().rows.none { it.status == StatusBadge.PENDING_APPROVAL })
    }

    @Test
    fun `the pending filter keeps the armed rule with a pending edit and drops the others`() = runTest {
        armedRuleWithEdit("r1")
        armedRule("r2")
        val viewModel = viewModel()
        val latest = collectState(viewModel)
        awaitUntil("due regole") { latest().rows.size == 2 }

        viewModel.onFilter(StatusFilter.PENDING)
        awaitUntil("filtro applicato") { latest().filter == StatusFilter.PENDING }
        assertEquals(listOf("r1"), latest().rows.map { it.id })

        viewModel.onFilter(StatusFilter.ARMED)
        awaitUntil("filtro armate") { latest().filter == StatusFilter.ARMED }
        assertEquals(setOf("r1", "r2"), latest().rows.map { it.id }.toSet())
    }

    @Test
    fun `a disabled rule with a pending edit shows the badge and stays disabled`() = runTest {
        val first = assertIs<DraftWriteResult.Saved>(repository.create(newDraft("r3"))).snapshot
        val armed = assertIs<DraftArmResult.Armed>(
            repository.arm(first.id, first.revision, first.fingerprint),
        ).automation
        store.disable(armed.id)
        val disabled = assertNotNull(store.get(armed.id))
        val edit = assertIs<DraftWriteResult.Saved>(
            repository.create(
                newDraft("r3").copy(
                    id = DraftId("edit-r3"),
                    expectedAutomationFingerprint = disabled.approvalFingerprint,
                ),
            ),
        ).snapshot
        val latest = collectState(viewModel())

        awaitUntil("riga con badge") { latest().rows.singleOrNull()?.pendingEditDraftId == edit.id.value }

        val row = latest().rows.single()
        assertEquals(StatusBadge.DISABLED, row.status)
        assertEquals(false, row.enabled)
    }

    @Test
    fun `a brand new draft stays its own pending row without a badge`() = runTest {
        val draft = assertIs<DraftWriteResult.Saved>(repository.create(newDraft("n1"))).snapshot
        val latest = collectState(viewModel())

        awaitUntil("riga bozza") { latest().rows.isNotEmpty() }

        val row = latest().rows.single()
        assertEquals(draft.id.value, row.id)
        assertEquals(StatusBadge.PENDING_APPROVAL, row.status)
        assertNull(row.pendingEditDraftId)
    }

    @Test
    fun `a legacy paused rule is still replaced by its draft row`() = runTest {
        val (rule, editId) = armedRuleWithEdit("r4")
        // Stato di una versione precedente: regola in PENDING_APPROVAL e disabilitata.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE automations SET status = 'PENDING_APPROVAL', enabled = 0 WHERE id = ?",
            arrayOf(rule.id.value),
        )
        val latest = collectState(viewModel())

        awaitUntil("riga bozza") { latest().rows.isNotEmpty() }

        val row = latest().rows.single()
        assertEquals(editId, row.id)
        assertEquals(StatusBadge.PENDING_APPROVAL, row.status)
        assertNull(row.pendingEditDraftId)
    }

    @Test
    fun `rejecting the edit removes the badge and leaves the armed row`() = runTest {
        val (rule, editId) = armedRuleWithEdit("r5")
        val latest = collectState(viewModel())
        awaitUntil("riga con badge") { latest().rows.singleOrNull()?.pendingEditDraftId == editId }

        val draft = assertNotNull(repository.get(DraftId(editId)))
        assertEquals(DraftDeleteResult.Deleted, repository.delete(draft.id, draft.revision))

        awaitUntil("badge rimosso") {
            latest().rows.singleOrNull()?.let { it.id == rule.id.value && it.pendingEditDraftId == null } == true
        }
        val row = latest().rows.single()
        assertEquals(StatusBadge.ARMED, row.status)
        assertTrue(row.enabled)
        assertEquals(0, latest().pendingCount)
    }

    @Test
    fun `the rows regenerate their texts when the language source goes from english to italian`() = runTest {
        armedRule("lang")
        var system = RenderLanguage.EN
        val source = RenderLanguageSource { system }
        val latest = collectState(viewModel(source))
        awaitUntil("riga inglese") { latest().rows.singleOrNull()?.triggerSummary?.startsWith("Every day") == true }

        system = RenderLanguage.IT
        source.refresh()

        awaitUntil("riga italiana") {
            latest().rows.singleOrNull()?.triggerSummary?.startsWith("Ogni giorno") == true
        }
    }

    // --- B32: la lista non rilegge lo stato a ogni scatto ---------------------------------------------

    @Test
    fun `ten firings with the same drafts read the device state once`() = runTest {
        val rule = armedRule("fire")
        assertIs<DraftWriteResult.Saved>(repository.create(newDraft("pend")))
        val reads = AtomicInteger(0)
        val snapshots = FirePolicySnapshotProvider {
            reads.incrementAndGet()
            FirePolicySnapshot(
                knownTools = emptySet(),
                availableCapabilities = emptySet(),
                whitelistedConversationIds = emptySet(),
            )
        }
        val latest = collectState(viewModel(snapshots = snapshots))
        awaitUntil("due righe") { latest().rows.size == 2 }
        settle()
        assertEquals(1, reads.get(), "una review per la bozza alla prima emissione")

        // Ogni scatto aggiorna la regola e fa riemettere la lista.
        repeat(10) { i -> store.recordFired(rule.id, 10_000L + i) }
        awaitUntil("ultimo scatto visibile") {
            latest().rows.firstOrNull { it.id == "fire" }?.lastFiredLabel != null
        }
        settle()
        assertEquals(1, reads.get(), "dieci scatti non rileggono lo stato del dispositivo")

        // Una bozza nuova si legge da sola; quelle note restano in cache.
        assertIs<DraftWriteResult.Saved>(repository.create(newDraft("pend2")))
        awaitUntil("tre righe") { latest().rows.size == 3 }
        settle()
        assertEquals(2, reads.get(), "solo la bozza nuova viene riletta")

        // Cambia un input della review (le regole attive, che entrano nei conflitti): si rilegge tutto.
        store.disable(rule.id)
        awaitUntil("regola disattivata") {
            latest().rows.firstOrNull { it.id == "fire" }?.status == StatusBadge.DISABLED
        }
        settle()
        assertEquals(4, reads.get(), "le due bozze sono riviste dopo il cambio delle regole attive")
    }

    /** Tempo reale per lasciar arrivare eventuali emissioni ulteriori prima di contare. */
    private suspend fun settle() = withContext(Dispatchers.Default) { delay(400) }

    @Test
    fun `re-enabling a one shot rule whose time has passed says what to do`() = runTest {
        seedDisabledOneShotInPast("os1")
        val viewModel = viewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect(messages::add)
        }

        viewModel.onToggleEnabled("os1", true)
        awaitUntil("messaggio") { messages.isNotEmpty() }

        assertEquals(
            listOf("This rule's time has already passed: edit it to choose a new time."),
            messages,
        )
    }

    @Test
    fun `a failed re-enable for another reason keeps the generic message`() = runTest {
        val rule = armedRule("os2")
        store.disable(rule.id)
        val viewModel = viewModel(scheduler = EnableFailingScheduler(db, "os2", "scheduling_failed"))
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect(messages::add)
        }

        viewModel.onToggleEnabled("os2", true)
        awaitUntil("messaggio") { messages.isNotEmpty() }

        assertEquals(listOf("Scheduling failed: the rule remains disabled."), messages)
    }

    // --- helpers -------------------------------------------------------------------------------------

    /** Regola una tantum gia' disattivata il cui orario e' passato: il coordinator reale la rifiuta. */
    private suspend fun seedDisabledOneShotInPast(id: String): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = "auto-$id",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.DISABLED,
            trigger = Trigger.Time(at = "2020-01-01T09:00", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "test")),
            enabled = false,
        )
        val signed = unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
        db.automationDao().upsertPreservingLastFired(
            AutomationEntity(
                id = signed.id.value,
                name = signed.name,
                status = signed.status,
                enabled = signed.enabled,
                priority = signed.priority,
                cooldownMs = signed.cooldownMs,
                schemaVersion = signed.schemaVersion,
                json = ArgusJson.encodeToString(Automation.serializer(), signed),
            ),
        )
        return signed
    }

    private fun newDraft(id: String) = NewDraft(
        id = DraftId("draft-$id"),
        automationId = AutomationId(id),
        draft = AutomationDraft(
            name = "regola $id",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "test")),
        ),
        atMillis = 1_000,
    )

    private suspend fun armedRule(id: String) = run {
        val first = assertIs<DraftWriteResult.Saved>(repository.create(newDraft(id))).snapshot
        assertIs<DraftArmResult.Armed>(repository.arm(first.id, first.revision, first.fingerprint)).automation
    }

    private suspend fun armedRuleWithEdit(id: String): Pair<dev.argus.engine.model.Automation, String> {
        val rule = armedRule(id)
        val edit = assertIs<DraftWriteResult.Saved>(
            repository.create(
                newDraft(id).copy(
                    id = DraftId("edit-$id"),
                    draft = newDraft(id).draft.copy(name = "regola $id v2"),
                    expectedAutomationFingerprint = rule.approvalFingerprint,
                ),
            ),
        ).snapshot
        assertEquals(AutomationStatus.ARMED, store.get(rule.id)?.status)
        return rule to edit.id.value
    }

    private fun viewModel(
        languageSource: RenderLanguageSource = RenderLanguageSource { RenderLanguage.EN },
        scheduler: TimeAlarmRuntime = ListNoopTimeAlarmRuntime(),
        snapshots: FirePolicySnapshotProvider = FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = emptySet(),
                availableCapabilities = emptySet(),
                whitelistedConversationIds = emptySet(),
            )
        },
    ): AutomationListViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
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
            enablement = AutomationEnablementCoordinator(store, scheduler),
            shizuku = ShizukuGateway(context),
            database = db,
            whitelist = whitelist,
            savedStateHandle = SavedStateHandle(),
            context = context,
            languageSource = languageSource,
        ).also { lastViewModel = it }
    }

    /** Raccoglie lo stato (il flow e' WhileSubscribed) e ne espone l'ultimo valore. */
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
}

private class ListNoopTimeAlarmRuntime : TimeAlarmRuntime {
    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = AlarmDeliveryResult.Ignored

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport =
        ReconcileReport(emptyList(), emptyList(), emptyList(), emptyList())
}
