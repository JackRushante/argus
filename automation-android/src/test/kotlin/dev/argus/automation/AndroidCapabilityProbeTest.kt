package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCapabilityProbeTest {
    @Test
    fun `authorized device yields coherent manifest policy snapshot and whitelist`() = runTest {
        val probe = probe(
            state().copy(
                notificationsGranted = true,
                notificationListenerGranted = true,
                foregroundLocationGranted = true,
                exactAlarmsGranted = true,
            ),
            listOf(WhitelistedContact("Moglie", "jid:42")),
        )

        val manifest = probe.probe(DeviceState())
        val snapshot = probe.current()

        assertEquals("CPH2747", manifest.deviceModel)
        assertEquals(16, manifest.androidVersion)
        assertEquals(36, manifest.androidApi)
        assertTrue(manifest.shizukuAvailable)
        assertEquals(StateKeys.ALL, manifest.stateKeys)
        assertEquals(listOf(WhitelistedContact("Moglie", "jid:42")), manifest.whitelistedContacts)
        assertTrue("notification_listener" in manifest.grantedPermissions)
        assertTrue(AndroidCapabilityProbe.TOOL_STATE_READ in manifest.availableTools)
        assertTrue(AndroidCapabilityProbe.TOOL_NOTIFY_SHOW in manifest.availableTools)
        assertTrue(ActionTypeIds.SET_DND in manifest.availableTools)
        assertTrue(ActionTypeIds.SET_WIFI in manifest.availableTools)
        assertTrue(ActionTypeIds.SHOW_NOTIFICATION in manifest.availableTools)
        assertTrue("vision.analyze" in manifest.unavailableTools)
        assertTrue(ActionCapabilities.SET_DND in snapshot.availableCapabilities)
        assertTrue(ActionCapabilities.SHOW_NOTIFICATION in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.state(StateKeys.RINGER) in snapshot.availableCapabilities)
        assertEquals(setOf("jid:42"), snapshot.whitelistedConversationIds)
        assertEquals(emptySet(), snapshot.transientlyUnavailableCapabilities)
    }

    @Test
    fun `stopped but still granted Shizuku is transient while revoked permission is structural`() = runTest {
        val stopped = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
            ),
        ).current()
        assertFalse(ActionCapabilities.SET_DND in stopped.availableCapabilities)
        assertTrue(ActionCapabilities.SET_DND in stopped.transientlyUnavailableCapabilities)
        assertFalse(ActionTypeIds.SET_DND in probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
            ),
        ).probe(DeviceState()).availableTools)

        val revokedProbe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                shizukuPermissionGranted = false,
            ),
        )
        val revoked = revokedProbe.current()
        assertFalse(ActionCapabilities.SET_DND in revoked.availableCapabilities)
        assertFalse(ActionCapabilities.SET_DND in revoked.transientlyUnavailableCapabilities)
        val revokedManifest = revokedProbe.probe(DeviceState())
        assertFalse(ActionTypeIds.SET_DND in revokedManifest.availableTools)
        assertTrue(ActionTypeIds.SET_DND in revokedManifest.unavailableTools)
    }

    @Test
    fun `missing listener grant keeps notification trigger and raw reply unavailable with exact reason`() = runTest {
        val probe = probe(
            state().copy(
                notificationsGranted = true,
                notificationListenerGranted = false,
                batteryOptimizationExempt = true,
            ),
        )

        val snapshot = probe.current()
        assertFalse(CapabilityIds.TRIGGER_NOTIFICATION in snapshot.availableCapabilities)
        assertFalse(GenerativeContract.TOOL_WHATSAPP_REPLY in snapshot.availableCapabilities)
        assertFalse(CapabilityIds.TRIGGER_NOTIFICATION in snapshot.transientlyUnavailableCapabilities)
        // Il runtime generativo è pronto: solo il canale notification/reply resta strutturalmente giù.
        assertTrue(CapabilityIds.ACTION_INVOKE_LLM in snapshot.availableCapabilities)

        val manifest = probe.probe(DeviceState())
        assertFalse(GenerativeContract.TOOL_WHATSAPP_REPLY in manifest.availableTools)
        assertEquals(
            AndroidCapabilityProbe.REASON_NOTIFICATION_LISTENER,
            manifest.unavailableTools[GenerativeContract.TOOL_WHATSAPP_REPLY],
        )
    }

    @Test
    fun `listener grant without generative readiness keeps invoke_llm unavailable`() = runTest {
        for (
        notReady in listOf(
            GenerativeReadiness(bridgeConfigured = false, privacyAccepted = true),
            GenerativeReadiness(bridgeConfigured = true, privacyAccepted = false),
        )
        ) {
            val snapshot = probe(
                state().copy(
                    notificationListenerGranted = true,
                    batteryOptimizationExempt = true,
                ),
                readiness = notReady,
            ).current()
            assertTrue(CapabilityIds.TRIGGER_NOTIFICATION in snapshot.availableCapabilities)
            assertTrue(GenerativeContract.TOOL_WHATSAPP_REPLY in snapshot.availableCapabilities)
            assertFalse(CapabilityIds.ACTION_INVOKE_LLM in snapshot.availableCapabilities)
            assertFalse(CapabilityIds.ACTION_INVOKE_LLM in snapshot.transientlyUnavailableCapabilities)
        }

        val withoutBattery = probe(
            state().copy(
                notificationListenerGranted = true,
                batteryOptimizationExempt = false,
            ),
        ).current()
        assertFalse(CapabilityIds.ACTION_INVOKE_LLM in withoutBattery.availableCapabilities)
    }

    @Test
    fun `ready generative runtime publishes trigger raw reply and invoke_llm`() = runTest {
        val probe = probe(
            state().copy(
                notificationsGranted = true,
                notificationListenerGranted = true,
                batteryOptimizationExempt = true,
            ),
        )

        val snapshot = probe.current()
        assertTrue(CapabilityIds.TRIGGER_NOTIFICATION in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.ACTION_INVOKE_LLM in snapshot.availableCapabilities)
        assertTrue(GenerativeContract.TOOL_WHATSAPP_REPLY in snapshot.availableCapabilities)
        // La reply statica non ha ancora un executor: pubblicarla sarebbe advertising falso.
        assertFalse(ActionCapabilities.WHATSAPP_REPLY in snapshot.availableCapabilities)
        assertTrue(GenerativeContract.TOOL_WHATSAPP_REPLY in probe.probe(DeviceState()).availableTools)
    }

    @Test
    fun `shizuku raw tools stay aligned between manifest and policy snapshot`() = runTest {
        val authorized = probe(state()).current()
        AndroidCapabilityProbe.SHIZUKU_TOOLS.forEach { tool ->
            assertTrue(tool in authorized.availableCapabilities, "manca $tool")
        }

        val stopped = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
            ),
        ).current()
        AndroidCapabilityProbe.SHIZUKU_TOOLS.forEach { tool ->
            assertFalse(tool in stopped.availableCapabilities)
            assertTrue(tool in stopped.transientlyUnavailableCapabilities, "manca transiente $tool")
        }
    }

    @Test
    fun `reconciler quarantines generative rule on privacy or listener revocation`() = runTest {
        val rule = generativeRule("generative")
        val contacts = listOf(WhitelistedContact("Moglie", CONVERSATION_ID))
        val readyState = state().copy(
            notificationListenerGranted = true,
            batteryOptimizationExempt = true,
        )

        val healthyStore = ReconcileAutomationStore(rule)
        val healthy = CapabilityReconciler(healthyStore, probe(readyState, contacts)).reconcile()
        assertEquals(emptyList(), healthy.needsReview)
        assertEquals(AutomationStatus.ARMED, healthyStore.get(rule.id)?.status)

        val privacyRevokedStore = ReconcileAutomationStore(rule)
        val privacyRevoked = CapabilityReconciler(
            privacyRevokedStore,
            probe(
                readyState,
                contacts,
                readiness = GenerativeReadiness(bridgeConfigured = true, privacyAccepted = false),
            ),
        ).reconcile()
        assertEquals(listOf(rule.id), privacyRevoked.needsReview)
        assertEquals(AutomationStatus.NEEDS_REVIEW, privacyRevokedStore.get(rule.id)?.status)

        val listenerRevokedStore = ReconcileAutomationStore(rule)
        val listenerRevoked = CapabilityReconciler(
            listenerRevokedStore,
            probe(readyState.copy(notificationListenerGranted = false), contacts),
        ).reconcile()
        assertEquals(listOf(rule.id), listenerRevoked.needsReview)
    }

    @Test
    fun `reconciler quarantines revocation but preserves temporary outage`() = runTest {
        val rule = signedRule("dnd")
        val revokedStore = ReconcileAutomationStore(rule)
        val revokedProbe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                shizukuPermissionGranted = false,
            ),
        )
        val revoked = CapabilityReconciler(revokedStore, revokedProbe).reconcile()
        assertEquals(listOf(rule.id), revoked.needsReview)
        assertEquals(AutomationStatus.NEEDS_REVIEW, revokedStore.get(rule.id)?.status)

        val temporaryStore = ReconcileAutomationStore(rule)
        val temporaryProbe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
            ),
        )
        val temporary = CapabilityReconciler(temporaryStore, temporaryProbe).reconcile()
        assertEquals(listOf(rule.id), temporary.temporarilyBlocked)
        assertEquals(AutomationStatus.ARMED, temporaryStore.get(rule.id)?.status)
    }

    @Test
    fun `reconciler cannot quarantine a newer approved revision`() = runTest {
        val original = signedRule("race")
        val revised = sign(original.copy(name = "nuova revisione", approvalFingerprint = null))
        val store = ReconcileAutomationStore(original).apply {
            beforeConditionalMark = { replace(revised) }
        }
        val revokedProbe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                shizukuPermissionGranted = false,
            ),
        )

        assertEquals(emptyList(), CapabilityReconciler(store, revokedProbe).reconcile().needsReview)
        assertEquals(revised, store.get(original.id))
    }

    private fun probe(
        state: AndroidCapabilityState,
        contacts: List<WhitelistedContact> = emptyList(),
        readiness: GenerativeReadiness = GenerativeReadiness(
            bridgeConfigured = true,
            privacyAccepted = true,
        ),
    ) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        FakeWhitelist(contacts),
        GenerativeRuntimeReadiness { readiness },
    )

    private fun state() = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.AUTHORIZED,
        shizukuPermissionGranted = true,
        notificationsGranted = false,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = false,
        batteryOptimizationExempt = false,
    )

    private fun signedRule(id: String): Automation = sign(
        Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        ),
    )

    private fun generativeRule(id: String): Automation = sign(
        Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Notification(
                pkg = "com.whatsapp",
                conversationId = CONVERSATION_ID,
                isGroup = false,
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
        ),
    )

    private fun sign(value: Automation): Automation =
        value.copy(approvalFingerprint = ApprovalFingerprints.of(value))

    private companion object {
        const val CONVERSATION_ID = "shortcut:com.whatsapp:hash"
    }
}

