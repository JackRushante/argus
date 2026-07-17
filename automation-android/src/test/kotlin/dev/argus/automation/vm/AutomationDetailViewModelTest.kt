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
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime
import dev.argus.automation.geofence.NoopGeofenceTriggerRuntime
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.entities.AutomationEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.DraftValidator
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var lastViewModel: AutomationDetailViewModel

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
        backgroundScope.launch { viewModel.events.collect(events::add) }

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

    // --- helpers -------------------------------------------------------------

    private fun viewModel(
        routeId: String,
        store: RoomAutomationStore,
        audit: AuditSink,
    ): AutomationDetailViewModel {
        val drafts = RoomDraftRepository(db)
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
        val snapshots = FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = emptySet(),
                availableCapabilities = emptySet(),
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
            registrar = ArmedAutomationRegistrar { true },
        )
        val scheduler = NoopTimeAlarmRuntime()
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
            audit = audit,
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
