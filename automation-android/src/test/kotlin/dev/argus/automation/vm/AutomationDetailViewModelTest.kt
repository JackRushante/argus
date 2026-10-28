package dev.argus.automation.vm

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AndroidCapabilityState
import dev.argus.automation.AndroidCapabilityStateSource
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.ArmedAutomationRegistrar
import dev.argus.automation.AutomationEnablementCoordinator
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.GenerativeReadiness
import dev.argus.automation.GenerativeRuntimeReadiness
import dev.argus.automation.apps.EmptyInstalledAppCatalog
import dev.argus.automation.apps.InstalledAppCatalog
import dev.argus.automation.apps.InstalledAppEntry
import dev.argus.automation.LogRetentionStore
import dev.argus.automation.AlarmDeliveryResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ReconcileReport
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime
import dev.argus.automation.geofence.GeofenceRegistration
import dev.argus.automation.geofence.GeofenceStateStore
import dev.argus.automation.geofence.MemoryGeofenceStateStore
import dev.argus.automation.geofence.NoopGeofenceTriggerRuntime
import dev.argus.automation.manual.ManualEntryService
import dev.argus.automation.manual.ManualHarness
import dev.argus.automation.sensor.NoopSensorTriggerRuntime
import dev.argus.automation.sensor.SensorReconcileReport
import dev.argus.automation.sensor.SensorTriggerRuntime
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAuditSink
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.RoomExecutionJournal
import dev.argus.data.entities.AuditEntity
import dev.argus.data.entities.AutomationEntity
import dev.argus.data.entities.ScheduledTimeAlarmEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.NoopAuditSink
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.AppInfoUi
import dev.argus.ui.model.ManualEntryUi
import dev.argus.ui.model.RuleStatsWindow
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var lastViewModel: AutomationDetailViewModel
    private val applicationScopes = mutableListOf<CoroutineScope>()
    private var executionSequence = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        if (::lastViewModel.isInitialized) {
            runBlocking {
                lastViewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
        runBlocking { applicationScopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `user delete records RULE_DELETED user and removes the rule`() = runTest {
        val automation = seedArmedAutomation("a1")
        val store = RoomAutomationStore(db.automationDao())
        val audit = DetailAuditRecorder()
        val viewModel = viewModel("a1", store, audit)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect(events::add)
        }

        viewModel.onDelete()
        awaitUntil("chiusura dopo delete") { events.any { it is DetailEvent.Close } }

        assertNull(store.get(automation.id), "la regola deve sparire dallo store")
        val event = audit.events.single { it.kind == AuditKind.RULE_DELETED }
        assertEquals("user", event.detail)
        assertEquals(automation.id, event.automationId)
        assertTrue(
            audit.events.none { it.kind != AuditKind.RULE_DELETED },
            "il delete non produce altri eventi audit",
        )
    }

    @Test
    fun `english detail emits english runtime messages`() = runTest {
        seedArmedAutomation("english")
        val viewModel = viewModel(
            "english",
            RoomAutomationStore(db.automationDao()),
            DetailAuditRecorder(),
            RenderLanguage.EN,
        )
        awaitUntil("english detail loaded") { viewModel.state.value.detail != null }
        assertEquals(
            "Run now is not available yet.",
            viewModel.state.value.detail?.runNowBlockedReason,
        )
        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect(events::add)
        }

        viewModel.onRunNow()
        awaitUntil("english run-now feedback") { events.isNotEmpty() }

        assertEquals(
            "Run now is not available yet.",
            (events.single() as DetailEvent.Message).text,
        )
    }

    // --- F10: statistiche per regola (ultimi 7 e 30 giorni) ---------------------------------------

    @Test
    fun `an armed rule shows its firings for the last 7 and 30 days`() = runTest {
        val automation = seedArmedAutomation("stats")
        val now = System.currentTimeMillis()
        recordFire(automation.id, now - 1 * DAY_MS, ExecutionStatus.SUCCEEDED)
        recordFire(automation.id, now - 2 * DAY_MS, ExecutionStatus.SUCCEEDED)
        recordFire(automation.id, now - 10 * DAY_MS, ExecutionStatus.FAILED)
        // Una soppressione non e' uno scatto; uno vecchio di 40 giorni sta fuori da entrambe le finestre.
        recordFire(automation.id, now - 1 * DAY_MS + 5, ExecutionStatus.SUPPRESSED_COOLDOWN)
        recordFire(automation.id, now - 40 * DAY_MS, ExecutionStatus.SUCCEEDED)
        val viewModel = viewModel("stats", RoomAutomationStore(db.automationDao()), DetailAuditRecorder())

        awaitUntil("dettaglio con statistiche") { viewModel.state.value.detail?.stats != null }

        val stats = viewModel.state.value.detail?.stats
        assertNotNull(stats)
        assertEquals(RuleStatsWindow(fires = 2, succeeded = 2, failed = 0), stats.last7Days)
        assertEquals(RuleStatsWindow(fires = 3, succeeded = 2, failed = 1), stats.last30Days)
        assertEquals(30, stats.longWindowDays)
        assertTrue(!stats.isEmpty)
    }

    @Test
    fun `a rule that never fired reports an empty 30 day window`() = runTest {
        seedArmedAutomation("quiet")
        val viewModel = viewModel("quiet", RoomAutomationStore(db.automationDao()), DetailAuditRecorder())

        awaitUntil("dettaglio con statistiche") { viewModel.state.value.detail?.stats != null }

        val stats = viewModel.state.value.detail?.stats
        assertNotNull(stats)
        assertEquals(RuleStatsWindow(0, 0, 0), stats.last30Days)
        assertTrue(stats.isEmpty)
    }

    @Test
    fun `a new firing updates the stats while the detail is open`() = runTest {
        val automation = seedArmedAutomation("live")
        val viewModel = viewModel("live", RoomAutomationStore(db.automationDao()), DetailAuditRecorder())
        awaitUntil("dettaglio con statistiche") { viewModel.state.value.detail?.stats != null }
        assertTrue(viewModel.state.value.detail?.stats?.isEmpty == true)

        recordFire(automation.id, System.currentTimeMillis(), ExecutionStatus.SUCCEEDED)

        // Lo scatto e il suo esito arrivano con due scritture: si aspetta l'esito, non il solo scatto.
        awaitUntil("conteggio aggiornato") { viewModel.state.value.detail?.stats?.last30Days == RuleStatsWindow(1, 1, 0) }
        assertEquals(RuleStatsWindow(1, 1, 0), viewModel.state.value.detail?.stats?.last30Days)
    }

    @Test
    fun `a draft has no stats`() = runTest {
        val repository = RoomDraftRepository(db)
        assertIs<DraftWriteResult.Saved>(repository.create(newDraft("d-stats", "bozza")))
        val viewModel = viewModel("draft-d-stats", RoomAutomationStore(db.automationDao()), DetailAuditRecorder())

        awaitUntil("bozza caricata") { viewModel.state.value.detail != null }

        assertNull(viewModel.state.value.detail?.stats)
    }

    @Test
    fun `a short log retention narrows the long stats window instead of showing a false zero`() = runTest {
        val automation = seedArmedAutomation("short")
        val now = System.currentTimeMillis()
        recordFire(automation.id, now - 1 * DAY_MS, ExecutionStatus.SUCCEEDED)
        // Rimasta prima della prossima manutenzione: oltre la conservazione scelta, non va contata.
        recordFire(automation.id, now - 20 * DAY_MS, ExecutionStatus.SUCCEEDED)
        val retention = LogRetentionStore(ApplicationProvider.getApplicationContext<Context>())
        assertTrue(retention.setDays(7))
        val viewModel = viewModel(
            "short",
            RoomAutomationStore(db.automationDao()),
            DetailAuditRecorder(),
            retention = retention,
        )

        awaitUntil("dettaglio con statistiche") { viewModel.state.value.detail?.stats != null }

        val stats = viewModel.state.value.detail?.stats
        assertNotNull(stats)
        assertEquals(7, stats.longWindowDays)
        assertEquals(RuleStatsWindow(1, 1, 0), stats.last7Days)
        assertEquals(stats.last7Days, stats.last30Days)
    }

    // --- B35: niente gergo da sviluppatore --------------------------------------------------------

    @Test
    fun `generative estimate and run-now texts are plain language in both languages`() = runTest {
        val repository = RoomDraftRepository(db)
        assertIs<DraftWriteResult.Saved>(repository.create(generativeDraft("gen")))
        var system = RenderLanguage.EN
        val source = RenderLanguageSource { system }
        val viewModel = viewModel(
            "draft-gen",
            RoomAutomationStore(db.automationDao()),
            DetailAuditRecorder(),
            languageSource = source,
        )
        awaitUntil("english draft loaded") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        assertEquals(
            "Minimum cooldown 60 s between two AI replies",
            viewModel.state.value.detail?.estimatedLlmCallsPerDay,
        )
        viewModel.onRunNow()
        awaitUntil("english run-now message") { events.size == 1 }
        assertEquals("Run now is not available yet.", (events.last() as DetailEvent.Message).text)
        assertNoInternalPhaseNames(
            viewModel.state.value.detail?.estimatedLlmCallsPerDay.orEmpty(),
            viewModel.state.value.detail?.runNowBlockedReason.orEmpty(),
            (events.last() as DetailEvent.Message).text,
        )

        system = RenderLanguage.IT
        source.refresh()
        awaitUntil("italian estimate") {
            viewModel.state.value.detail?.estimatedLlmCallsPerDay == "Cooldown minimo 60 s tra due risposte AI"
        }
        viewModel.onRunNow()
        awaitUntil("italian run-now message") { events.size == 2 }
        assertEquals("Esegui ora non è ancora disponibile.", (events.last() as DetailEvent.Message).text)
        assertNoInternalPhaseNames(
            viewModel.state.value.detail?.estimatedLlmCallsPerDay.orEmpty(),
            viewModel.state.value.detail?.runNowBlockedReason.orEmpty(),
            (events.last() as DetailEvent.Message).text,
        )
    }

    private fun assertNoInternalPhaseNames(vararg texts: String) {
        val jargon = Regex("""\bP[0-9](-[A-Z])?\b|\bMVP\b|sideload""", RegexOption.IGNORE_CASE)
        texts.forEach { text ->
            assertNull(jargon.find(text)?.value, "gergo interno nel testo visibile: $text")
        }
    }

    // --- B34: lingua che segue il telefono --------------------------------------------------------

    @Test
    fun `the detail regenerates its texts when the language source goes from english to italian`() = runTest {
        seedArmedAutomation("lang")
        var system = RenderLanguage.EN
        val source = RenderLanguageSource { system }
        val viewModel = viewModel(
            "lang",
            RoomAutomationStore(db.automationDao()),
            DetailAuditRecorder(),
            languageSource = source,
        )
        awaitUntil("english detail loaded") { viewModel.state.value.detail != null }
        assertTrue(
            viewModel.state.value.detail!!.rule.triggerLine.startsWith("Every day"),
            viewModel.state.value.detail!!.rule.triggerLine,
        )

        system = RenderLanguage.IT
        source.refresh()

        awaitUntil("italian detail") {
            viewModel.state.value.detail?.rule?.triggerLine?.startsWith("Ogni giorno") == true
        }
        // Anche i messaggi prodotti al momento seguono la lingua corrente.
        val events = collectEvents(viewModel)
        viewModel.onRunNow()
        awaitUntil("messaggio italiano") { events.isNotEmpty() }
        assertTrue((events.single() as DetailEvent.Message).text.startsWith("Esegui ora"))
    }

    // --- S3: si arma la revisione mostrata ---------------------------------------------------------

    @Test
    fun `arm acts on the revision on screen while a newer slow review is pending`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val spy = SpyDraftRepository(RoomDraftRepository(db))
        val first = assertIs<DraftWriteResult.Saved>(spy.create(newDraft("s3", "prima"))).snapshot
        val viewModel = viewModel("s3", store, DetailAuditRecorder(), RenderLanguage.EN, drafts = spy)
        awaitUntil("revisione 1 mostrata") { viewModel.state.value.detail?.name == "prima" }
        val events = collectEvents(viewModel)

        // La revisione 2 arriva ma la sua review e' lenta: lo schermo mostra ancora la 1.
        spy.slowNextGet()
        val second = assertIs<DraftWriteResult.Saved>(
            spy.revise(first.id, first.revision, first.draft.copy(name = "seconda"), first.priority, 2_000),
        ).snapshot
        awaitUntil("review della revisione 2 avviata") { spy.slowGetReached.isCompleted }
        assertEquals("prima", viewModel.state.value.detail?.name)

        viewModel.onArm()
        awaitUntil("revisione cambiata") { events.any { it is DetailEvent.Message } }

        assertEquals(
            "The draft changed; review the new revision.",
            (events.single { it is DetailEvent.Message } as DetailEvent.Message).text,
        )
        assertTrue(spy.armCalls.isEmpty(), "la revisione non vista non deve mai essere armata")
        assertEquals(second.revision, spy.get(first.id)?.revision)

        // Review completata: ora lo schermo mostra la 2 e armare agisce su quella.
        spy.releaseSlowGet()
        awaitUntil("revisione 2 mostrata") { viewModel.state.value.detail?.name == "seconda" }
        viewModel.onArm()
        awaitUntil("chiusura dopo arm") { events.any { it is DetailEvent.Close } }
        assertEquals(listOf(second.revision), spy.armCalls.map { it.revision })
    }

    @Test
    fun `reject also acts on the revision on screen not on a newer unseen one`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val spy = SpyDraftRepository(RoomDraftRepository(db))
        val first = assertIs<DraftWriteResult.Saved>(spy.create(newDraft("s3r", "prima"))).snapshot
        val viewModel = viewModel("s3r", store, DetailAuditRecorder(), RenderLanguage.EN, drafts = spy)
        awaitUntil("revisione 1 mostrata") { viewModel.state.value.detail?.name == "prima" }
        val events = collectEvents(viewModel)

        spy.slowNextGet()
        assertIs<DraftWriteResult.Saved>(
            spy.revise(first.id, first.revision, first.draft.copy(name = "seconda"), first.priority, 2_000),
        )
        awaitUntil("review della revisione 2 avviata") { spy.slowGetReached.isCompleted }

        viewModel.onReject()
        awaitUntil("revisione cambiata") { events.any { it is DetailEvent.Message } }

        assertEquals(
            "The draft changed; reopen it before rejecting it.",
            (events.single { it is DetailEvent.Message } as DetailEvent.Message).text,
        )
        assertNotNull(spy.get(first.id), "la bozza piu' recente non va cancellata")
        spy.releaseSlowGet()
    }

    // --- B31: ciclo di vita visto dalla UI ----------------------------------------------------------

    @Test
    fun `the detail never shows rule unavailable while the draft turns into an armed rule`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val repository = RoomDraftRepository(db)
        assertIs<DraftWriteResult.Saved>(repository.create(newDraft("b31a", "da armare")))
        val registering = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        val registrar = ArmedAutomationRegistrar {
            registering.complete(Unit)
            releaseRegistration.await()
            true
        }
        val viewModel = viewModel(
            "draft-b31a", store, DetailAuditRecorder(), RenderLanguage.EN,
            drafts = repository, registrar = registrar,
        )
        awaitUntil("bozza mostrata") { viewModel.state.value.detail != null }
        val states = mutableListOf<DetailViewState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect(states::add)
        }
        val events = collectEvents(viewModel)

        viewModel.onArm()
        awaitUntil("registrazione avviata") { registering.isCompleted }
        // L'arm e' gia' committato: la bozza non c'e' piu' e la regola armata ha un altro id.
        awaitUntil("regola armata nel DB") { runBlocking { store.get(AutomationId("b31a")) } != null }
        settle()

        assertEquals(false, viewModel.state.value.missing, "nessun «regola non disponibile» durante l'arm")
        assertNotNull(viewModel.state.value.detail, "l'ultimo stato valido resta sullo schermo")
        assertEquals(true, viewModel.state.value.detail?.actionInProgress)

        releaseRegistration.complete(Unit)
        awaitUntil("chiusura") { events.any { it is DetailEvent.Close } }
        settle()
        assertTrue(states.none { it.missing }, "in nessun momento lo stato e' stato «non disponibile»")
    }

    @Test
    fun `the detail still reports a rule that really disappeared when no action is running`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val automation = seedArmedAutomation("b31m")
        val viewModel = viewModel("b31m", store, DetailAuditRecorder(), RenderLanguage.EN)
        awaitUntil("regola mostrata") { viewModel.state.value.detail != null }

        store.delete(automation.id)

        awaitUntil("regola non disponibile") { viewModel.state.value.missing }
        assertNull(viewModel.state.value.detail)
    }

    @Test
    fun `leaving the screen during arm does not cancel the arm or its registration`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val spy = SpyDraftRepository(RoomDraftRepository(db))
        assertIs<DraftWriteResult.Saved>(spy.create(newDraft("b31b", "uscita")))
        val registered = java.util.concurrent.CopyOnWriteArrayList<Automation>()
        val registrar = ArmedAutomationRegistrar { automation ->
            registered += automation
            true
        }
        val viewModel = viewModel(
            "draft-b31b", store, DetailAuditRecorder(), RenderLanguage.EN,
            drafts = spy, registrar = registrar,
        )
        awaitUntil("bozza mostrata") { viewModel.state.value.detail != null }

        spy.gateNextArm()
        viewModel.onArm()
        awaitUntil("arm avviato") { spy.armGateReached.isCompleted }
        // L'utente torna indietro: la schermata si chiude e viewModelScope viene cancellato.
        viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        spy.releaseArmGate()

        awaitUntil("registrazione dell'arm") { registered.isNotEmpty() }
        assertEquals(AutomationId("b31b"), registered.single().id)
        assertEquals(AutomationStatus.ARMED, store.get(AutomationId("b31b"))?.status)
    }

    @Test
    fun `re-enabling a one shot rule whose time has passed explains what to do`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        seedDisabledOneShotInPast("b31c")
        val viewModel = viewModel(
            "b31c", store, DetailAuditRecorder(), RenderLanguage.EN,
            enablementAudit = RoomAuditSink(db.auditDao()),
        )
        awaitUntil("regola disattivata mostrata") { viewModel.state.value.detail?.status == StatusBadge.DISABLED }
        val events = collectEvents(viewModel)

        viewModel.onSetEnabled(true)
        awaitUntil("messaggio") { events.any { it is DetailEvent.Message } }

        val expected = "This rule's time has already passed: edit it to choose a new time."
        assertEquals(expected, (events.single { it is DetailEvent.Message } as DetailEvent.Message).text)
        // Lo stesso motivo compare nelle ultime esecuzioni, al posto di un codice.
        awaitUntil("riga nelle ultime esecuzioni") {
            viewModel.state.value.detail?.recentRuns?.any { it.summary == expected } == true
        }
    }

    @Test
    fun `the one shot in the past message is italian when the phone is italian`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        seedDisabledOneShotInPast("b31i")
        val viewModel = viewModel("b31i", store, DetailAuditRecorder(), RenderLanguage.IT)
        awaitUntil("regola disattivata mostrata") { viewModel.state.value.detail?.status == StatusBadge.DISABLED }
        val events = collectEvents(viewModel)

        viewModel.onSetEnabled(true)
        awaitUntil("messaggio") { events.any { it is DetailEvent.Message } }

        assertEquals(
            "L'orario di questa regola è già passato: modificala per scegliere un nuovo orario.",
            (events.single { it is DetailEvent.Message } as DetailEvent.Message).text,
        )
    }

    @Test
    fun `a failed re-enable for another reason keeps the generic message`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val rule = seedArmedAutomation("b31g")
        store.disable(rule.id)
        val viewModel = viewModel(
            "b31g", store, DetailAuditRecorder(), RenderLanguage.EN,
            scheduler = EnableFailingScheduler(db, "b31g", "scheduling_failed"),
        )
        awaitUntil("regola disattivata mostrata") { viewModel.state.value.detail?.status == StatusBadge.DISABLED }
        val events = collectEvents(viewModel)

        viewModel.onSetEnabled(true)
        awaitUntil("messaggio") { events.any { it is DetailEvent.Message } }

        assertEquals(
            "Scheduling failed: the rule remains disabled.",
            (events.single { it is DetailEvent.Message } as DetailEvent.Message).text,
        )
    }

    @Test
    fun `an older one shot failure is not blamed for a new failed attempt`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val rule = seedArmedAutomation("b31o")
        store.disable(rule.id)
        // Un fallimento di molto tempo fa: non riguarda questo tentativo.
        db.auditDao().insert(
            AuditEntity(
                automationId = "b31o",
                kind = AuditKind.ENABLE_FAILED,
                atMillis = 1_000,
                detail = ONE_SHOT_IN_PAST,
            ),
        )
        val viewModel = viewModel(
            "b31o", store, DetailAuditRecorder(), RenderLanguage.EN,
            scheduler = EnableFailingScheduler(db, "b31o", detail = null),
        )
        awaitUntil("regola disattivata mostrata") { viewModel.state.value.detail?.status == StatusBadge.DISABLED }
        val events = collectEvents(viewModel)

        viewModel.onSetEnabled(true)
        awaitUntil("messaggio") { events.any { it is DetailEvent.Message } }

        assertEquals(
            "Scheduling failed: the rule remains disabled.",
            (events.single { it is DetailEvent.Message } as DetailEvent.Message).text,
        )
    }

    // --- B26: doppio tocco ---------------------------------------------------------------------------

    @Test
    fun `two consecutive arm taps run one approval and emit one close`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val spy = SpyDraftRepository(RoomDraftRepository(db))
        assertIs<DraftWriteResult.Saved>(spy.create(newDraft("b26", "doppio")))
        val viewModel = viewModel("b26", store, DetailAuditRecorder(), RenderLanguage.EN, drafts = spy)
        awaitUntil("bozza mostrata") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onArm()
        viewModel.onArm()
        awaitUntil("chiusura") { events.any { it is DetailEvent.Close } }
        settle()

        assertEquals(1, spy.armCalls.size, "una sola chiamata approvals.arm")
        assertEquals(1, events.count { it is DetailEvent.Close }, "un solo Close")
        assertEquals(0, events.count { it is DetailEvent.Message })
    }

    @Test
    fun `the footer is disabled while arm runs and is enabled again when arm fails without closing`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val spy = SpyDraftRepository(RoomDraftRepository(db))
        assertIs<DraftWriteResult.Saved>(spy.create(newDraft("b26b", "in corso")))
        val viewModel = viewModel("b26b", store, DetailAuditRecorder(), RenderLanguage.EN, drafts = spy)
        awaitUntil("bozza mostrata") { viewModel.state.value.detail != null }
        assertEquals(false, viewModel.state.value.detail?.actionInProgress)
        val events = collectEvents(viewModel)

        spy.holdNextArm(DraftArmResult.IntegrityFailure)
        viewModel.onArm()
        awaitUntil("arm avviato") { spy.armHeld.isCompleted }
        assertEquals(true, viewModel.state.value.detail?.actionInProgress)
        viewModel.onArm()
        viewModel.onReject()
        spy.releaseHeldArm()

        awaitUntil("messaggio di errore") { events.any { it is DetailEvent.Message } }
        awaitUntil("footer di nuovo attivo") { viewModel.state.value.detail?.actionInProgress == false }
        settle()
        assertEquals(1, spy.armCalls.size, "il secondo arm e il reject durante l'arm sono ignorati")
        assertEquals(0, events.count { it is DetailEvent.Close })
        assertNotNull(spy.get(DraftId("draft-b26b")), "il reject ignorato non ha cancellato la bozza")

        // Dopo un fallimento senza chiusura l'utente puo' riprovare.
        viewModel.onArm()
        awaitUntil("secondo tentativo") { spy.armCalls.size == 2 }
    }

    @Test
    fun `a double tap on delete deletes once and closes once`() = runTest {
        seedArmedAutomation("b26d")
        val store = RoomAutomationStore(db.automationDao())
        val audit = DetailAuditRecorder()
        val viewModel = viewModel("b26d", store, audit)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onDelete()
        viewModel.onDelete()
        awaitUntil("chiusura") { events.any { it is DetailEvent.Close } }
        settle()

        assertEquals(1, events.count { it is DetailEvent.Close })
        assertEquals(1, audit.events.count { it.kind == AuditKind.RULE_DELETED })
    }

    // --- 0.3.6: l'eliminazione pulisce anche i sensori -----------------------------------------------

    @Test
    fun `deleting a rule also reconciles the sensors after the rule is gone`() = runTest {
        val automation = seedArmedAutomation("e3a")
        val store = RoomAutomationStore(db.automationDao())
        val reconciledWhilePresent = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val sensor = SensorTriggerRuntime {
            reconciledWhilePresent += store.get(automation.id) != null
            SensorReconcileReport(emptyList(), emptySet())
        }
        val viewModel = viewModel("e3a", store, DetailAuditRecorder(), RenderLanguage.EN, sensor = sensor)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onDelete()
        awaitUntil("chiusura") { events.any { it is DetailEvent.Close } }

        assertEquals(listOf(false), reconciledWhilePresent.toList(), "una chiamata, a regola gia' rimossa")
        assertEquals("Rule deleted.", (events.single { it is DetailEvent.Close } as DetailEvent.Close).message)
    }

    @Test
    fun `a failing sensor reconcile on delete reports the deferred cleanup and still deletes`() = runTest {
        val automation = seedArmedAutomation("e3b")
        val store = RoomAutomationStore(db.automationDao())
        val sensor = SensorTriggerRuntime { error("sensor backend unavailable") }
        val viewModel = viewModel("e3b", store, DetailAuditRecorder(), RenderLanguage.EN, sensor = sensor)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onDelete()
        awaitUntil("chiusura") { events.any { it is DetailEvent.Close } }

        assertNull(store.get(automation.id), "la regola e' stata eliminata comunque")
        assertEquals(
            "Rule deleted; runtime cleanup deferred.",
            (events.single { it is DetailEvent.Close } as DetailEvent.Close).message,
        )
    }

    @Test
    fun `the state flag survives a recomputed state while an action is running`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val spy = SpyDraftRepository(RoomDraftRepository(db))
        val first = assertIs<DraftWriteResult.Saved>(spy.create(newDraft("b26c", "ricalcolo"))).snapshot
        val viewModel = viewModel("b26c", store, DetailAuditRecorder(), RenderLanguage.EN, drafts = spy)
        awaitUntil("bozza mostrata") { viewModel.state.value.detail != null }
        collectEvents(viewModel)

        spy.holdNextArm(DraftArmResult.IntegrityFailure)
        viewModel.onArm()
        awaitUntil("arm avviato") { spy.armHeld.isCompleted }
        // Una nuova emissione dal DB ricalcola lo stato: il flag non deve sparire.
        assertIs<DraftWriteResult.Saved>(
            spy.revise(first.id, first.revision, first.draft.copy(name = "ricalcolata"), first.priority, 2_000),
        )
        awaitUntil("stato ricalcolato") { viewModel.state.value.detail?.name == "ricalcolata" }
        assertEquals(true, viewModel.state.value.detail?.actionInProgress)
        spy.releaseHeldArm()
        awaitUntil("footer di nuovo attivo") { viewModel.state.value.detail?.actionInProgress == false }
    }

    // --- D5: la regola armata resta il suo dettaglio ---------------------------------------------------

    @Test
    fun `an armed rule with a pending edit opens as the armed rule and the draft opens by its own id`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val repository = RoomDraftRepository(db)
        val first = assertIs<DraftWriteResult.Saved>(repository.create(newDraft("d5", "originale"))).snapshot
        val original = assertIs<DraftArmResult.Armed>(
            repository.arm(first.id, first.revision, first.fingerprint),
        ).automation
        assertIs<DraftWriteResult.Saved>(
            repository.create(
                newDraft("d5", "modificata").copy(
                    id = DraftId("edit-d5"),
                    expectedAutomationFingerprint = original.approvalFingerprint,
                ),
            ),
        )

        val byRule = viewModel("d5", store, DetailAuditRecorder(), RenderLanguage.EN)
        awaitUntil("regola mostrata") { byRule.state.value.detail != null }
        assertEquals(StatusBadge.ARMED, byRule.state.value.detail?.status)
        assertEquals("originale", byRule.state.value.detail?.name)
        runBlocking { byRule.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }

        val byDraft = viewModel("edit-d5", store, DetailAuditRecorder(), RenderLanguage.EN)
        awaitUntil("bozza mostrata") { byDraft.state.value.detail != null }
        assertEquals(StatusBadge.PENDING_APPROVAL, byDraft.state.value.detail?.status)
        assertEquals("modificata", byDraft.state.value.detail?.name)
    }

    // --- B13: la modifica dal dettaglio conserva le variabili -----------------------------------------

    @Test
    fun `ask edit of a p4 rule carries its variables in the base draft`() = runTest {
        val unsigned = Automation(
            id = AutomationId("p4"),
            name = "p4",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "\${n}")),
            vars = listOf(dev.argus.engine.model.VarBinding.RandomInt("n", 6)),
            enabled = true,
            schemaVersion = dev.argus.engine.model.AUTOMATION_SCHEMA_VERSION_P4,
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
        val viewModel = viewModel("p4", RoomAutomationStore(db.automationDao()), DetailAuditRecorder())
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onAskEdit()
        awaitUntil("chat aperta") { events.any { it is DetailEvent.OpenChat } }

        val open = events.single { it is DetailEvent.OpenChat } as DetailEvent.OpenChat
        assertEquals(signed.vars, open.baseDraft?.vars)
        assertEquals(signed.approvalFingerprint, open.automationFingerprint)
    }

    // --- 0.3.6 (F2): «Perché non è scattata?» ---------------------------------------------------------

    @Test
    fun `a saved rule offers the diagnosis and onDiagnose fills it`() = runTest {
        seedArmedAutomation("f2a")
        val viewModel = viewModel(
            "f2a", RoomAutomationStore(db.automationDao()), DetailAuditRecorder(), RenderLanguage.EN,
        )
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        assertEquals(true, viewModel.state.value.detail?.diagnosisAvailable)
        assertNull(viewModel.state.value.detail?.diagnosis, "non si calcola finche non viene chiesta")

        viewModel.onDiagnose()
        awaitUntil("diagnosi") { viewModel.state.value.detail?.diagnosis != null }

        val diagnosis = assertNotNull(viewModel.state.value.detail?.diagnosis)
        assertEquals(
            listOf(
                "The rule is active",
                "Required permissions and services: all ready",
                "No alarm is registered for this rule",
                "It has never run yet",
            ),
            diagnosis.rows.map { it.text },
        )
        assertTrue(diagnosis.checkedAtLabel.startsWith("Checked at "))
    }

    @Test
    fun `the diagnosis reads the block causes the persisted alarm and the geofence registration`() = runTest {
        val rule = seedArmedAutomation("f2b")
        db.scheduledTimeAlarmDao().upsert(
            ScheduledTimeAlarmEntity(
                automationId = "f2b",
                approvalFingerprint = requireNotNull(rule.approvalFingerprint).value,
                eventAtMillis = System.currentTimeMillis() + 3_600_000,
                wakeAtMillis = System.currentTimeMillis() + 3_600_000,
                requestedPrecision = "FLEXIBLE",
                scheduledMode = "INEXACT",
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        // Notifiche non concesse: la regola le mostra, quindi manca un requisito.
        val viewModel = viewModel(
            "f2b", RoomAutomationStore(db.automationDao()), DetailAuditRecorder(), RenderLanguage.EN,
            probe = probeFor(capabilityState(notifications = false)),
        )
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }

        viewModel.onDiagnose()
        awaitUntil("diagnosi") { viewModel.state.value.detail?.diagnosis != null }

        val rows = assertNotNull(viewModel.state.value.detail?.diagnosis).rows
        assertEquals("Argus is not allowed to show notifications", rows[1].text)
        assertEquals(dev.argus.ui.model.DiagnosisFix.OPEN_SYSTEM, rows[1].fix)
        assertTrue(rows[2].text.startsWith("Next alarm: "), rows[2].text)
        assertEquals(
            "Flexible time: to save battery Android may move it by up to an hour; " +
                "for a precise time, ask for the exact time in chat",
            rows[3].text,
        )
    }

    @Test
    fun `the diagnosis of a geofence rule reads the registration from the store`() = runTest {
        val unsigned = Automation(
            id = AutomationId("f2g"),
            name = "casa",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Geofence(
                lat = 45.0,
                lng = 9.0,
                radiusM = 150.0,
                transition = dev.argus.engine.model.Transition.EXIT,
            ),
            actions = listOf(Action.ShowNotification("Argus", "test")),
            enabled = true,
        )
        val rule = persist(unsigned)
        val geofenceState = MemoryGeofenceStateStore()
        val fingerprint = requireNotNull(rule.approvalFingerprint)
        geofenceState.prepare(GeofenceRegistration(rule.id, fingerprint, 45.0, 9.0, 150f))
        geofenceState.activate(rule.id, fingerprint)
        val viewModel = viewModel(
            "f2g", RoomAutomationStore(db.automationDao()), DetailAuditRecorder(), RenderLanguage.EN,
            geofenceState = geofenceState,
        )
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }

        viewModel.onDiagnose()
        awaitUntil("diagnosi") { viewModel.state.value.detail?.diagnosis != null }

        assertTrue(
            assertNotNull(viewModel.state.value.detail?.diagnosis).rows.any {
                it.text == "Position relative to the area: unknown (updates on your next move)"
            },
        )
    }

    @Test
    fun `a language change rebuilds the diagnosis text`() = runTest {
        seedArmedAutomation("f2l")
        var system = RenderLanguage.EN
        val source = RenderLanguageSource { system }
        val viewModel = viewModel(
            "f2l", RoomAutomationStore(db.automationDao()), DetailAuditRecorder(),
            languageSource = source,
        )
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        viewModel.onDiagnose()
        awaitUntil("diagnosi inglese") {
            viewModel.state.value.detail?.diagnosis?.rows?.firstOrNull()?.text == "The rule is active"
        }

        system = RenderLanguage.IT
        source.refresh()

        awaitUntil("diagnosi italiana") {
            viewModel.state.value.detail?.diagnosis?.rows?.firstOrNull()?.text == "La regola è attiva"
        }
        val diagnosis = assertNotNull(viewModel.state.value.detail?.diagnosis)
        assertTrue(diagnosis.checkedAtLabel.startsWith("Controllato alle "))
        assertEquals("Non è ancora mai scattata", diagnosis.rows.last().text)
    }

    @Test
    fun `a draft has no diagnosis and onDiagnose does nothing`() = runTest {
        val spy = SpyDraftRepository(RoomDraftRepository(db))
        assertIs<DraftWriteResult.Saved>(spy.create(newDraft("f2d", "bozza")))
        val viewModel = viewModel(
            "f2d", RoomAutomationStore(db.automationDao()), DetailAuditRecorder(), RenderLanguage.EN,
            drafts = spy,
        )
        awaitUntil("bozza mostrata") { viewModel.state.value.detail != null }

        viewModel.onDiagnose()
        settle()

        assertEquals(StatusBadge.PENDING_APPROVAL, viewModel.state.value.detail?.status)
        assertEquals(false, viewModel.state.value.detail?.diagnosisAvailable)
        assertNull(viewModel.state.value.detail?.diagnosis)
    }

    @Test
    fun `the old diagnosis is dropped when the rule changes revision`() = runTest {
        val first = seedArmedAutomation("f2r")
        val viewModel = viewModel(
            "f2r", RoomAutomationStore(db.automationDao()), DetailAuditRecorder(), RenderLanguage.EN,
        )
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        viewModel.onDiagnose()
        awaitUntil("diagnosi") { viewModel.state.value.detail?.diagnosis != null }

        // Una nuova revisione approvata della stessa regola: diversa impronta.
        persist(first.copy(actions = listOf(Action.ShowNotification("Argus", "altro testo")), approvalFingerprint = null))

        awaitUntil("diagnosi azzerata") { viewModel.state.value.detail?.diagnosis == null }
        assertEquals(true, viewModel.state.value.detail?.diagnosisAvailable)
        // L'utente puo' ricontrollare: la nuova diagnosi vale per la nuova revisione.
        viewModel.onDiagnose()
        awaitUntil("nuova diagnosi") { viewModel.state.value.detail?.diagnosis != null }
    }

    @Test
    fun `a failing check reports a message and leaves the diagnosis empty`() = runTest {
        seedArmedAutomation("f2e")
        val broken = AndroidCapabilityProbe(
            AndroidCapabilityStateSource { error("state unavailable") },
            RoomContactWhitelistStore(db.contactWhitelistDao()),
            GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true) },
        )
        val viewModel = viewModel(
            "f2e", RoomAutomationStore(db.automationDao()), DetailAuditRecorder(), RenderLanguage.EN,
            probe = broken,
        )
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onDiagnose()
        awaitUntil("messaggio") { events.any { it is DetailEvent.Message } }

        assertEquals(
            "Unable to run the check.",
            (events.single { it is DetailEvent.Message } as DetailEvent.Message).text,
        )
        assertNull(viewModel.state.value.detail?.diagnosis)
    }

    // --- helpers -------------------------------------------------------------

    private fun capabilityState(
        shizuku: ShizukuGatewayStatus = ShizukuGatewayStatus.AUTHORIZED,
        notifications: Boolean = true,
    ) = AndroidCapabilityState(
        deviceModel = "test",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = shizuku,
        shizukuPermissionGranted = shizuku == ShizukuGatewayStatus.AUTHORIZED,
        notificationsGranted = notifications,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = true,
        batteryOptimizationExempt = true,
    )

    private fun probeFor(state: AndroidCapabilityState) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        RoomContactWhitelistStore(db.contactWhitelistDao()),
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true) },
    )

    /** Firma e salva una regola come farebbe l'arm: serve a simulare una nuova revisione approvata. */
    private suspend fun persist(unsigned: Automation): Automation {
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


    /** Eventi del ViewModel raccolti in una lista (nessun replay: va chiamato prima dell'azione). */
    private fun kotlinx.coroutines.test.TestScope.collectEvents(
        viewModel: AutomationDetailViewModel,
    ): MutableList<DetailEvent> {
        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect(events::add)
        }
        return events
    }

    /** Tempo reale per lasciar arrivare un eventuale evento duplicato prima di contare. */
    private suspend fun settle() = withContext(Dispatchers.Default) { delay(400) }

    private fun generativeDraft(id: String) = NewDraft(
        id = DraftId("draft-$id"),
        automationId = AutomationId(id),
        draft = AutomationDraft(
            name = "generativa $id",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(
                Action.InvokeLlm(
                    goal = "say hello",
                    contextSources = emptyList(),
                    allowedTools = emptyList(),
                    replyTargetSender = false,
                    deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
                    notificationTitle = "Argus",
                ),
            ),
        ),
        atMillis = 1_000,
    )

    private fun newDraft(id: String, name: String) = NewDraft(
        id = DraftId("draft-$id"),
        automationId = AutomationId(id),
        draft = AutomationDraft(
            name = name,
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "test")),
        ),
        atMillis = 1_000,
    )

    private fun viewModel(
        routeId: String,
        store: RoomAutomationStore,
        audit: AuditSink,
        language: RenderLanguage = RenderLanguage.IT,
        drafts: DraftRepository = RoomDraftRepository(db),
        languageSource: RenderLanguageSource = RenderLanguageSource { language },
        registrar: ArmedAutomationRegistrar = ArmedAutomationRegistrar { true },
        scheduler: TimeAlarmRuntime = NoopTimeAlarmRuntime(),
        sensor: SensorTriggerRuntime = NoopSensorTriggerRuntime,
        enablementAudit: AuditSink = NoopAuditSink,
        probe: AndroidCapabilityProbe = probeFor(capabilityState()),
        geofenceState: GeofenceStateStore = MemoryGeofenceStateStore(),
        retention: LogRetentionStore = LogRetentionStore(ApplicationProvider.getApplicationContext<Context>()),
        manual: ManualEntryService = ManualHarness(store).service,
        appCatalog: InstalledAppCatalog = EmptyInstalledAppCatalog,
    ): AutomationDetailViewModel {
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
        val snapshots = FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = emptySet(),
                // Le capacita' richieste dalla bozza di prova sono tutte disponibili: l'arm puo' riuscire.
                availableCapabilities = CapabilityRequirements.derive(
                    Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
                    listOf(Action.ShowNotification("Argus", "test")),
                ),
                whitelistedConversationIds = emptySet(),
            )
        }
        val approvals = ApprovalFlow(
            drafts = drafts,
            approvals = ApprovalService(
                drafts,
                DraftValidator(emptySet()),
                whitelist,
            ),
            automations = store,
            capabilities = snapshots,
            location = CurrentLocationProvider { null },
            registrar = registrar,
        )
        // Scope applicativo di prova: sopravvive al viewModelScope come in produzione.
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        applicationScopes += applicationScope
        return AutomationDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("id" to routeId)),
            store = store,
            drafts = drafts,
            approvals = approvals,
            enablement = AutomationEnablementCoordinator(store, scheduler, audit = enablementAudit),
            scheduler = scheduler,
            database = db,
            whitelist = whitelist,
            connectivity = NoopConnectivityTriggerRuntime,
            geofence = NoopGeofenceTriggerRuntime,
            sensor = sensor,
            probe = probe,
            geofenceState = geofenceState,
            audit = audit,
            languageSource = languageSource,
            retention = retention,
            manual = manual,
            applicationScope = applicationScope,
            appCatalog = appCatalog,
        ).also { lastViewModel = it }
    }

    // --- 0.7.0: nome delle app e «Cambia app» --------------------------------------------------------

    private class FakeCatalog(
        private val apps: List<InstalledAppEntry>,
        private val installed: Set<String> = apps.map { it.packageName }.toSet(),
    ) : InstalledAppCatalog {
        override suspend fun launchableApps() = apps
        override suspend fun isInstalled(packageName: String) = packageName in installed
        override suspend fun missing(packages: Collection<String>) = packages.filterNot { it in installed }.toSet()
    }

    private val appCatalog = FakeCatalog(
        listOf(
            InstalledAppEntry("Chrome", "com.android.chrome"),
            InstalledAppEntry("Firefox", "org.mozilla.firefox"),
        ),
    )

    private fun appsDraft(id: String) = NewDraft(
        id = DraftId("draft-$id"),
        automationId = AutomationId(id),
        draft = AutomationDraft(
            name = "Apri app $id",
            trigger = Trigger.AppEvent("com.youtube", AppEventKind.OPENED),
            actions = listOf(Action.LaunchApp("com.android.chrome")),
            rationale = "Quando apri YouTube apro Chrome.",
        ),
        atMillis = 1_000,
    )

    @Test
    fun `the detail names the apps of the rule and flags the one that is not installed`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        assertIs<DraftWriteResult.Saved>(RoomDraftRepository(db).create(appsDraft("ap1")))
        val viewModel = viewModel("draft-ap1", store, DetailAuditRecorder(), RenderLanguage.EN, appCatalog = appCatalog)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail?.appInfo?.isNotEmpty() == true }

        val detail = assertNotNull(viewModel.state.value.detail)
        assertEquals(AppInfoUi(name = "Chrome", installed = true), detail.appInfo["com.android.chrome"])
        assertEquals(AppInfoUi(name = null, installed = false), detail.appInfo["com.youtube"])
        assertTrue(detail.canChangeApp)
        assertEquals(dev.argus.ui.model.AppRef(0, "com.youtube"), detail.rule.triggerAppRef)
    }

    @Test
    fun `change app on a draft makes a new revision with a new fingerprint and drops the old prose`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val repository = RoomDraftRepository(db)
        val first = assertIs<DraftWriteResult.Saved>(repository.create(appsDraft("ap2"))).snapshot
        val viewModel = viewModel("draft-ap2", store, DetailAuditRecorder(), RenderLanguage.EN, appCatalog = appCatalog)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onChangeApp(slot = 1, packageName = "org.mozilla.firefox")
        awaitUntil("nuova revisione") { events.any { it is DetailEvent.Message } }

        val second = assertNotNull(repository.get(first.id))
        assertEquals(first.revision + 1, second.revision)
        assertTrue(second.fingerprint != first.fingerprint)
        assertEquals(Action.LaunchApp("org.mozilla.firefox"), second.draft.actions.single())
        assertEquals(first.draft.trigger, second.draft.trigger)
        assertEquals("", second.draft.rationale)
        assertTrue((events.single() as DetailEvent.Message).text.contains("new revision"))
    }

    @Test
    fun `change app refuses a package that is not a package name and never touches the draft`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val repository = RoomDraftRepository(db)
        val first = assertIs<DraftWriteResult.Saved>(repository.create(appsDraft("ap3"))).snapshot
        val viewModel = viewModel("draft-ap3", store, DetailAuditRecorder(), RenderLanguage.EN, appCatalog = appCatalog)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onChangeApp(slot = 1, packageName = "not a package")
        awaitUntil("rifiuto") { events.any { it is DetailEvent.Message } }

        assertEquals(first.revision, repository.get(first.id)?.revision)
        assertEquals("Unable to change the app.", (events.single() as DetailEvent.Message).text)
    }

    @Test
    fun `choosing the app already in use changes nothing`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val repository = RoomDraftRepository(db)
        val first = assertIs<DraftWriteResult.Saved>(repository.create(appsDraft("ap4"))).snapshot
        val viewModel = viewModel("draft-ap4", store, DetailAuditRecorder(), RenderLanguage.EN, appCatalog = appCatalog)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onChangeApp(slot = 1, packageName = "com.android.chrome")
        awaitUntil("messaggio") { events.any { it is DetailEvent.Message } }

        assertEquals(first.revision, repository.get(first.id)?.revision)
        assertEquals("The rule already uses that app.", (events.single() as DetailEvent.Message).text)
    }

    @Test
    fun `change app on an armed rule never edits it: it opens an edit draft for approval`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        val armed = persist(
            Automation(
                id = AutomationId("armed-ap"),
                name = "Apri Chrome",
                createdBy = CreatedBy.USER,
                status = AutomationStatus.ARMED,
                trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
                actions = listOf(Action.LaunchApp("com.android.chrome")),
                enabled = true,
            ),
        )
        val repository = RoomDraftRepository(db)
        val viewModel = viewModel("armed-ap", store, DetailAuditRecorder(), RenderLanguage.EN, appCatalog = appCatalog)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        assertTrue(assertNotNull(viewModel.state.value.detail).canChangeApp)
        val events = collectEvents(viewModel)

        viewModel.onChangeApp(slot = 0, packageName = "org.mozilla.firefox")
        awaitUntil("bozza di modifica aperta") { events.any { it is DetailEvent.OpenDetail } }

        // La regola armata è identica, stesso stato e stessa impronta.
        val still = assertNotNull(store.get(armed.id))
        assertEquals(AutomationStatus.ARMED, still.status)
        assertEquals(armed.approvalFingerprint, still.approvalFingerprint)
        assertEquals(armed.actions, still.actions)
        // La bozza di modifica porta il nuovo package e punta alla versione approvata.
        val opened = events.filterIsInstance<DetailEvent.OpenDetail>().single()
        val draft = assertNotNull(repository.get(DraftId(opened.id)))
        assertEquals(armed.id, draft.automationId)
        assertEquals(armed.approvalFingerprint, draft.baseAutomationFingerprint)
        assertEquals(Action.LaunchApp("org.mozilla.firefox"), draft.draft.actions.single())
    }

    @Test
    fun `a second change app on an armed rule opens the edit that is already waiting`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        persist(
            Automation(
                id = AutomationId("armed-ap2"),
                name = "Apri Chrome",
                createdBy = CreatedBy.USER,
                status = AutomationStatus.ARMED,
                trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
                actions = listOf(Action.LaunchApp("com.android.chrome")),
                enabled = true,
            ),
        )
        val viewModel = viewModel("armed-ap2", store, DetailAuditRecorder(), RenderLanguage.EN, appCatalog = appCatalog)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onChangeApp(slot = 0, packageName = "org.mozilla.firefox")
        awaitUntil("prima bozza") { events.any { it is DetailEvent.OpenDetail } }
        awaitUntil("footer di nuovo attivo") { viewModel.state.value.detail?.actionInProgress == false }
        viewModel.onChangeApp(slot = 0, packageName = "com.example.other")
        awaitUntil("seconda apertura") { events.count { it is DetailEvent.OpenDetail } == 2 }

        val ids = events.filterIsInstance<DetailEvent.OpenDetail>().map { it.id }
        assertEquals(ids[0], ids[1])
        assertEquals(1, RoomDraftRepository(db).observeAll().first().count { it.automationId.value == "armed-ap2" })
    }

    @Test
    fun `opening change app loads the installed apps once, in the catalog order`() = runTest {
        val store = RoomAutomationStore(db.automationDao())
        assertIs<DraftWriteResult.Saved>(RoomDraftRepository(db).create(appsDraft("ap6")))
        val viewModel = viewModel("draft-ap6", store, DetailAuditRecorder(), RenderLanguage.EN, appCatalog = appCatalog)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        assertNull(viewModel.state.value.detail?.appChoices)

        viewModel.onLoadAppChoices()
        awaitUntil("elenco caricato") { viewModel.state.value.detail?.appChoices != null }

        assertEquals(
            listOf("com.android.chrome", "org.mozilla.firefox"),
            viewModel.state.value.detail?.appChoices?.map { it.packageName },
        )
    }

    // --- F8/F21: avvio manuale e pausa nel Dettaglio ---------------------------------------------

    private suspend fun seedArmedManual(id: String, name: String = "Apri il garage"): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = name,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Manual,
            actions = listOf(Action.SetFlashlight(true)),
            enabled = true,
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

    @Test
    fun `an armed manual rule offers run now and the manual entry points`() = runTest {
        seedArmedManual("m1")
        val store = RoomAutomationStore(db.automationDao())
        val harness = ManualHarness(store)
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.EN, manual = harness.service)
        awaitUntil("manual detail loaded") { viewModel.state.value.detail != null }

        val detail = requireNotNull(viewModel.state.value.detail)
        assertTrue(detail.canRunNow)
        assertNull(detail.runNowBlockedReason)
        assertEquals(ManualEntryUi(canPinShortcut = true, isTileRule = false, paused = false), detail.manual)
        assertEquals("manual", detail.rule.triggerIconKey)
    }

    @Test
    fun `a rule that is not manual keeps run now disabled and has no manual entry points`() = runTest {
        seedArmedAutomation("a1")
        val store = RoomAutomationStore(db.automationDao())
        val viewModel = viewModel("a1", store, DetailAuditRecorder(), RenderLanguage.EN)
        awaitUntil("detail loaded") { viewModel.state.value.detail != null }

        val detail = requireNotNull(viewModel.state.value.detail)
        assertEquals(false, detail.canRunNow)
        assertNull(detail.manual)
    }

    @Test
    fun `a disabled manual rule cannot be run from the detail`() = runTest {
        val rule = seedArmedManual("m1")
        val store = RoomAutomationStore(db.automationDao())
        store.disable(rule.id)
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.EN)
        awaitUntil("disabled manual detail loaded") { viewModel.state.value.detail != null }

        val detail = requireNotNull(viewModel.state.value.detail)
        assertEquals(false, detail.canRunNow)
        assertNull(detail.manual)
    }

    @Test
    fun `run now on a manual rule hands the event to the engine and says it started`() = runTest {
        val rule = seedArmedManual("m1")
        val store = RoomAutomationStore(db.automationDao())
        val harness = ManualHarness(store)
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.EN, manual = harness.service)
        awaitUntil("manual detail loaded") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onRunNow()
        awaitUntil("run-now feedback") { events.isNotEmpty() }

        assertEquals("Rule started.", (events.single() as DetailEvent.Message).text)
        val delivered = harness.dispatcher.envelopes.single().event
        assertIs<dev.argus.engine.runtime.TriggerEvent.ManualFired>(delivered)
        assertEquals(rule.approvalFingerprint, delivered.approvalFingerprint)
    }

    @Test
    fun `run now while paused tells the user the run was skipped`() = runTest {
        seedArmedManual("m1")
        val store = RoomAutomationStore(db.automationDao())
        val harness = ManualHarness(store, paused = true)
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.IT, manual = harness.service)
        awaitUntil("manual detail loaded") { viewModel.state.value.detail != null }
        assertEquals(true, viewModel.state.value.detail?.manual?.paused)
        val events = collectEvents(viewModel)

        viewModel.onRunNow()
        awaitUntil("run-now feedback") { events.isNotEmpty() }

        assertTrue((events.single() as DetailEvent.Message).text.contains("in pausa"))
    }

    @Test
    fun `the pause shown in the detail follows the switch`() = runTest {
        seedArmedManual("m1")
        val store = RoomAutomationStore(db.automationDao())
        val harness = ManualHarness(store)
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.EN, manual = harness.service)
        awaitUntil("manual detail loaded") { viewModel.state.value.detail != null }
        assertEquals(false, viewModel.state.value.detail?.manual?.paused)

        harness.pause.setPaused(true)
        awaitUntil("pause shown") { viewModel.state.value.detail?.manual?.paused == true }
        harness.pause.setPaused(false)
        awaitUntil("pause cleared") { viewModel.state.value.detail?.manual?.paused == false }
    }

    @Test
    fun `adding to the Home screen asks the launcher with the rule token`() = runTest {
        seedArmedManual("m1", name = "Apri il garage")
        val store = RoomAutomationStore(db.automationDao())
        val harness = ManualHarness(store)
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.EN, manual = harness.service)
        awaitUntil("manual detail loaded") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onAddToHome()
        awaitUntil("shortcut feedback") { events.isNotEmpty() }

        val entry = harness.pinner.requested.single()
        assertEquals("m1", entry.id)
        assertEquals("Apri il garage", entry.name)
        assertTrue(harness.tokens.isValid(AutomationId("m1"), entry.token))
        assertTrue((events.single() as DetailEvent.Message).text.startsWith("Shortcut requested"))
    }

    @Test
    fun `a launcher without pin support hides the shortcut button and explains if asked`() = runTest {
        seedArmedManual("m1")
        val store = RoomAutomationStore(db.automationDao())
        val harness = ManualHarness(store, pinSupported = false)
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.EN, manual = harness.service)
        awaitUntil("manual detail loaded") { viewModel.state.value.detail != null }
        assertEquals(false, viewModel.state.value.detail?.manual?.canPinShortcut)
        val events = collectEvents(viewModel)

        viewModel.onAddToHome()
        awaitUntil("shortcut feedback") { events.isNotEmpty() }

        assertTrue(harness.pinner.requested.isEmpty())
        assertTrue((events.single() as DetailEvent.Message).text.contains("does not support shortcuts"))
    }

    @Test
    fun `choosing the tile rule marks this rule as the tile one`() = runTest {
        seedArmedManual("m1")
        val store = RoomAutomationStore(db.automationDao())
        val harness = ManualHarness(store)
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.EN, manual = harness.service)
        awaitUntil("manual detail loaded") { viewModel.state.value.detail != null }
        assertEquals(false, viewModel.state.value.detail?.manual?.isTileRule)
        val events = collectEvents(viewModel)

        viewModel.onUseInTile()

        assertEquals("m1", harness.tile.ruleId.value)
        awaitUntil("tile rule shown") { viewModel.state.value.detail?.manual?.isTileRule == true }
        assertTrue((events.single() as DetailEvent.Message).text.contains("Apri il garage"))
    }

    @Test
    fun `deleting a manual rule forgets its token and its tile choice`() = runTest {
        seedArmedManual("m1")
        val store = RoomAutomationStore(db.automationDao())
        val harness = ManualHarness(store)
        val token = harness.tokens.tokenFor(AutomationId("m1"))
        harness.tile.select("m1")
        val viewModel = viewModel("m1", store, DetailAuditRecorder(), RenderLanguage.EN, manual = harness.service)
        awaitUntil("manual detail loaded") { viewModel.state.value.detail != null }
        val events = collectEvents(viewModel)

        viewModel.onDelete()
        awaitUntil("delete done") { events.any { it is DetailEvent.Close } }

        assertEquals(false, harness.tokens.isValid(AutomationId("m1"), token))
        assertNull(harness.tile.ruleId.value)
    }

    /** Seed confinato al test: il codice applicativo scrive solo via DraftRepository. */
    private suspend fun seedArmedAutomation(id: String): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = "auto-$id",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "test")),
            enabled = true,
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

    /** Un'esecuzione conclusa con [status], reclamata a [atMillis], nel journal reale. */
    private suspend fun recordFire(id: AutomationId, atMillis: Long, status: ExecutionStatus) {
        val executionId = ExecutionId("exec-${executionSequence++}")
        RoomAutomationStore(db.automationDao()).claimFire(
            FireClaimRequest(id, TriggerEventId("event-${executionSequence++}"), executionId, atMillis, 0),
        )
        RoomExecutionJournal(db.executionJournalDao()).finish(
            ExecutionCompletion(executionId, status, atMillis, 0, 0, 0),
        )
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

private const val DAY_MS = 24L * 60 * 60 * 1_000

private class DetailAuditRecorder : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) { events += e }
}

