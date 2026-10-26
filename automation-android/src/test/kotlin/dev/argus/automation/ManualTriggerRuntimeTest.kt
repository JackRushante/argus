package dev.argus.automation

import dev.argus.automation.manual.MapStore
import dev.argus.automation.manual.manualRule
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Trigger `manual` (F8) nel runtime: probe, registrazione all'arm e requisiti di capacità. */
class ManualTriggerRuntimeTest {
    private fun state() = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        shizukuPermissionGranted = false,
        notificationsGranted = false,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = false,
        batteryOptimizationExempt = false,
    )

    private fun probe() = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state() },
        object : ContactWhitelistStore {
            override suspend fun all(): List<WhitelistedContact> = emptyList()
            override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
            override suspend fun upsert(contact: WhitelistedContact) = Unit
            override suspend fun remove(conversationId: String) = Unit
        },
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
    )

    @Test
    fun `manual is announced and armable with no grant and no Shizuku`() = runTest {
        val probe = probe()

        assertTrue("manual" in probe.probe(DeviceState()).availableTriggers)
        assertTrue(CapabilityIds.TRIGGER_MANUAL in probe.current().availableCapabilities)
    }

    private fun registrar(store: MapStore, capabilities: Set<String>) = AndroidArmedAutomationRegistrar(
        coordinator = TimeAlarmCoordinator(
            store = store,
            state = object : TimeAlarmStateStore {
                override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? = null
                override suspend fun all(): List<ScheduledTimeAlarm> = emptyList()
                override suspend fun upsert(alarm: ScheduledTimeAlarm) = Unit
                override suspend fun delete(automationId: AutomationId) = Unit
            },
            backend = object : TimeAlarmBackend {
                override fun canScheduleExact(): Boolean = false
                override fun schedule(registration: TimeAlarmRegistration) = ScheduledAlarmMode.INEXACT
                override fun cancel(automationId: AutomationId) = Unit
            },
            dispatcher = { },
            now = { Instant.parse("2026-10-26T10:00:00Z") },
        ),
        store = store,
        snapshots = FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = AndroidCapabilityProbe.KNOWN_TOOLS,
                availableCapabilities = capabilities,
                whitelistedConversationIds = emptySet(),
            )
        },
    )

    @Test
    fun `arming a manual rule needs only its capability and a persisted armed rule`() = runTest {
        val rule = manualRule("m1")
        val capabilities = setOf(CapabilityIds.TRIGGER_MANUAL)

        assertTrue(registrar(MapStore(rule), capabilities).register(rule))
        assertFalse(
            registrar(MapStore(rule), emptySet()).register(rule),
            "senza la capability pubblicata non si arma",
        )
        assertFalse(
            registrar(MapStore(manualRule("m1", status = AutomationStatus.DISABLED)), capabilities).register(rule),
            "la regola persistita deve essere armata e abilitata",
        )
        assertFalse(registrar(MapStore(), capabilities).register(rule))
    }

    @Test
    fun `a manual rule requires exactly the manual capability plus its actions`() {
        val rule = manualRule("m1")
        assertEquals(
            setOf(CapabilityIds.TRIGGER_MANUAL, CapabilityIds.ACTION_SET_FLASHLIGHT),
            rule.requiredCapabilities,
        )
    }
}
