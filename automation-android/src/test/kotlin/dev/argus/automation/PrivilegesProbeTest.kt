package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.CapabilityIds
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

/**
 * 0.5.0 (privilegi): il probe riconosce ogni permesso concesso e il root verificato, e pubblica le
 * capacità giuste anche senza Shizuku. Una combinazione per test: nessun privilegio, solo permessi
 * concessi, Shizuku (adb e root: per il probe è lo stesso, conta l'autorizzazione), root su, Sui
 * (arriva dal gateway Shizuku, quindi identico a Shizuku autorizzato).
 */
class PrivilegesProbeTest {
    private fun state(
        shizuku: ShizukuGatewayStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        rootActive: Boolean = false,
        secure: Boolean = false,
        usage: Boolean = false,
        writeSettings: Boolean = false,
        dnd: Boolean = false,
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
        dndPolicyGranted = dnd,
        writeSettingsGranted = writeSettings,
        rootActive = rootActive,
        secureSettingsGranted = secure,
        usageStatsGranted = usage,
    )

    private object EmptyWhitelist : ContactWhitelistStore {
        override suspend fun all(): List<WhitelistedContact> = emptyList()
        override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
        override suspend fun upsert(contact: WhitelistedContact) = Unit
        override suspend fun remove(conversationId: String) = Unit
    }