private class NoopTimeAlarmRuntime : TimeAlarmRuntime {
    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = AlarmDeliveryResult.Ignored

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport =
        ReconcileReport(emptyList(), emptyList(), emptyList(), emptyList())
}

/**
 * Decora un [DraftRepository] reale: conta gli arm e permette di rallentare la prossima `get`
 * (review lenta) o di trattenere il prossimo `arm` per osservare il footer disabilitato.
 */
private class SpyDraftRepository(private val delegate: DraftRepository) : DraftRepository by delegate {
    data class ArmCall(val id: DraftId, val revision: Long)

    val armCalls = java.util.concurrent.CopyOnWriteArrayList<ArmCall>()

    private val slowGet = java.util.concurrent.atomic.AtomicBoolean(false)
    val slowGetReached = CompletableDeferred<Unit>()
    private val slowGetRelease = CompletableDeferred<Unit>()

    private var heldArmResult: DraftArmResult? = null
    private val holdNextArm = java.util.concurrent.atomic.AtomicBoolean(false)
    val armHeld = CompletableDeferred<Unit>()
    private val armRelease = CompletableDeferred<Unit>()

    fun slowNextGet() { slowGet.set(true) }
    fun releaseSlowGet() { slowGetRelease.complete(Unit) }

    fun holdNextArm(result: DraftArmResult) {
        heldArmResult = result
        holdNextArm.set(true)
    }

