package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.CapabilityRequirements
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

/**
 * Capacità delle azioni privilegiate tipizzate 0.5.1: pubblicate solo con un backend privilegiato pronto
 * (Shizuku, Sui o `su` verificato); `set_nfc` anche solo sui telefoni con l'NFC.
 */
class AndroidCapabilityProbePrivileged051Test {
    private val sevenWithoutNfc = listOf(
        ActionTypeIds.APP_CONTROL, ActionTypeIds.SET_AIRPLANE, ActionTypeIds.SET_LOCATION,
        ActionTypeIds.SET_BATTERY_SAVER, ActionTypeIds.LOCK_SCREEN, ActionTypeIds.SCREENSHOT,
        ActionTypeIds.APP_PERMISSION,
    )
    private val all = sevenWithoutNfc + ActionTypeIds.SET_NFC

    private val capabilities = listOf(
        ActionCapabilities.APP_CONTROL, ActionCapabilities.SET_AIRPLANE, ActionCapabilities.SET_LOCATION,
        ActionCapabilities.SET_BATTERY_SAVER, ActionCapabilities.LOCK_SCREEN, ActionCapabilities.SCREENSHOT,
        ActionCapabilities.APP_PERMISSION, ActionCapabilities.SET_NFC,
    )

    private fun state(
        shizuku: ShizukuGatewayStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        rootActive: Boolean = false,
        nfc: Boolean = true,
        secure: Boolean = false,
        usage: Boolean = false,
        writeSettings: Boolean = false,
    ) = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = shizuku,
        shizukuPermissionGranted = shizuku == ShizukuGatewayStatus.AUTHORIZED ||
            shizuku == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
        notificationsGranted = false,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = false,
        batteryOptimizationExempt = false,
        writeSettingsGranted = writeSettings,
        rootActive = rootActive,
        secureSettingsGranted = secure,
        usageStatsGranted = usage,
        nfcPresent = nfc,
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
        baseTierActive = true,
    )

    @Test
    fun `no backend publishes none of the eight and says why`() = runTest {
        val probe = probe(state())
        val manifest = probe.probe(DeviceState())
        val available = probe.current().availableCapabilities

        assertTrue(all.none { it in manifest.availableTools })
        assertTrue(capabilities.none { it in available })
        sevenWithoutNfc.forEach { assertEquals("Shizuku not installed", manifest.unavailableTools[it], it) }
        assertEquals("Shizuku not installed", manifest.unavailableTools[ActionTypeIds.SET_NFC])
    }

    @Test
    fun `permanent grants and special accesses do not unlock the shell actions`() = runTest {
        val probe = probe(state(secure = true, usage = true, writeSettings = true))
        val manifest = probe.probe(DeviceState())

        assertTrue(all.none { it in manifest.availableTools })
        assertTrue(capabilities.none { it in probe.current().availableCapabilities })
    }

    @Test
    fun `Shizuku authorized publishes all eight on a phone with NFC`() = runTest {
        val probe = probe(state(shizuku = ShizukuGatewayStatus.AUTHORIZED))
        val manifest = probe.probe(DeviceState())

        assertTrue(manifest.availableTools.containsAll(all))
        assertTrue(probe.current().availableCapabilities.containsAll(capabilities))
        assertTrue(all.none { it in manifest.unavailableTools })
        assertEquals(manifest.availableTools.size, manifest.availableTools.toSet().size, "no duplicates")
    }

    @Test
    fun `a phone without NFC never publishes set_nfc, whatever the backend`() = runTest {
        listOf(
            state(shizuku = ShizukuGatewayStatus.AUTHORIZED, nfc = false),
            state(rootActive = true, nfc = false),
        ).forEach { state ->
            val probe = probe(state)
            val manifest = probe.probe(DeviceState())
            val available = probe.current().availableCapabilities

            assertTrue(manifest.availableTools.containsAll(sevenWithoutNfc))
            assertFalse(ActionTypeIds.SET_NFC in manifest.availableTools)
            assertFalse(ActionCapabilities.SET_NFC in available)
            assertEquals(AndroidCapabilityProbe.REASON_NO_NFC, manifest.unavailableTools[ActionTypeIds.SET_NFC])
        }
        // Senza NFC e senza backend il motivo è l'hardware, non Shizuku.
        assertEquals(
            AndroidCapabilityProbe.REASON_NO_NFC,
            probe(state(nfc = false)).probe(DeviceState()).unavailableTools[ActionTypeIds.SET_NFC],
        )
    }

    @Test
    fun `verified root su counts exactly like Shizuku, Sui included`() = runTest {
        val shizuku = probe(state(shizuku = ShizukuGatewayStatus.AUTHORIZED))
        val root = probe(state(rootActive = true))

        val rootManifest = root.probe(DeviceState())
        assertTrue(rootManifest.availableTools.containsAll(all))
        assertEquals(shizuku.probe(DeviceState()).availableTools, rootManifest.availableTools)
        assertEquals(shizuku.current().availableCapabilities, root.current().availableCapabilities)
    }

    @Test
    fun `root that is off or not verified publishes nothing`() = runTest {
        val probe = probe(state(rootActive = false))
        assertTrue(all.none { it in probe.probe(DeviceState()).availableTools })
    }

    @Test
    fun `a stopped but authorized Shizuku is a temporary outage, not a revoked rule`() = runTest {
        val probe = probe(state(shizuku = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING))
        val snapshot = probe.current()

        assertTrue(capabilities.none { it in snapshot.availableCapabilities })
        assertTrue(snapshot.transientlyUnavailableCapabilities.containsAll(capabilities))

        val noNfc = probe(state(shizuku = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING, nfc = false)).current()
        assertFalse(ActionCapabilities.SET_NFC in noNfc.transientlyUnavailableCapabilities)
    }

    @Test
    fun `block causes point at Shizuku, and at the hardware for NFC`() = runTest {
        val required = capabilities.toSet()

        val notInstalled = probe(state()).blockCauses(required)
        assertTrue(notInstalled.values.all { it == BlockCause.SHIZUKU_NOT_INSTALLED }, notInstalled.toString())
        assertEquals(required, notInstalled.keys)

        val stopped = probe(state(shizuku = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING)).blockCauses(required)
        assertTrue(stopped.values.all { it == BlockCause.SHIZUKU_NOT_RUNNING }, stopped.toString())

        val noNfc = probe(state(nfc = false)).blockCauses(setOf(ActionCapabilities.SET_NFC))
        assertEquals(BlockCause.OTHER, noNfc[ActionCapabilities.SET_NFC])

        assertEquals(
            emptyMap(),
            probe(state(shizuku = ShizukuGatewayStatus.AUTHORIZED)).blockCauses(required),
        )
    }

    @Test
    fun `a rule using the actions is armable exactly when its capabilities are published`() = runTest {
        val rule = CapabilityRequirements.derive(
            Trigger.Immediate,
            listOf(
                Action.AppControl("com.google.android.youtube", AppControlOp.FORCE_STOP),
                Action.SetNfc(true),
                Action.Screenshot,
            ),
        )
        val withShizuku = probe(state(shizuku = ShizukuGatewayStatus.AUTHORIZED)).current().availableCapabilities
        val without = probe(state()).current().availableCapabilities

        assertTrue(rule.all { it in withShizuku })
        assertEquals(
            setOf(ActionCapabilities.APP_CONTROL, ActionCapabilities.SET_NFC, ActionCapabilities.SCREENSHOT),
            (rule - without).filter { it.startsWith("action.") }.toSet(),
        )
    }

    @Test
    fun `the Shizuku banner knows the new actions`() {
        assertTrue(AndroidCapabilityProbe.SHIZUKU_CAPABILITIES.containsAll(capabilities))
        assertTrue(AndroidCapabilityProbe.PRIVILEGED_ACTION_TYPES.containsAll(sevenWithoutNfc))
        assertEquals(setOf(ActionTypeIds.SET_NFC), AndroidCapabilityProbe.NFC_ACTION_TYPES)
    }
}
