package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
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

/** Capability e probe delle azioni 0.4.1: parlato, tasti multimediali, HTTP, luminosità e rotazione. */
class AndroidCapabilityProbeActions041Test {

    private fun state(
        shizuku: ShizukuGatewayStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        writeSettings: Boolean = false,
    ) = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = shizuku,
        shizukuPermissionGranted = shizuku == ShizukuGatewayStatus.AUTHORIZED,
        notificationsGranted = false,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = false,
        batteryOptimizationExempt = false,
        writeSettingsGranted = writeSettings,
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
    fun `speak media control and http need no permission and no Shizuku`() = runTest {
        val probe = probe(state())
        val manifest = probe.probe(DeviceState())
        val snapshot = probe.current()

        listOf(ActionTypeIds.SPEAK, ActionTypeIds.MEDIA_CONTROL, ActionTypeIds.HTTP_REQUEST).forEach { type ->
            assertTrue(type in manifest.availableTools, type)
            assertFalse(type in manifest.unavailableTools, type)
        }
        listOf(ActionCapabilities.SPEAK, ActionCapabilities.MEDIA_CONTROL, ActionCapabilities.HTTP_REQUEST)
            .forEach { assertTrue(it in snapshot.availableCapabilities, it) }
        assertEquals(emptySet(), snapshot.transientlyUnavailableCapabilities)
    }

    @Test
    fun `brightness and rotation are published only when modify system settings is granted`() = runTest {
        val denied = probe(state(writeSettings = false))
        val deniedManifest = denied.probe(DeviceState())
        listOf(ActionTypeIds.SET_BRIGHTNESS, ActionTypeIds.SET_ROTATION).forEach { type ->
            assertFalse(type in deniedManifest.availableTools, type)
            assertEquals(AndroidCapabilityProbe.REASON_WRITE_SETTINGS, deniedManifest.unavailableTools[type], type)
        }
        listOf(ActionCapabilities.SET_BRIGHTNESS, ActionCapabilities.SET_ROTATION).forEach {
            assertFalse(it in denied.current().availableCapabilities, it)
        }
        assertFalse("write_settings" in deniedManifest.grantedPermissions)

        val granted = probe(state(writeSettings = true))
        val grantedManifest = granted.probe(DeviceState())
        listOf(ActionTypeIds.SET_BRIGHTNESS, ActionTypeIds.SET_ROTATION).forEach { type ->
            assertTrue(type in grantedManifest.availableTools, type)
            assertFalse(type in grantedManifest.unavailableTools, type)
        }
        listOf(ActionCapabilities.SET_BRIGHTNESS, ActionCapabilities.SET_ROTATION).forEach {
            assertTrue(it in granted.current().availableCapabilities, it)
        }
        assertTrue("write_settings" in grantedManifest.grantedPermissions)
    }

    @Test
    fun `the special permission does not depend on Shizuku in either direction`() = runTest {
        // Con Shizuku autorizzato ma senza il permesso speciale, luminosità e rotazione restano non disponibili.
        val withShizukuOnly = probe(state(ShizukuGatewayStatus.AUTHORIZED, writeSettings = false))
        assertFalse(
            ActionCapabilities.SET_BRIGHTNESS in withShizukuOnly.current().availableCapabilities,
        )
        // Col permesso ma senza Shizuku sono disponibili e nessuna è «transitoria».
        val withPermissionOnly = probe(state(ShizukuGatewayStatus.INSTALLED_NOT_RUNNING, writeSettings = true))
        val snapshot = withPermissionOnly.current()
        assertTrue(ActionCapabilities.SET_BRIGHTNESS in snapshot.availableCapabilities)
        assertFalse(ActionCapabilities.SET_BRIGHTNESS in snapshot.transientlyUnavailableCapabilities)
        assertFalse(ActionCapabilities.HTTP_REQUEST in snapshot.transientlyUnavailableCapabilities)
    }

    @Test
    fun `a missing special permission is diagnosed as such and not as Shizuku`() = runTest {
        val probe = probe(state(writeSettings = false))

        val causes = probe.blockCauses(
            setOf(ActionCapabilities.SET_BRIGHTNESS, ActionCapabilities.SET_ROTATION, ActionCapabilities.SPEAK),
        )

        assertEquals(
            mapOf(
                ActionCapabilities.SET_BRIGHTNESS to BlockCause.WRITE_SETTINGS_ACCESS,
                ActionCapabilities.SET_ROTATION to BlockCause.WRITE_SETTINGS_ACCESS,
            ),
            causes,
        )
    }

    @Test
    fun `the fire time policy blocks a brightness rule when the permission was revoked`() = runTest {
        val rule = ruleWith(Action.SetBrightness(level = 30))
        val granted = probe(state(writeSettings = true))
        val revoked = probe(state(writeSettings = false))

        assertEquals(
            setOf(CapabilityIds.TRIGGER_IMMEDIATE, CapabilityIds.ACTION_SET_BRIGHTNESS),
            rule.requiredCapabilities,
        )
        assertTrue(rule.requiredCapabilities.all { it in granted.current().availableCapabilities })
        assertEquals(
            listOf(CapabilityIds.ACTION_SET_BRIGHTNESS),
            (rule.requiredCapabilities - revoked.current().availableCapabilities).toList(),
        )
    }

    @Test
    fun `the new action types are real wire names the compiler can use`() {
        assertEquals(
            setOf("speak", "media_control", "http_request"),
            AndroidCapabilityProbe.BASE_ACTIONS_041_TYPES,
        )
        assertEquals(setOf("set_brightness", "set_rotation"), AndroidCapabilityProbe.WRITE_SETTINGS_ACTION_TYPES)
        assertEquals(
            setOf("action.set_brightness", "action.set_rotation"),
            AndroidCapabilityProbe.WRITE_SETTINGS_CAPABILITIES,
        )
        // Non sono tool grezzi del modello né richiedono Shizuku.
        assertTrue(AndroidCapabilityProbe.PRIVILEGED_ACTION_TYPES.none {
            it in AndroidCapabilityProbe.BASE_ACTIONS_041_TYPES + AndroidCapabilityProbe.WRITE_SETTINGS_ACTION_TYPES
        })
    }

    private fun ruleWith(action: Action) = Automation(
        id = AutomationId("r"),
        name = "r",
        createdBy = CreatedBy.USER,
        status = AutomationStatus.ARMED,
        trigger = Trigger.Immediate,
        actions = listOf(action),
    )
}
