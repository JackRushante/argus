package dev.argus.automation.vm

import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.ArmedAutomationRegistrar
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.brain.BridgeConfigurationStore
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.SCHEMA_VERSION
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.ApprovalWhitelistProvider
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftDeleteResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import dev.argus.ui.model.ChatItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a cancelled request cannot clear or overwrite the next request`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain)

        viewModel.prefillEdit("prima", automationId = "edit-obsoleto")
        viewModel.onSend()
        runCurrent()
        val first = brain.removeFirst()
        assertEquals("prima", first.prompt)
        assertTrue(viewModel.state.value.sending)

        viewModel.onCancelPending()
        viewModel.onInputChange("seconda")
        viewModel.onSend()
        runCurrent()
        val second = brain.removeFirst()

        assertEquals("seconda", second.prompt)
        assertTrue(viewModel.state.value.sending)
        assertFalse(first.response.isCompleted)

        second.response.complete(
            CompileResult(
                reply = "Regola pronta",
                draft = AutomationDraft(
                    name = "DND sera",
                    trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                    actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
                ),
                metaError = null,
            ),
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.sending)
        assertEquals(true, state.brainReachable)
        assertTrue(state.items.any { it is ChatItem.UserMessage && it.text == "prima" })
        assertTrue(state.items.any { it is ChatItem.UserMessage && it.text == "seconda" })
        assertTrue(
            state.items.any {
                it is ChatItem.DraftCard &&
                    it.rule.triggerLine.contains("23:00") &&
                    it.rule.actions.any { action -> action.label.contains("Non disturbare") }
            },
        )
    }

    @Test
    fun `edit prompt carries the complete current draft without persisting it`() {
        val base = AutomationDraft(
            name = "DND sera",
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.TOTAL)),
            cooldownMs = 60_000,
        )

        val prompt = composeEditPrompt("spostala alle 22", base)

        assertTrue(prompt.contains("ARGUS_CURRENT_RULE_JSON"))
        assertTrue(prompt.contains("DND sera"))
        assertTrue(prompt.contains("TOTAL"))
        assertTrue(prompt.contains("60000"))
        assertTrue(prompt.endsWith("spostala alle 22"))
    }

    private fun chatViewModel(brain: Brain): ChatViewModel {
        val automations = ViewModelAutomationStore()
        val drafts = ViewModelDraftRepository()
        val policy = FirePolicySnapshot(
            knownTools = AndroidCapabilityProbe.KNOWN_TOOLS,
            availableCapabilities = setOf(
                CapabilityIds.TRIGGER_TIME,
                ActionCapabilities.SET_DND,
            ),
            whitelistedConversationIds = emptySet(),
        )
        val approvals = ApprovalService(
            drafts,
            DraftValidator(policy.knownTools),
            ApprovalWhitelistProvider { emptySet() },
        )
        var nextId = 0
        val flow = ApprovalFlow(
            drafts = drafts,
            approvals = approvals,
            automations = automations,
            capabilities = FirePolicySnapshotProvider { policy },
            location = CurrentLocationProvider { null },
            registrar = ArmedAutomationRegistrar { true },
            nowMillis = { 1_000L },
            newDraftId = { DraftId("draft-${++nextId}") },
            newAutomationId = { AutomationId("automation-$nextId") },
        )
        val configuration = ViewModelBridgeConfiguration()
        return ChatViewModel(
            brain = brain,
            configuredBridge = ConfiguredBridgeBrain(
                configuration = configuration,
                privacyAccepted = { true },
                elapsedRealtimeMillis = { 0L },
            ),
            capabilityProbe = object : CapabilityProbe {
                override suspend fun probe(currentState: DeviceState): CapabilityManifest =
                    TEST_MANIFEST
            },
            deviceState = DeviceStateSnapshotProvider { DeviceState() },
            approvalFlow = flow,
            drafts = drafts,
        )
    }

    private companion object {
        val TEST_MANIFEST = CapabilityManifest(
            deviceModel = "test",
            androidVersion = 16,
            androidApi = 36,
            shizukuAvailable = false,
            grantedPermissions = emptyList(),
            availableTools = emptyList(),
            unavailableTools = emptyMap(),
            whitelistedContacts = emptyList(),
        )
    }
}

private class QueuedBrain : Brain {
    data class Request(
        val prompt: String,
        val response: CompletableDeferred<CompileResult>,
    )

    private val requests = ArrayDeque<Request>()

    override suspend fun compile(
        nl: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        val request = Request(nl, CompletableDeferred())
        requests.addLast(request)
        return request.response.await()
    }

    fun removeFirst(): Request = requests.removeFirst()
}

private class ViewModelBridgeConfiguration : BridgeConfigurationStore {
    override fun baseUrl(): String = "https://bridge.invalid"
    override suspend fun bearerToken(): String? = null
    override suspend fun saveConfiguration(baseUrl: String, bearerToken: String?): Boolean = false
    override suspend fun saveBaseUrl(value: String): Boolean = false
    override suspend fun saveBearerToken(value: String): Boolean = false
    override suspend fun clearBearerToken(): Boolean = true
}

private class ViewModelDraftRepository : DraftRepository {
    private val values = MutableStateFlow<Map<DraftId, PendingDraft>>(emptyMap())

    override suspend fun create(newDraft: NewDraft): DraftWriteResult {
        if (newDraft.id in values.value) {
            return DraftWriteResult.Conflict(values.value[newDraft.id]?.revision)
        }
        val unsigned = PendingDraft(
            id = newDraft.id,
            automationId = newDraft.automationId,
            revision = 1,
            fingerprint = ApprovalFingerprint("0".repeat(64)),
            draft = newDraft.draft,
            createdBy = newDraft.createdBy,
            priority = newDraft.priority,
            schemaVersion = SCHEMA_VERSION,
            createdAtMillis = newDraft.atMillis,
            updatedAtMillis = newDraft.atMillis,
            baseAutomationFingerprint = newDraft.expectedAutomationFingerprint,
        )
        val snapshot = unsigned.copy(
            fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()),
        )
        values.value += snapshot.id to snapshot
        return DraftWriteResult.Saved(snapshot)
    }

    override suspend fun revise(
        id: DraftId,
        expectedRevision: Long,
        draft: AutomationDraft,
        priority: Int,
        atMillis: Long,
    ): DraftWriteResult = DraftWriteResult.Conflict(values.value[id]?.revision)

    override suspend fun get(id: DraftId): PendingDraft? = values.value[id]
    override fun observeAll(): Flow<List<PendingDraft>> = values.map { it.values.toList() }
    override suspend fun delete(id: DraftId, expectedRevision: Long): DraftDeleteResult =
        DraftDeleteResult.Missing
    override suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): DraftArmResult = DraftArmResult.Missing
}

private class ViewModelAutomationStore : AutomationStore {
    private val values = MutableStateFlow<Map<AutomationId, Automation>>(emptyMap())

    override suspend fun get(id: AutomationId): Automation? = values.value[id]
    override suspend fun all(): List<Automation> = values.value.values.toList()
    override fun observeAll(): Flow<List<Automation>> = values.map { it.values.toList() }
    override suspend fun armed(): List<Automation> = values.value.values.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }
    override suspend fun delete(id: AutomationId) {
        values.value -= id
    }
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun enableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        FireClaimResult.NotEligible
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null
}