    private fun probe(state: AndroidCapabilityState, baseTier: Boolean = false) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        EmptyWhitelist,
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
        baseTierActive = baseTier,
    )

    private val privilegedOnly = AndroidCapabilityProbe.PRIVILEGED_CAPABILITIES -
        AndroidCapabilityProbe.PUBLIC_STATE_CAPABILITIES

    @Test
    fun `no privilege at all publishes nothing privileged`() = runTest {
        val probe = probe(state())
        val manifest = probe.probe(DeviceState())
        val available = probe.current().availableCapabilities

        assertFalse(manifest.shizukuAvailable)
        assertTrue(privilegedOnly.none { it in available })
        assertFalse(ActionCapabilities.WRITE_SETTING in available)
        assertFalse(CapabilityIds.STATE_FOREGROUND_APP in available)
        assertFalse(ActionTypeIds.WRITE_SETTING in manifest.availableTools)
        assertTrue(ActionTypeIds.WRITE_SETTING in manifest.unavailableTools)
        assertFalse("write_secure_settings" in manifest.grantedPermissions)
        assertFalse("usage_stats" in manifest.grantedPermissions)
    }

    @Test
    fun `permanent grants alone publish write_setting and foreground app but not the shell`() = runTest {
        val probe = probe(state(secure = true, usage = true))
        val manifest = probe.probe(DeviceState())
        val available = probe.current().availableCapabilities

        assertTrue(ActionCapabilities.WRITE_SETTING in available)
        assertTrue(CapabilityIds.STATE_FOREGROUND_APP in available)
        assertTrue(ActionTypeIds.WRITE_SETTING in manifest.availableTools)
        assertFalse(ActionTypeIds.WRITE_SETTING in manifest.unavailableTools)
        assertTrue("write_secure_settings" in manifest.grantedPermissions)
        assertTrue("usage_stats" in manifest.grantedPermissions)

        // Il resto dello shell privilegiato NON si sblocca coi permessi.
        listOf(
            ActionCapabilities.SET_WIFI,
            ActionCapabilities.SET_BLUETOOTH,
            ActionCapabilities.SET_MOBILE_DATA,
            ActionCapabilities.SET_DARK_MODE,
            ActionCapabilities.RUN_SHELL,
            CapabilityIds.STATE_READER_SETTING,
            CapabilityIds.STATE_READER_DUMPSYS_FIELD,
        ).forEach { assertFalse(it in available, it) }
        assertFalse(manifest.shizukuAvailable)
        assertTrue(ActionTypeIds.RUN_SHELL in manifest.unavailableTools)
        assertTrue(ActionTypeIds.SET_WIFI in manifest.unavailableTools)
    }

    @Test
    fun `write_setting is not published by the usage grant or by modify system settings alone`() = runTest {
        val usageOnly = probe(state(usage = true)).current().availableCapabilities
        assertFalse(ActionCapabilities.WRITE_SETTING in usageOnly)
        assertTrue(CapabilityIds.STATE_FOREGROUND_APP in usageOnly)

        val systemOnly = probe(state(writeSettings = true))
        assertFalse(ActionCapabilities.WRITE_SETTING in systemOnly.current().availableCapabilities)
        assertTrue(ActionCapabilities.SET_BRIGHTNESS in systemOnly.current().availableCapabilities)
    }

    @Test
    fun `brightness and rotation follow modify system settings, not the secure grant`() = runTest {
        val secureOnly = probe(state(secure = true))
        val manifest = secureOnly.probe(DeviceState())
        assertFalse(ActionTypeIds.SET_BRIGHTNESS in manifest.availableTools)
        assertFalse(ActionCapabilities.SET_ROTATION in secureOnly.current().availableCapabilities)
    }

    @Test
    fun `do not disturb access still unlocks the base DND actions without Shizuku`() = runTest {
        val probe = probe(state(dnd = true), baseTier = true)
        val available = probe.current().availableCapabilities
        assertTrue(ActionCapabilities.SET_DND in available)
        assertTrue(ActionCapabilities.SET_RINGER in available)
    }

    @Test
    fun `Shizuku authorized publishes everything privileged, adb or root alike`() = runTest {
        val probe = probe(state(shizuku = ShizukuGatewayStatus.AUTHORIZED))
        val manifest = probe.probe(DeviceState())
        val available = probe.current().availableCapabilities

        assertTrue(manifest.shizukuAvailable)
        assertTrue(available.containsAll(AndroidCapabilityProbe.PRIVILEGED_CAPABILITIES))
        assertTrue(manifest.availableTools.containsAll(AndroidCapabilityProbe.PRIVILEGED_ACTION_TYPES))
        assertEquals(manifest.availableTools.size, manifest.availableTools.toSet().size, "no duplicates")
    }

    @Test
    fun `verified root su counts exactly like Shizuku`() = runTest {
        val shizuku = probe(state(shizuku = ShizukuGatewayStatus.AUTHORIZED))
        val root = probe(state(rootActive = true))

        val rootManifest = root.probe(DeviceState())
        assertTrue(rootManifest.shizukuAvailable)
        assertEquals(shizuku.current().availableCapabilities, root.current().availableCapabilities)
        assertEquals(shizuku.probe(DeviceState()).availableTools, rootManifest.availableTools)
        assertEquals(
            shizuku.probe(DeviceState()).unavailableTools,
            rootManifest.unavailableTools,
        )
    }

    @Test
    fun `root that is off or not verified publishes nothing`() = runTest {
        // rootActive = false è ciò che il probe vede con interruttore spento, verifica in corso o su negato.
        val probe = probe(state(rootActive = false))
        assertTrue(privilegedOnly.none { it in probe.current().availableCapabilities })
        assertFalse(probe.probe(DeviceState()).shizukuAvailable)
    }

    @Test
    fun `permanent grants on top of Shizuku do not duplicate the tool`() = runTest {
        val manifest = probe(state(shizuku = ShizukuGatewayStatus.AUTHORIZED, secure = true, usage = true))
            .probe(DeviceState())
        assertEquals(1, manifest.availableTools.count { it == ActionTypeIds.WRITE_SETTING })
    }

    @Test
    fun `Shizuku stopped but the permanent grants still work`() = runTest {
        val probe = probe(
            state(shizuku = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING, secure = true, usage = true),
        )
        val available = probe.current().availableCapabilities
        assertTrue(ActionCapabilities.WRITE_SETTING in available)
        assertTrue(CapabilityIds.STATE_FOREGROUND_APP in available)
        assertFalse(ActionCapabilities.RUN_SHELL in available)
    }
}
