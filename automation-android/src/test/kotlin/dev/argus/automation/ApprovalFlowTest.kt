package dev.argus.automation

import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.AutomationStore
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApprovalFlowTest {
    @Test
    fun `submit persists review and exposes conflicts without blocking arm`() = runTest {
        val existing = signed(
            Automation(
                id = AutomationId("existing"),
                name = "Wi-Fi on",
                createdBy = CreatedBy.USER,
                status = AutomationStatus.ARMED,
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                actions = listOf(Action.SetWifi(true)),
            ),
        )
        val fixture = fixture(
            available = setOf(CapabilityIds.TRIGGER_TIME, ActionCapabilities.SET_WIFI),
            initial = listOf(existing),
        )
        val result = fixture.flow.submit(
            CompileResult(
                reply = "Ecco la regola",
                draft = AutomationDraft(
                    name = "Wi-Fi off",
                    trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                    actions = listOf(Action.SetWifi(false)),
                ),
                metaError = null,
            ),
        )

        val ready = assertIs<DraftSubmissionResult.Ready>(result)
        assertTrue(ready.review.canArm)
        assertEquals(1, ready.review.conflicts.size)
        assertTrue("wifi" in ready.review.conflicts.single().message.lowercase())
        assertEquals("Ecco la regola", ready.reply)
    }

    @Test
    fun `structurally unavailable capability blocks review while transient outage only warns`() = runTest {
        val draft = AutomationDraft(
            name = "DND",
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val structural = fixture(available = setOf(CapabilityIds.TRIGGER_TIME))
        val blocked = assertIs<DraftSubmissionResult.Ready>(
            structural.flow.submit(CompileResult("ok", draft, null)),
        ).review
        assertFalse(blocked.canArm)
        assertTrue(blocked.draft.issues.any { it.code == "capability_unavailable" })

        val transient = fixture(
            available = setOf(CapabilityIds.TRIGGER_TIME),
            transient = setOf(ActionCapabilities.SET_DND),
        )
        val warning = assertIs<DraftSubmissionResult.Ready>(
            transient.flow.submit(CompileResult("ok", draft, null)),
        ).review
        assertTrue(warning.canArm)
        assertTrue(warning.draft.issues.any { it.code == "capability_temporarily_unavailable" })
    }

    @Test
    fun `geofence coordinates resolve at arm and trigger registrar sees signed automation`() = runTest {
        var registered: Automation? = null
        val fixture = fixture(
            available = setOf(
                CapabilityIds.TRIGGER_GEOFENCE,
                ActionCapabilities.SET_WIFI,
            ),
            location = DeviceLocation(45.4642, 9.1900),
            registrar = ArmedAutomationRegistrar { registered = it; true },
        )
        val ready = assertIs<DraftSubmissionResult.Ready>(
            fixture.flow.submit(
                CompileResult(
                    "ok",
                    AutomationDraft(
                        name = "Esco da casa",
                        trigger = Trigger.Geofence(
                            radiusM = 150.0,
                            transition = Transition.EXIT,
                            resolveCurrentLocation = true,
                        ),
                        actions = listOf(Action.SetWifi(false)),
                    ),
                    null,
                ),
            ),
        ).review

        val armed = assertIs<FlowArmResult.Armed>(
            fixture.flow.arm(
                ready.draft.snapshot.id,
                ready.draft.snapshot.revision,
                ready.draft.snapshot.fingerprint,
            ),
        ).automation
        val trigger = assertIs<Trigger.Geofence>(armed.trigger)
        assertEquals(45.4642, trigger.lat)
        assertEquals(9.1900, trigger.lng)
        assertFalse(trigger.resolveCurrentLocation)
        assertEquals(armed, registered)
        assertEquals(armed.approvalFingerprint, ApprovalFingerprints.of(armed))
    }

    @Test
    fun `registration failure disables the exact approved revision`() = runTest {
        val fixture = fixture(
            available = setOf(CapabilityIds.TRIGGER_TIME, ActionCapabilities.SET_DND),
            registrar = ArmedAutomationRegistrar { false },
        )
        val ready = assertIs<DraftSubmissionResult.Ready>(
            fixture.flow.submit(
                CompileResult(
                    "ok",
                    AutomationDraft(
                        "DND",
                        Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                        listOf(Action.SetDnd(DndMode.PRIORITY)),
                    ),
                    null,
                ),
            ),
        ).review

        val failed = assertIs<FlowArmResult.RegistrationFailed>(
            fixture.flow.arm(
                ready.draft.snapshot.id,
                ready.draft.snapshot.revision,
                ready.draft.snapshot.fingerprint,
            ),
        )
        assertEquals(
            AutomationStatus.DISABLED,
            fixture.store.get(failed.automation.id)?.status,
        )
    }

    @Test
    fun `compile meta error never creates a pending draft`() = runTest {
        val fixture = fixture(available = emptySet())
        val result = fixture.flow.submit(CompileResult("non valida", null, "schema_incompatible"))
        assertEquals(
            DraftSubmissionResult.NoDraft("non valida", "schema_incompatible"),
            result,
        )
        assertEquals(emptyList(), fixture.repository.observeAll().first())
    }

    @Test
    fun `edit pauses and replaces the exact approved automation id`() = runTest {
        val original = signed(
            Automation(
                id = AutomationId("existing"),
                name = "Wi-Fi sera",
                createdBy = CreatedBy.USER,
                status = AutomationStatus.ARMED,
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                actions = listOf(Action.SetWifi(false)),
            ),
        )
        val fixture = fixture(
            available = setOf(CapabilityIds.TRIGGER_TIME, ActionCapabilities.SET_WIFI),
            initial = listOf(original),
        )

        val ready = assertIs<DraftSubmissionResult.Ready>(
            fixture.flow.submitEdit(
                CompileResult(
                    "Aggiornata",
                    AutomationDraft(
                        "Wi-Fi più tardi",
                        Trigger.Time(cron = "30 23 * * *", tz = "Europe/Rome"),
                        listOf(Action.SetWifi(false)),
                    ),
                    null,
                ),
                original.id,
                requireNotNull(original.approvalFingerprint),
            ),
        ).review.draft.snapshot

        assertEquals(original.id, ready.automationId)
        assertEquals(original.approvalFingerprint, ready.baseAutomationFingerprint)
        assertEquals(AutomationStatus.PENDING_APPROVAL, fixture.store.get(original.id)?.status)

        val armed = assertIs<FlowArmResult.Armed>(
            fixture.flow.arm(ready.id, ready.revision, ready.fingerprint),
        ).automation
        assertEquals(original.id, armed.id)
        assertEquals("Wi-Fi più tardi", armed.name)
    }

    @Test
    fun `edit rejects a newer approved automation than the displayed fingerprint`() = runTest {
        val original = signed(
            Automation(
                id = AutomationId("edit-stale"),
                name = "Wi-Fi sera",
                createdBy = CreatedBy.USER,
                status = AutomationStatus.ARMED,
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                actions = listOf(Action.SetWifi(false)),
            ),
        )
        val fixture = fixture(
            available = setOf(CapabilityIds.TRIGGER_TIME, ActionCapabilities.SET_WIFI),
            initial = listOf(original),
        )
        val newer = signed(original.copy(name = "Revisione nuova"))
        fixture.store.put(newer)

        val result = fixture.flow.submitEdit(
            CompileResult(
                "Aggiornata",
                AutomationDraft(
                    "Versione da contesto vecchio",
                    original.trigger,
                    original.actions,
                ),
                null,
            ),
            original.id,
            requireNotNull(original.approvalFingerprint),
        )

        assertEquals(DraftSubmissionResult.Rejected("automation_stale"), result)
        assertEquals(newer, fixture.store.get(original.id))
        assertEquals(emptyList(), fixture.repository.observeAll().first())
    }

    @Test
    fun `draft recompilation rejects a stale displayed revision`() = runTest {
        val fixture = fixture(
            available = setOf(CapabilityIds.TRIGGER_TIME, ActionCapabilities.SET_WIFI),
        )
        val first = assertIs<DraftSubmissionResult.Ready>(
            fixture.flow.submit(
                CompileResult(
                    "prima",
                    AutomationDraft(
                        "Wi-Fi",
                        Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                        listOf(Action.SetWifi(false)),
                    ),
                    null,
                ),
            ),
        ).review.draft.snapshot
        assertIs<DraftWriteResult.Saved>(
            fixture.repository.revise(
                first.id,
                first.revision,
                first.draft.copy(name = "Cambio concorrente"),
                first.priority,
                2_000L,
            ),
        )

        val result = fixture.flow.submitRevision(
            CompileResult("seconda", first.draft.copy(name = "Cambio chat"), null),
            first.id,
            expectedRevision = first.revision,
        )

        assertEquals(DraftSubmissionResult.Conflict(currentRevision = 2L), result)
        assertEquals("Cambio concorrente", fixture.repository.get(first.id)?.draft?.name)
    }

    private fun fixture(
        available: Set<String>,
        transient: Set<String> = emptySet(),
        initial: List<Automation> = emptyList(),
        location: DeviceLocation? = null,
        registrar: ArmedAutomationRegistrar = ArmedAutomationRegistrar { true },
    ): Fixture {
        val store = MemoryAutomationStore(initial)
        val repository = MemoryDraftRepository(store)
        val snapshot = FirePolicySnapshot(
            knownTools = AndroidCapabilityProbe.KNOWN_TOOLS,
            availableCapabilities = available,
            whitelistedConversationIds = emptySet(),
            transientlyUnavailableCapabilities = transient,
        )
        val approval = ApprovalService(
            repository,
            DraftValidator(snapshot.knownTools),
            ApprovalWhitelistProvider { snapshot.whitelistedConversationIds },
        )
        val flow = ApprovalFlow(
            drafts = repository,
            approvals = approval,
            automations = store,
            capabilities = FirePolicySnapshotProvider { snapshot },
            location = CurrentLocationProvider { location },
            registrar = registrar,
            nowMillis = { 1_000L },
            newDraftId = { DraftId("draft-${repository.count + 1}") },
            newAutomationId = { AutomationId("automation-${repository.count + 1}") },
        )
        return Fixture(flow, store, repository)
    }

    private fun signed(value: Automation): Automation =
        value.copy(approvalFingerprint = ApprovalFingerprints.of(value))

    private data class Fixture(
        val flow: ApprovalFlow,
        val store: MemoryAutomationStore,
        val repository: MemoryDraftRepository,
    )
}

private class MemoryDraftRepository(
    private val automations: MemoryAutomationStore,
) : DraftRepository {
    private val values = MutableStateFlow<Map<DraftId, PendingDraft>>(emptyMap())
    val count: Int get() = values.value.size

    override suspend fun create(newDraft: NewDraft): DraftWriteResult {
        if (newDraft.id in values.value) return DraftWriteResult.Conflict(values.value[newDraft.id]?.revision)
        val existing = automations.get(newDraft.automationId)
        if (newDraft.expectedAutomationFingerprint != null) {
            if (existing?.approvalFingerprint != newDraft.expectedAutomationFingerprint) {
                return DraftWriteResult.Rejected("automation_stale")
            }
        } else if (existing != null) {
            return DraftWriteResult.Rejected("automation_exists")
        }
        val snapshot = snapshot(newDraft, revision = 1, draft = newDraft.draft)
        values.value += snapshot.id to snapshot
        if (existing != null) {
            automations.put(
                existing.copy(status = AutomationStatus.PENDING_APPROVAL, enabled = false),
            )
        }
        return DraftWriteResult.Saved(snapshot)
    }

    override suspend fun revise(
        id: DraftId,
        expectedRevision: Long,
        draft: AutomationDraft,
        priority: Int,
        atMillis: Long,
    ): DraftWriteResult {
        val current = values.value[id] ?: return DraftWriteResult.Conflict(null)
        if (current.revision != expectedRevision) return DraftWriteResult.Conflict(current.revision)
        val revised = snapshot(
            NewDraft(
                id = current.id,
                automationId = current.automationId,
                draft = draft,
                createdBy = current.createdBy,
                priority = priority,
                atMillis = current.createdAtMillis,
                expectedAutomationFingerprint = current.baseAutomationFingerprint,
            ),
            revision = current.revision + 1,
            draft = draft,
            updatedAtMillis = atMillis,
        )
        values.value += id to revised
        return DraftWriteResult.Saved(revised)
    }

    override suspend fun get(id: DraftId): PendingDraft? = values.value[id]
    override fun observeAll(): Flow<List<PendingDraft>> = flowOf(values.value.values.toList())
    override suspend fun delete(id: DraftId, expectedRevision: Long): DraftDeleteResult {
        val current = values.value[id] ?: return DraftDeleteResult.Missing
        if (current.revision != expectedRevision) return DraftDeleteResult.Stale(current.revision)
        values.value -= id
        return DraftDeleteResult.Deleted
    }

    override suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): DraftArmResult {
        val current = values.value[id] ?: return DraftArmResult.Missing
        if (current.revision != expectedRevision) return DraftArmResult.Stale(current.revision)
        if (!current.hasValidFingerprint() || current.fingerprint != expectedFingerprint) {
            return DraftArmResult.IntegrityFailure
        }
        val existing = automations.get(current.automationId)
        if (current.baseAutomationFingerprint == null && existing != null) {
            return DraftArmResult.AutomationConflict
        }
        if (current.baseAutomationFingerprint != null &&
            (existing == null || existing.approvalFingerprint != current.baseAutomationFingerprint)
        ) return DraftArmResult.AutomationConflict
        val armed = current.armedAutomation()
        automations.put(armed)
        values.value -= id
        return DraftArmResult.Armed(armed)
    }

    private fun snapshot(
        newDraft: NewDraft,
        revision: Long,
        draft: AutomationDraft,
        updatedAtMillis: Long = newDraft.atMillis,
    ): PendingDraft {
        val unsigned = PendingDraft(
            id = newDraft.id,
            automationId = newDraft.automationId,
            revision = revision,
            fingerprint = ApprovalFingerprint("0".repeat(64)),
            draft = draft,
            createdBy = newDraft.createdBy,
            priority = newDraft.priority,
            schemaVersion = dev.argus.engine.model.SCHEMA_VERSION,
            createdAtMillis = newDraft.atMillis,
            updatedAtMillis = updatedAtMillis,
            baseAutomationFingerprint = newDraft.expectedAutomationFingerprint,
        )
        return unsigned.copy(fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()))
    }
}

private class MemoryAutomationStore(initial: List<Automation>) : AutomationStore {
    private val values = MutableStateFlow(initial.associateBy { it.id })
    override suspend fun get(id: AutomationId): Automation? = values.value[id]
    override suspend fun all(): List<Automation> = values.value.values.toList()
    override fun observeAll(): Flow<List<Automation>> = values.map { it.values.toList() }
    override suspend fun armed(): List<Automation> = values.value.values.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }
    override suspend fun delete(id: AutomationId) { values.value -= id }
    override suspend fun disable(id: AutomationId) {
        values.value[id]?.let { values.value += id to it.copy(status = AutomationStatus.DISABLED, enabled = false) }
    }
    override suspend fun disableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean {
        val current = values.value[id] ?: return false
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return false
        values.value += id to current.copy(status = AutomationStatus.DISABLED, enabled = false)
        return true
    }
    override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ) = false
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        FireClaimResult.NotEligible
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null

    fun put(automation: Automation) { values.value += automation.id to automation }
}
