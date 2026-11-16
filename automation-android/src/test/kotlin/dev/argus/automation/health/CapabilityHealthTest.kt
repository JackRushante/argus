package dev.argus.automation.health

import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AndroidCapabilityState
import dev.argus.automation.AndroidCapabilityStateSource
import dev.argus.automation.BlockCause
import dev.argus.automation.GenerativeReadiness
import dev.argus.automation.GenerativeRuntimeReadiness
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityHealthTest {
    @Test
    fun `block causes map to the thing to fix`() {
        listOf(
            BlockCause.SHIZUKU_NOT_INSTALLED,
            BlockCause.SHIZUKU_NOT_RUNNING,
            BlockCause.SHIZUKU_NOT_AUTHORIZED,
            BlockCause.SHIZUKU_TOO_OLD,
        ).forEach { assertEquals(HealthCapability.PRIVILEGED_BACKEND, CapabilityHealth.of(it)) }
        assertEquals(HealthCapability.NOTIFICATION_LISTENER, CapabilityHealth.of(BlockCause.NOTIFICATION_ACCESS))
        assertEquals(HealthCapability.ACCESSIBILITY, CapabilityHealth.of(BlockCause.ACCESSIBILITY_SERVICE))
        assertEquals(HealthCapability.LOCATION, CapabilityHealth.of(BlockCause.LOCATION_PERMISSION))
        // Fuori dagli avvisi: la posizione spenta dalle impostazioni rapide, gli SMS, l'AI.
        assertNull(CapabilityHealth.of(BlockCause.LOCATION_OFF))
        assertNull(CapabilityHealth.of(BlockCause.SMS_PERMISSION))
        assertNull(CapabilityHealth.of(BlockCause.AI_NOT_READY))
    }

    @Test
    fun `exact alarms matter only for punctual time rules`() {
        val snapshot = CapabilityHealthSnapshot(emptyMap(), exactAlarmsGranted = false)
        assertEquals(
            setOf(HealthCapability.EXACT_ALARMS),
            CapabilityHealth.missing(rule("exact", time(TimePrecision.EXACT)), snapshot),
        )
        assertTrue(CapabilityHealth.missing(rule("flexible", time(TimePrecision.FLEXIBLE)), snapshot).isEmpty())
        assertTrue(
            CapabilityHealth.missing(
                rule("granted", time(TimePrecision.EXACT)),
                snapshot.copy(exactAlarmsGranted = true),
            ).isEmpty(),
        )
    }

    @Test
    fun `affected counts only enabled rules`() {
        val wifi = rule("wifi", time(TimePrecision.FLEXIBLE), Action.SetWifi(false))
        val off = wifi.copy(id = AutomationId("off"), enabled = false)
        val snapshot = CapabilityHealthSnapshot(
            wifi.requiredCapabilities.associateWith { BlockCause.SHIZUKU_NOT_RUNNING },
            exactAlarmsGranted = true,
        )
        assertEquals(mapOf(HealthCapability.PRIVILEGED_BACKEND to 1), CapabilityHealth.affectedCounts(listOf(wifi, off), snapshot))
    }

    @Test
    fun `the probe snapshot names Shizuku, the listener and location for the rules that need them`() = runTest {
        val probe = AndroidCapabilityProbe(
            AndroidCapabilityStateSource { lostState() },
            EmptyWhitelist,
            GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true) },
            baseTierActive = true,
        )
        val rules = listOf(
            rule("wifi", time(TimePrecision.FLEXIBLE), Action.SetWifi(false)),
            rule("whatsapp", Trigger.Notification(pkg = "com.whatsapp"), Action.Vibrate(200)),
            rule("home", Trigger.Geofence(lat = 45.0, lng = 9.0, radiusM = 100.0, transition = Transition.ENTER), Action.Vibrate(200)),
            rule("alarm", time(TimePrecision.EXACT), Action.Vibrate(200)),
        )

        val snapshot = probe.healthSnapshot(rules.flatMapTo(linkedSetOf()) { it.requiredCapabilities })

        assertEquals(
            mapOf(
                HealthCapability.PRIVILEGED_BACKEND to 1,
                HealthCapability.NOTIFICATION_LISTENER to 1,
                HealthCapability.LOCATION to 1,
                HealthCapability.EXACT_ALARMS to 1,
            ),
            CapabilityHealth.affectedCounts(rules, snapshot),
        )
    }

    private fun lostState() = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
        shizukuPermissionGranted = true,
        notificationsGranted = true,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = false,
        batteryOptimizationExempt = true,
    )

    private fun time(precision: TimePrecision) = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome", precision = precision)

    private fun rule(id: String, trigger: Trigger, action: Action = Action.Vibrate(200)) = Automation(
        id = AutomationId(id),
        name = id,
        createdBy = CreatedBy.USER,
        status = AutomationStatus.ARMED,
        trigger = trigger,
        actions = listOf(action),
    )

    private object EmptyWhitelist : ContactWhitelistStore {
        override suspend fun all(): List<WhitelistedContact> = emptyList()
        override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
        override suspend fun upsert(contact: WhitelistedContact) = Unit
        override suspend fun remove(conversationId: String) = Unit
    }
}
