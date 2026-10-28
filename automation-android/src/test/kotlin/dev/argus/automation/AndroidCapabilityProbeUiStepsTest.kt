package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.DeviceState
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ui_steps` si annuncia al modello e si arma solo con l'Accessibilità accesa e le notifiche concesse. */
class AndroidCapabilityProbeUiStepsTest {
    private fun state(accessibility: Boolean, notifications: Boolean) = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        shizukuPermissionGranted = false,
        notificationsGranted = notifications,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = false,
        batteryOptimizationExempt = false,
        accessibilityEnabled = accessibility,
    )

    private object EmptyWhitelist : ContactWhitelistStore {
        override suspend fun all(): List<WhitelistedContact> = emptyList()
        override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
        override suspend fun upsert(contact: WhitelistedContact) = Unit
        override suspend fun remove(conversationId: String) = Unit
    }

    private fun probe(state: AndroidCapabilityState) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        EmptyWhitelist,
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
    )

    @Test
    fun `with the service on and notifications granted the macro is announced and armable`() = runTest {
        val probe = probe(state(accessibility = true, notifications = true))
        val manifest = probe.probe(DeviceState())
        assertTrue(ActionTypeIds.UI_STEPS in manifest.availableTools)
        assertFalse(ActionTypeIds.UI_STEPS in manifest.unavailableTools)
        assertTrue(ActionCapabilities.UI_STEPS in probe.current().availableCapabilities)
        assertTrue("accessibility" in manifest.grantedPermissions)
    }

    @Test
    fun `with the service off the model is told why and the capability is missing`() = runTest {
        val probe = probe(state(accessibility = false, notifications = true))
        val manifest = probe.probe(DeviceState())
        assertFalse(ActionTypeIds.UI_STEPS in manifest.availableTools)
        assertEquals(AndroidCapabilityProbe.REASON_ACCESSIBILITY, manifest.unavailableTools[ActionTypeIds.UI_STEPS])
        assertFalse(ActionCapabilities.UI_STEPS in probe.current().availableCapabilities)
        assertFalse("accessibility" in manifest.grantedPermissions)
    }

    @Test
    fun `the reason for the service being off says what to turn on and where, not that the feature is missing`() = runTest {
        val manifest = probe(state(accessibility = false, notifications = true)).probe(DeviceState())
        val reason = manifest.unavailableTools.getValue(ActionTypeIds.UI_STEPS)

        assertTrue("Accessibility" in reason && "Argus" in reason, reason)
        assertTrue("turned off" in reason && "turn it on" in reason, reason)
        assertTrue("Settings > Accessibility" in reason, reason)
        listOf("not available", "not supported", "this version").forEach {
            assertFalse(it in reason, "$it in: $reason")
        }
        // Il motivo è fisso: nessun dato dell'utente, e abbastanza corto da stare in ogni compile.
        assertTrue(reason.length < 250, "${reason.length} caratteri")
    }

    @Test
    fun `without notifications the macro has no stop button so it is not offered`() = runTest {
        val probe = probe(state(accessibility = true, notifications = false))
        val manifest = probe.probe(DeviceState())
        assertFalse(ActionTypeIds.UI_STEPS in manifest.availableTools)
        assertEquals("notification permission missing", manifest.unavailableTools[ActionTypeIds.UI_STEPS])
        assertFalse(ActionCapabilities.UI_STEPS in probe.current().availableCapabilities)
    }

    @Test
    fun `the block cause tells which of the two is missing`() = runTest {
        val required = setOf(ActionCapabilities.UI_STEPS)
        assertEquals(
            BlockCause.ACCESSIBILITY_SERVICE,
            probe(state(accessibility = false, notifications = true)).blockCauses(required)[ActionCapabilities.UI_STEPS],
        )
        assertEquals(
            BlockCause.NOTIFICATIONS_PERMISSION,
            probe(state(accessibility = true, notifications = false)).blockCauses(required)[ActionCapabilities.UI_STEPS],
        )
        assertTrue(probe(state(accessibility = true, notifications = true)).blockCauses(required).isEmpty())
    }

    @Test
    fun `an accessibility service that is off is a temporary block, not a structural one`() = runTest {
        // Come ogni capability di permesso: manca, ma non entra fra le transitorie di Shizuku.
        val snapshot = probe(state(accessibility = false, notifications = true)).current()
        assertEquals(emptySet(), snapshot.transientlyUnavailableCapabilities)
    }
}
