package dev.argus.automation.vm

import android.content.Context
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
import dev.argus.automation.LogRetentionStore
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ReconcileReport
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime
import dev.argus.automation.geofence.MemoryGeofenceStateStore
import dev.argus.automation.geofence.NoopGeofenceTriggerRuntime
import dev.argus.automation.manual.ManualHarness
import dev.argus.automation.sensor.NoopSensorTriggerRuntime
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.DndMode
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
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.AutomationDetailState
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.RuleChangeKind
import dev.argus.ui.presentation.RuleSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

/**
 * 0.7.2, «Cosa cambia»: la bozza che sostituisce una regola armata mostra nel dettaglio di approvazione
 * le righe cambiate rispetto alla regola armata. Percorso reale: Room in memoria, approvazione, ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class DetailEditChangesTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val scopes = mutableListOf<CoroutineScope>()

    private val base = AutomationDraft(
        name = "Notte",
        trigger = Trigger.Time(cron = "0 23 * * *", tz = "UTC"),
        actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
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
    fun `an edit of an armed rule shows what changes, old and new`() = runTest {
        val armed = armed("notte", base)
        val edit = editDraft(armed, base.copy(actions = listOf(Action.SetDnd(DndMode.TOTAL))))

        val detail = detailOf(edit.id.value)

        val change = assertNotNull(detail.changes).single()
        assertEquals(RuleSection.ACTIONS, change.section)
        assertEquals(RuleChangeKind.CHANGED, change.kind)
        assertEquals("Turn on Do Not Disturb (priority)", change.old?.text)
        assertEquals(true, change.new?.text?.startsWith("Turn on Do Not Disturb"))
        // La regola intera resta resa per com'è nella bozza.
        assertEquals(1, detail.rule.actions.size)
    }

    @Test
    fun `an edit that only renames the rule says that nothing executable changes`() = runTest {
        val armed = armed("notte", base)
        val edit = editDraft(armed, base.copy(name = "Notte tranquilla", rationale = "nuovo nome"))

        val detail = detailOf(edit.id.value)

        assertEquals(emptyList(), detail.changes)
    }

    @Test
    fun `a new draft has nothing to compare with`() = runTest {
        val saved = assertIs<DraftWriteResult.Saved>(
            repository.create(NewDraft(DraftId("nuova"), AutomationId("nuova"), base, atMillis = 1_000)),
        ).snapshot

        val detail = detailOf(saved.id.value)

        assertNull(detail.changes)
    }

    @Test
    fun `the armed rule itself shows no changes card`() = runTest {
        val armed = armed("notte", base)
        editDraft(armed, base.copy(actions = listOf(Action.SetDnd(DndMode.OFF))))

        val detail = detailOf(armed.id.value)

        assertNull(detail.changes)
    }

    // --- aiuti ---------------------------------------------------------------------------------

    private suspend fun detailOf(routeId: String): AutomationDetailState {
        val viewModel = detailViewModel(routeId)
        awaitUntil("dettaglio caricato") { viewModel.state.value.detail != null }
        return assertNotNull(viewModel.state.value.detail)
    }

    private suspend fun armed(id: String, draft: AutomationDraft): Automation {
        val saved = assertIs<DraftWriteResult.Saved>(
            repository.create(NewDraft(DraftId("draft-$id"), AutomationId(id), draft, atMillis = 1_000)),
        ).snapshot
        return assertIs<DraftArmResult.Armed>(repository.arm(saved.id, saved.revision, saved.fingerprint)).automation
    }

    private suspend fun editDraft(automation: Automation, draft: AutomationDraft): PendingDraft =
        assertIs<DraftWriteResult.Saved>(
            repository.create(
                NewDraft(
                    id = DraftId("edit-${automation.id.value}"),
                    automationId = automation.id,
                    draft = draft,
                    atMillis = 2_000,
                    expectedAutomationFingerprint = automation.approvalFingerprint,
                ),
            ),
        ).snapshot

    private fun approvalFlow(): ApprovalFlow {
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
        return ApprovalFlow(
            drafts = repository,
            approvals = ApprovalService(repository, DraftValidator(emptySet()), whitelist),
            automations = store,
            capabilities = FirePolicySnapshotProvider {
                FirePolicySnapshot(
                    knownTools = emptySet(),
                    availableCapabilities = CapabilityRequirements.derive(base.trigger, base.actions),
                    whitelistedConversationIds = emptySet(),
                )
            },
            location = CurrentLocationProvider { null },
            registrar = ArmedAutomationRegistrar { true },
        )
    }

    private fun detailViewModel(routeId: String): AutomationDetailViewModel {
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
        val scheduler = EditNoopTimeAlarmRuntime()
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
            manual = ManualHarness(store).service,
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

private class EditNoopTimeAlarmRuntime : TimeAlarmRuntime {
    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = AlarmDeliveryResult.Ignored

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport =
        ReconcileReport(emptyList(), emptyList(), emptyList(), emptyList())
}
