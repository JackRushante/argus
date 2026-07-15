package dev.argus.automation

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.automation.connectivity.ConnectivityReconcileReport
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmedAutomationRegistrarTest {
    @Test
    fun `notification rule registers with global listener grant and armed snapshot`() = runTest {
        val rule = notificationRule("wa")
        val registrar = registrar(
            store = SingleRuleStore(rule),
            capabilities = setOf(CapabilityIds.TRIGGER_NOTIFICATION),
        )

        assertTrue(registrar.register(rule))
    }

    @Test
    fun `notification rule without listener grant or armed snapshot fails closed`() = runTest {
        val rule = notificationRule("wa")

        assertFalse(
            registrar(store = SingleRuleStore(rule), capabilities = emptySet()).register(rule),
            "senza grant globale del listener la regola non è registrabile",
        )
        assertFalse(
            registrar(
                store = SingleRuleStore(rule.copy(status = AutomationStatus.DISABLED, enabled = false)),
                capabilities = setOf(CapabilityIds.TRIGGER_NOTIFICATION),
            ).register(rule),
            "lo snapshot persistito deve risultare ARMED ed enabled",
        )
        assertFalse(
            registrar(
                store = SingleRuleStore(rule = null),
                capabilities = setOf(CapabilityIds.TRIGGER_NOTIFICATION),
            ).register(rule),
        )
    }

    @Test
    fun `snapshot failure and unimplemented triggers stay fail closed`() = runTest {
        val rule = notificationRule("wa")
        assertFalse(
            AndroidArmedAutomationRegistrar(
                coordinator = coordinator(SingleRuleStore(rule)),
                store = SingleRuleStore(rule),
                snapshots = FirePolicySnapshotProvider { error("probe down") },
            ).register(rule),
        )

        val connectivity = signed(
            rule("conn").copy(
                trigger = Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED),
                actions = listOf(Action.ShowNotification("Argus", "rete")),
            ),
        )
        assertFalse(
            registrar(
                store = SingleRuleStore(connectivity),
                capabilities = setOf(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI),
            ).register(connectivity),
            "trigger senza registrar reale restano fail-closed",
        )
    }

    @Test
    fun `phone state rule registers only with its granular grant capability`() = runTest {
        val sms = signed(
            rule("sms").copy(
                trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED),
                actions = listOf(Action.ShowNotification("Argus", "sms")),
            ),
        )
        assertTrue(
            registrar(
                store = SingleRuleStore(sms),
                capabilities = setOf(CapabilityIds.TRIGGER_PHONE_SMS),
            ).register(sms),
        )
        assertFalse(
            registrar(
                store = SingleRuleStore(sms),
                capabilities = setOf(CapabilityIds.TRIGGER_PHONE_CALL),
            ).register(sms),
            "il grant chiamate non copre gli SMS: capability granulari",
        )

        val call = signed(
            rule("call").copy(
                trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL),
                actions = listOf(Action.ShowNotification("Argus", "chiamata")),
            ),
        )
        assertTrue(
            registrar(
                store = SingleRuleStore(call),
                capabilities = setOf(CapabilityIds.TRIGGER_PHONE_CALL),
            ).register(call),
        )
        assertFalse(
            registrar(store = SingleRuleStore(call), capabilities = emptySet()).register(call),
        )
    }

    @Test
    fun `connectivity uses granular capability and sentinel only for wifi or power`() = runTest {
        val wifi = signed(
            rule("wifi").copy(
                trigger = Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED),
                actions = listOf(Action.ShowNotification("Argus", "wifi")),
            ),
        )
        val activeRuntime = ConnectivityTriggerRuntime {
            ConnectivityReconcileReport(requiredBy = listOf(wifi.id), active = true)
        }
        assertTrue(
            registrar(
                store = SingleRuleStore(wifi),
                capabilities = setOf(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI),
                connectivity = activeRuntime,
            ).register(wifi),
        )
        assertFalse(
            registrar(
                store = SingleRuleStore(wifi),
                capabilities = setOf(CapabilityIds.TRIGGER_CONNECTIVITY_BT),
                connectivity = activeRuntime,
            ).register(wifi),
        )

        val bluetooth = signed(
            rule("bt").copy(
                trigger = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED),
                actions = listOf(Action.ShowNotification("Argus", "bt")),
            ),
        )
        assertTrue(
            registrar(
                store = SingleRuleStore(bluetooth),
                capabilities = setOf(CapabilityIds.TRIGGER_CONNECTIVITY_BT),
            ).register(bluetooth),
            "il receiver Bluetooth manifest non deve avviare la sentinella",
        )
    }

    @Test
    fun `time rule still registers through the alarm coordinator`() = runTest {
        val rule = signed(
            rule("time").copy(
                trigger = Trigger.Time(
                    cron = "0 8 * * *",
                    tz = "Europe/Rome",
                    precision = TimePrecision.FLEXIBLE,
                ),
            ),
        )
        val store = SingleRuleStore(rule)

        assertTrue(registrar(store = store).register(rule))
    }

    private fun registrar(
        store: AutomationStore,
        capabilities: Set<String> = emptySet(),
        connectivity: ConnectivityTriggerRuntime = dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime,
    ) = AndroidArmedAutomationRegistrar(
        coordinator = coordinator(store),
        store = store,
        snapshots = FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = AndroidCapabilityProbe.KNOWN_TOOLS,
                availableCapabilities = capabilities,
                whitelistedConversationIds = setOf(CONVERSATION_ID),
            )
        },
        connectivity = connectivity,
    )

    private fun coordinator(store: AutomationStore) = TimeAlarmCoordinator(
        store = store,
        state = InMemoryTimeAlarmStateStore(),
        backend = AcceptingTimeAlarmBackend(),
        dispatcher = { },
        now = { Instant.parse("2026-07-14T08:00:00Z") },
    )

    private fun notificationRule(id: String): Automation = signed(rule(id))

    private fun rule(id: String): Automation = Automation(
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
        enabled = true,
        cooldownMs = 60_000,
    )

    private fun signed(value: Automation): Automation =
        value.copy(approvalFingerprint = ApprovalFingerprints.of(value))

    private class SingleRuleStore(private val rule: Automation?) : AutomationStore {
        override suspend fun get(id: AutomationId): Automation? = rule?.takeIf { it.id == id }
        override suspend fun all(): List<Automation> = listOfNotNull(rule)
        override fun observeAll(): Flow<List<Automation>> = flowOf(listOfNotNull(rule))
        override suspend fun armed(): List<Automation> = listOfNotNull(rule).filter {
            it.status == AutomationStatus.ARMED && it.enabled
        }

        override suspend fun delete(id: AutomationId) = Unit
        override suspend fun disable(id: AutomationId) = Unit
        override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) =
            false

        override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) =
            false

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

    private class InMemoryTimeAlarmStateStore : TimeAlarmStateStore {
        private val alarms = mutableMapOf<AutomationId, ScheduledTimeAlarm>()
        override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? =
            alarms[automationId]

        override suspend fun all(): List<ScheduledTimeAlarm> = alarms.values.toList()
        override suspend fun upsert(alarm: ScheduledTimeAlarm) {
            alarms[alarm.automationId] = alarm
        }

        override suspend fun delete(automationId: AutomationId) {
            alarms.remove(automationId)
        }
    }

    private class AcceptingTimeAlarmBackend : TimeAlarmBackend {
        override fun canScheduleExact(): Boolean = false
        override fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode =
            ScheduledAlarmMode.INEXACT

        override fun cancel(automationId: AutomationId) = Unit
    }

    private companion object {
        const val CONVERSATION_ID = "shortcut:com.whatsapp:hash"
    }
}