    fun releaseHeldArm() { armRelease.complete(Unit) }

    /** Ferma il prossimo `arm` PRIMA di eseguirlo davvero: al rilascio prosegue sul repository reale. */
    private val gateArm = java.util.concurrent.atomic.AtomicBoolean(false)
    val armGateReached = CompletableDeferred<Unit>()
    private val armGateRelease = CompletableDeferred<Unit>()

    fun gateNextArm() { gateArm.set(true) }
    fun releaseArmGate() { armGateRelease.complete(Unit) }

    override suspend fun get(id: DraftId): PendingDraft? {
        if (slowGet.compareAndSet(true, false)) {
            slowGetReached.complete(Unit)
            slowGetRelease.await()
        }
        return delegate.get(id)
    }

    override suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): DraftArmResult {
        armCalls += ArmCall(id, expectedRevision)
        if (gateArm.compareAndSet(true, false)) {
            armGateReached.complete(Unit)
            armGateRelease.await()
        }
        if (holdNextArm.compareAndSet(true, false)) {
            armHeld.complete(Unit)
            armRelease.await()
            return requireNotNull(heldArmResult)
        }
        return delegate.arm(id, expectedRevision, expectedFingerprint)
    }
}

/**
 * Finto lato runtime di una riattivazione che fallisce: il reconcile segnala la regola come
 * fallita (il coordinator reale risponde SchedulingFailed e la rimette disattivata) e, se
 * [detail] c'e', scrive il `ENABLE_FAILED` col codice che il blocco runtime produrra' davvero.
 */
internal class EnableFailingScheduler(
    private val db: ArgusDatabase,
    private val automationId: String,
    private val detail: String?,
) : TimeAlarmRuntime {
    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = AlarmDeliveryResult.Ignored

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport {
        detail?.let {
            db.auditDao().insert(
                AuditEntity(
                    automationId = automationId,
                    kind = AuditKind.ENABLE_FAILED,
                    atMillis = System.currentTimeMillis(),
                    detail = it,
                ),
            )
        }
        return ReconcileReport(emptyList(), emptyList(), emptyList(), listOf(AutomationId(automationId)))
    }
}
