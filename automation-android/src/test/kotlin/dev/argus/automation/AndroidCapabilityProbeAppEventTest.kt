package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.5.1: «app aperta/chiusa» si annuncia e si arma solo con l'accesso ai dati di utilizzo concesso. */
class AndroidCapabilityProbeAppEventTest {
    private fun state(usage: Boolean, shizuku: ShizukuGatewayStatus = ShizukuGatewayStatus.NOT_INSTALLED) =
        AndroidCapabilityState(
            deviceModel = "CPH2747",
            androidVersion = 16,
            androidApi = 36,
            shizukuStatus = shizuku,
            shizukuPermissionGranted = false,
            notificationsGranted = false,
            notificationListenerGranted = false,
            foregroundLocationGranted = false,
            backgroundLocationGranted = false,
            exactAlarmsGranted = false,
            batteryOptimizationExempt = false,
            usageStatsGranted = usage,
        )

    private fun capabilityProbe(state: AndroidCapabilityState) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        object : ContactWhitelistStore {
            override suspend fun all(): List<WhitelistedContact> = emptyList()
            override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
            override suspend fun upsert(contact: WhitelistedContact) = Unit
            override suspend fun remove(conversationId: String) = Unit
        },
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
    )

    private val trigger = Trigger.AppEvent("com.google.android.youtube", AppEventKind.OPENED)

    @Test
    fun `with the usage access the trigger is announced and armable, without any shell`() = runTest {
        val probe = capabilityProbe(state(usage = true))

        assertTrue("app_event" in probe.probe(DeviceState()).availableTriggers)
        val snapshot = probe.current()
        assertTrue(CapabilityIds.TRIGGER_APP_EVENT in snapshot.availableCapabilities)
        val required = CapabilityRequirements.derive(trigger, listOf<Action>(Action.Vibrate(100)))
            .filterNot { it.startsWith("action.") }
        assertEquals(setOf(CapabilityIds.TRIGGER_APP_EVENT), required.toSet())
        assertTrue(snapshot.availableCapabilities.containsAll(required))
    }

    @Test
    fun `without the usage access the trigger is not announced and not armable`() = runTest {
        val probe = capabilityProbe(state(usage = false))

        assertFalse("app_event" in probe.probe(DeviceState()).availableTriggers)
        assertFalse(CapabilityIds.TRIGGER_APP_EVENT in probe.current().availableCapabilities)
    }

    @Test
    fun `an authorized Shizuku alone does not publish it, the permission is what counts`() = runTest {
        val probe = capabilityProbe(state(usage = false, shizuku = ShizukuGatewayStatus.AUTHORIZED))

        assertFalse("app_event" in probe.probe(DeviceState()).availableTriggers)
        assertFalse(CapabilityIds.TRIGGER_APP_EVENT in probe.current().availableCapabilities)
    }

    @Test
    fun `a rule that needs it is blocked with the usage access cause`() = runTest {
        val probe = capabilityProbe(state(usage = false))

        assertEquals(
            mapOf(CapabilityIds.TRIGGER_APP_EVENT to BlockCause.USAGE_ACCESS),
            probe.blockCauses(setOf(CapabilityIds.TRIGGER_APP_EVENT)),
        )
        assertTrue(capabilityProbe(state(usage = true)).blockCauses(setOf(CapabilityIds.TRIGGER_APP_EVENT)).isEmpty())
    }

    @Test
    fun `the usage permission was already in the granted list and the foreground condition is unchanged`() = runTest {
        val manifest = capabilityProbe(state(usage = true)).probe(DeviceState())
        assertTrue("usage_stats" in manifest.grantedPermissions)
        assertFalse("usage_stats" in capabilityProbe(state(usage = false)).probe(DeviceState()).grantedPermissions)
    }
}
