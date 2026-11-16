package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.runtime.DeviceState
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Condizioni di contesto e trigger calendario: pubblicati alle condizioni dei loro permessi. */
class AndroidCapabilityProbeConditionsCalendar08Test {
    private val bare = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        shizukuPermissionGranted = false,
        notificationsGranted = true,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = true,
        batteryOptimizationExempt = true,
    )

    private fun probe(state: AndroidCapabilityState) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        object : ContactWhitelistStore {
            override suspend fun all(): List<WhitelistedContact> = emptyList()
            override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
            override suspend fun upsert(contact: WhitelistedContact) = Unit
            override suspend fun remove(conversationId: String) = Unit
        },
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
        baseTierActive = true,
    )

    private val mine = setOf(
        CapabilityIds.STATE_WIFI_CONNECTED,
        CapabilityIds.STATE_BLUETOOTH_CONNECTED,
        CapabilityIds.STATE_HEADSET_CONNECTED,
        CapabilityIds.STATE_CALENDAR_BUSY,
        CapabilityIds.TRIGGER_CALENDAR_EVENT,
    )

    @Test
    fun `none of them is still marked not available yet`() {
        mine.forEach { assertFalse(it in AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES, it) }
    }

    @Test
    fun `wifi link and headsets need no permission`() = runTest {
        val available = probe(bare).current().availableCapabilities
        assertTrue(CapabilityIds.STATE_WIFI_CONNECTED in available)
        assertTrue(CapabilityIds.STATE_HEADSET_CONNECTED in available)
        // Il nome della rete chiede la posizione, come l'identità Wi-Fi del trigger.
        assertFalse(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY in available)
    }

    @Test
    fun `bluetooth and calendar wait for their permissions and say which one is missing`() = runTest {
        val without = probe(bare)
        val available = without.current().availableCapabilities
        assertFalse(CapabilityIds.STATE_BLUETOOTH_CONNECTED in available)
        assertFalse(CapabilityIds.STATE_CALENDAR_BUSY in available)
        assertFalse(CapabilityIds.TRIGGER_CALENDAR_EVENT in available)
        assertEquals(
            mapOf(
                CapabilityIds.STATE_BLUETOOTH_CONNECTED to BlockCause.BLUETOOTH_PERMISSION,
                CapabilityIds.STATE_CALENDAR_BUSY to BlockCause.CALENDAR_PERMISSION,
                CapabilityIds.TRIGGER_CALENDAR_EVENT to BlockCause.CALENDAR_PERMISSION,
            ),
            without.blockCauses(mine),
        )
        assertFalse("calendar_event" in without.probe(DeviceState()).availableTriggers)

        val with = probe(bare.copy(bluetoothConnectGranted = true, readCalendarGranted = true))
        assertTrue(with.current().availableCapabilities.containsAll(mine))
        assertEquals(emptyMap(), with.blockCauses(mine))
        val manifest = with.probe(DeviceState())
        assertTrue("calendar_event" in manifest.availableTriggers)
        assertTrue("read_calendar" in manifest.grantedPermissions)
    }

    @Test
    fun `before android 12 the connected bluetooth devices are not readable`() = runTest {
        val old = probe(bare.copy(androidApi = 30, androidVersion = 11, bluetoothConnectGranted = true))
        assertFalse(CapabilityIds.STATE_BLUETOOTH_CONNECTED in old.current().availableCapabilities)
        assertEquals(BlockCause.OTHER, old.blockCauses(setOf(CapabilityIds.STATE_BLUETOOTH_CONNECTED)).values.single())
    }
}
