package dev.argus.automation.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.ArmedAutomationRegistrar
import dev.argus.automation.AutomationEnablementCoordinator
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.AlarmDeliveryResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ReconcileReport
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime
import dev.argus.automation.geofence.NoopGeofenceTriggerRuntime
import dev.argus.automation.sensor.NoopSensorTriggerRuntime
import dev.argus.automation.sensor.SensorReconcileReport
import dev.argus.automation.sensor.SensorTriggerRuntime
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.entities.AuditEntity
import dev.argus.data.entities.AutomationEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
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
        val rule = seedArmedAutomation("b31c")
        store.disable(rule.id)
        val viewModel = viewModel(
            "b31c", store, DetailAuditRecorder(), RenderLanguage.EN,
            scheduler = EnableFailingScheduler(db, "b31c", ONE_SHOT_IN_PAST),
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
        val rule = seedArmedAutomation("b31i")
        store.disable(rule.id)
        val viewModel = viewModel(
            "b31i", store, DetailAuditRecorder(), RenderLanguage.IT,
            scheduler = EnableFailingScheduler(db, "b31i", ONE_SHOT_IN_PAST),
        )
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

    // --- helpers -------------------------------------------------------------

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
            enablement = AutomationEnablementCoordinator(store, scheduler),
            scheduler = scheduler,
            database = db,
            whitelist = whitelist,
            connectivity = NoopConnectivityTriggerRuntime,
            geofence = NoopGeofenceTriggerRuntime,
            sensor = sensor,
            audit = audit,
            languageSource = languageSource,
            applicationScope = applicationScope,
        ).also { lastViewModel = it }
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
