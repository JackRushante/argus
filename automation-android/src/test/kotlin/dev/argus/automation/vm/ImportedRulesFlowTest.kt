package dev.argus.automation.vm

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.AlarmDeliveryResult
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AndroidCapabilityState
import dev.argus.automation.AndroidCapabilityStateSource
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.ArmedAutomationRegistrar
import dev.argus.automation.AutomationEnablementCoordinator
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.GenerativeReadiness
import dev.argus.automation.GenerativeRuntimeReadiness
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ReconcileReport
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime
import dev.argus.automation.geofence.MemoryGeofenceStateStore
import dev.argus.automation.geofence.NoopGeofenceTriggerRuntime
import dev.argus.automation.sensor.NoopSensorTriggerRuntime
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.backup.BackupDocumentIo
import dev.argus.data.backup.BackupRule
import dev.argus.data.backup.ImportOutcome
import dev.argus.data.backup.PersonalDataStripper
import dev.argus.data.backup.RulesBackupCodec
import dev.argus.data.backup.RulesBackupService
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.NoopAuditSink
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.AutomationListState
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import android.content.Context
import dev.argus.automation.LogRetentionStore

/**
 * Backup delle regole (0.4.0, F1): le bozze create dall'import devono comparire nella lista come
 * «da approvare», aprirsi nel dettaglio di approvazione e armarsi SOLO da lì, con gli stessi controlli
 * delle bozze della chat. Percorso reale: servizio di import, Room in memoria, ViewModel di lista e dettaglio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ImportedRulesFlowTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val scopes = mutableListOf<CoroutineScope>()

    private val file: Uri = Uri.parse("content://argus.test/rules.json")

    private val okRule = AutomationDraft(
        name = "Importata buona",
        trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
        actions = listOf(Action.ShowNotification("Argus", "test")),
    )
    private val replyRule = AutomationDraft(
        name = "Importata con risposta",
        trigger = Trigger.Notification(
            pkg = "com.whatsapp", conversationId = "wa::contatto::1", sender = "Moglie", isGroup = false,
        ),
        actions = listOf(
            Action.InvokeLlm(
                goal = "rispondi",
                contextSources = listOf("notification"),
                allowedTools = listOf("whatsapp_reply"),
                replyTargetSender = true,
            ),
        ),
        cooldownMs = 60_000,
    )
    private val geofenceRule = PersonalDataStripper.strip(
        AutomationDraft(
            name = "Importata geofence",
            trigger = Trigger.Geofence(
                lat = 45.0, lng = 9.0, radiusM = 200.0, transition = Transition.ENTER,
            ),
            actions = listOf(Action.ShowNotification("Argus", "arrivato")),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        repository = RoomDraftRepository(db)
    }

    @After
    fun tearDown() {
        runBlocking {
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
        }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `imported drafts show in the list as waiting for approval, next to the armed rules, and arm nothing`() = runTest {
        val existing = seedArmedRule("esistente")
        importAll()
        val latest = collectList(listViewModel())

        awaitUntil("quattro righe") { latest().rows.size == 4 }

        val imported = latest().rows.filter { it.name.startsWith("Importata") }
        assertEquals(3, imported.size)
        imported.forEach {
            assertEquals(StatusBadge.PENDING_APPROVAL, it.status, it.name)
            assertEquals(false, it.enabled, "una bozza non è mai attiva: ${it.name}")
        }
        assertEquals(3, latest().pendingCount)
        // L'id della riga apre la bozza nel dettaglio.
        val draftIds = repository.observeAll().first().map { it.id.value }.toSet()
        assertEquals(draftIds, imported.map { it.id }.toSet())
        // La regola che c'era resta armata e attiva.
        val row = latest().rows.single { it.id == existing.id.value }
        assertEquals(StatusBadge.ARMED, row.status)
        assertEquals(true, row.enabled)
        assertEquals(listOf(existing.id), store.armed().map { it.id })
    }

    @Test
    fun `the pending filter of the list shows the imported drafts`() = runTest {
        seedArmedRule("esistente")
        importAll()
        val viewModel = listViewModel()
        val latest = collectList(viewModel)
        awaitUntil("quattro righe") { latest().rows.size == 4 }

        viewModel.onFilter(StatusFilter.PENDING)
        awaitUntil("filtro applicato") { latest().filter == StatusFilter.PENDING }

        assertEquals(3, latest().rows.size)
        assertTrue(latest().rows.all { it.status == StatusBadge.PENDING_APPROVAL })
    }

    @Test
    fun `an imported draft opens in the approval detail and arms from there`() = runTest {
        importAll()
        val draft = importedDraft("Importata buona")
        val viewModel = detailViewModel(draft.id.value)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }

        val detail = assertNotNull(viewModel.state.value.detail)
        assertEquals(StatusBadge.PENDING_APPROVAL, detail.status)
        assertEquals("Importata buona", detail.name)
        assertTrue(detail.canArm, "problemi: ${detail.warnings}")
        assertEquals(null, store.get(draft.automationId), "prima dell'approvazione non esiste nessuna regola")

        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect(events::add)
        }
        viewModel.onArm()
        awaitUntil("chiusura dopo l'arm") { events.any { it is DetailEvent.Close } }

        val armed = assertNotNull(store.get(draft.automationId))
        assertEquals(AutomationStatus.ARMED, armed.status)
        assertEquals(CreatedBy.IMPORT, armed.createdBy)
        assertEquals(null, repository.get(draft.id), "la bozza è stata consumata dall'approvazione")
    }

    @Test
    fun `an imported draft that cites a contact outside the whitelist opens flagged and cannot be armed`() = runTest {
        importAll()
        val draft = importedDraft("Importata con risposta")
        val viewModel = detailViewModel(draft.id.value)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }

        val detail = assertNotNull(viewModel.state.value.detail)
        assertEquals(false, detail.canArm)
        assertNotNull(detail.armBlockedReason)
        assertTrue(
            detail.warnings.any { it.code == "target_not_whitelisted" },
            "avvisi: ${detail.warnings.map { it.code }}",
        )

        viewModel.onArm()
        // Il tentativo non arma nulla: nessuna regola, la bozza resta.
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(null, store.get(draft.automationId))
        assertNotNull(repository.get(draft.id))
        assertEquals(emptyList(), RoomContactWhitelistStore(db.contactWhitelistDao()).observeAll().first())
    }

    @Test
    fun `a stripped geofence opens in the detail saying the position is taken when it is armed`() = runTest {
        importAll()
        val draft = importedDraft("Importata geofence")
        val viewModel = detailViewModel(draft.id.value)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }

        val detail = assertNotNull(viewModel.state.value.detail)
        assertEquals("Location: current position at activation time", detail.geofencePreviewLabel)
        assertTrue(detail.canArm, "problemi: ${detail.warnings}")
    }

    // --- aiuti ---------------------------------------------------------------------------------

    /** Scrive un file di backup con le tre regole e lo importa con il servizio reale. */
    private suspend fun importAll() {
        val text = RulesBackupCodec.encode(
            rules = listOf(okRule, replyRule, geofenceRule).map { BackupRule(enabled = true, draft = it) },
            exportedAt = "2026-10-26T10:00:00Z",
            appVersion = "0.4.0",
        )
        val documents = object : BackupDocumentIo {
            override suspend fun read(source: Uri, maxBytes: Int): ByteArray? = text.toByteArray()
            override suspend fun write(destination: Uri, bytes: ByteArray) = Unit
        }
        val service = RulesBackupService(
            automations = store,
            drafts = repository,
            documents = documents,
            appVersion = { "0.4.0" },
        )
        assertIs<ImportOutcome.Done>(service.import(file))
    }

    private suspend fun importedDraft(name: String): PendingDraft =
        repository.observeAll().first().single { it.draft.name == name }

    private suspend fun seedArmedRule(id: String) = assertIs<DraftArmResult.Armed>(
        run {
            val saved = assertIs<DraftWriteResult.Saved>(
                repository.create(
                    NewDraft(
                        id = DraftId("draft-$id"),
                        automationId = AutomationId(id),
                        draft = AutomationDraft(
                            name = "regola $id",
                            trigger = Trigger.Time(cron = "0 9 * * *", tz = "UTC"),
                            actions = listOf(Action.ShowNotification("Argus", "test")),
                        ),
                        atMillis = 1_000,
                    ),
                ),
            ).snapshot
            repository.arm(saved.id, saved.revision, saved.fingerprint)
        },
    ).automation

    /** Capacità disponibili: tutte quelle che le tre regole importate richiedono. */
    private fun snapshots() = FirePolicySnapshotProvider {
        FirePolicySnapshot(
            knownTools = setOf("whatsapp_reply"),
            availableCapabilities = listOf(okRule, replyRule, geofenceRule).flatMapTo(linkedSetOf()) {
                CapabilityRequirements.derive(it.trigger, it.actions, it.conditions)
            },
            whitelistedConversationIds = emptySet(),
        )
    }

    private fun approvalFlow(): ApprovalFlow {
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
        return ApprovalFlow(
            drafts = repository,
            approvals = ApprovalService(repository, DraftValidator(setOf("whatsapp_reply")), whitelist),
            automations = store,
            capabilities = snapshots(),
            location = CurrentLocationProvider { null },
            registrar = ArmedAutomationRegistrar { true },
        )
    }

    private fun listViewModel(): AutomationListViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return AutomationListViewModel(
            store = store,
            drafts = repository,
            approvals = approvalFlow(),
            enablement = AutomationEnablementCoordinator(store, BackupNoopTimeAlarmRuntime()),
            shizuku = ShizukuGateway(context),
            database = db,
            whitelist = RoomContactWhitelistStore(db.contactWhitelistDao()),
            savedStateHandle = SavedStateHandle(),
            context = context,
            languageSource = RenderLanguageSource { RenderLanguage.EN },
        ).also { viewModels += it }
    }

    private fun detailViewModel(routeId: String): AutomationDetailViewModel {
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
        val scheduler = BackupNoopTimeAlarmRuntime()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += applicationScope
        return AutomationDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("id" to routeId)),
            store = store,
            drafts = repository,
            approvals = approvalFlow(),
            enablement = AutomationEnablementCoordinator(store, scheduler),
            scheduler = scheduler,
            database = db,
            whitelist = whitelist,
            connectivity = NoopConnectivityTriggerRuntime,
            geofence = NoopGeofenceTriggerRuntime,
            sensor = NoopSensorTriggerRuntime,
            probe = AndroidCapabilityProbe(
                AndroidCapabilityStateSource { capabilityState() },
                whitelist,
                GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true) },
            ),
            geofenceState = MemoryGeofenceStateStore(),
            audit = NoopAuditSink,
            languageSource = RenderLanguageSource { RenderLanguage.EN },
            applicationScope = applicationScope,
            retention = LogRetentionStore(ApplicationProvider.getApplicationContext<Context>()),
        ).also { viewModels += it }
    }

    private fun capabilityState() = AndroidCapabilityState(
        deviceModel = "test",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.AUTHORIZED,
        shizukuPermissionGranted = true,
        notificationsGranted = true,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = true,
        batteryOptimizationExempt = true,
    )

    private fun TestScope.collectList(viewModel: AutomationListViewModel): () -> AutomationListState {
        var latest = viewModel.state.value
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { latest = it }
        }
        return { latest }
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

private class BackupNoopTimeAlarmRuntime : TimeAlarmRuntime {
    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = AlarmDeliveryResult.Ignored

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport =
        ReconcileReport(emptyList(), emptyList(), emptyList(), emptyList())
}