private class FakeWhitelist(initial: List<WhitelistedContact>) : ContactWhitelistStore {
    private val values = MutableStateFlow(initial)
    override suspend fun all(): List<WhitelistedContact> = values.value
    override fun observeAll(): Flow<List<WhitelistedContact>> = values
    override suspend fun upsert(contact: WhitelistedContact) {
        values.value = values.value.filterNot { it.id == contact.id } + contact
    }
    override suspend fun remove(conversationId: String) {
        values.value = values.value.filterNot { it.id == conversationId }
    }
}

private class ReconcileAutomationStore(initial: Automation) : AutomationStore {
    private val values = MutableStateFlow(mapOf(initial.id to initial))
    var beforeConditionalMark: (() -> Unit)? = null

    override suspend fun get(id: AutomationId): Automation? = values.value[id]
    override suspend fun all(): List<Automation> = values.value.values.toList()
    override fun observeAll(): Flow<List<Automation>> = flowOf(values.value.values.toList())
    override suspend fun armed(): List<Automation> = values.value.values.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }
    override suspend fun delete(id: AutomationId) { values.value -= id }
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean {
        beforeConditionalMark?.also { beforeConditionalMark = null }?.invoke()
        val current = values.value[id] ?: return false
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return false
        values.value += id to current.copy(status = AutomationStatus.NEEDS_REVIEW, enabled = false)
        return true
    }
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        FireClaimResult.NotEligible
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null

    fun replace(value: Automation) { values.value += value.id to value }
}
