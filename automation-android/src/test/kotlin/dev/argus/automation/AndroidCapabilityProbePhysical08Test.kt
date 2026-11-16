package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.SensorKind
import dev.argus.engine.runtime.DeviceState
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8: i trigger fisici si pubblicano alle loro condizioni (hardware, grant) e dicono perché mancano. */
class AndroidCapabilityProbePhysical08Test {
    private fun state(nfc: Boolean = false, usage: Boolean = false) = AndroidCapabilityState(
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
        usageStatsGranted = usage,
        nfcPresent = nfc,
    )

    private fun accelerometer(kind: SensorKind) = AndroidSensorCapability(
        kind = kind,
        reportingMode = SensorReportingMode.CONTINUOUS,
        wakeUp = false,
        fifoMaxEventCount = 0,
        minDelayUs = 10_000,
        maxDelayUs = 200_000,
        permissionGranted = true,
    )

    private fun probe(
        state: AndroidCapabilityState,
        sensors: AndroidSensorCapabilitySource = EmptyAndroidSensorCapabilitySource,
    ) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        object : ContactWhitelistStore {
            override suspend fun all(): List<WhitelistedContact> = emptyList()
            override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
            override suspend fun upsert(contact: WhitelistedContact) = Unit
            override suspend fun remove(conversationId: String) = Unit
        },
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
        sensors,
        AndroidCapabilityProbe.IMPLEMENTED_SENSOR_KINDS,
    )

    @Test
    fun `next alarm is always armable, nfc only with the hardware, app usage only with usage access`() = runTest {
        val bare = probe(state())
        val available = bare.current().availableCapabilities
        assertTrue(CapabilityIds.TRIGGER_NEXT_ALARM in available)
        assertFalse(CapabilityIds.TRIGGER_NFC_TAG in available)
        assertFalse(CapabilityIds.TRIGGER_APP_USAGE in available)
        val triggers = bare.probe(DeviceState()).availableTriggers
        assertTrue("next_alarm" in triggers)
        assertFalse("nfc_tag" in triggers)
        assertFalse("app_usage" in triggers)
        // Senza NFC il motivo è chiaro, e non è «non disponibile in questa versione».
        assertEquals(
            mapOf(
                CapabilityIds.TRIGGER_NFC_TAG to BlockCause.NFC_UNAVAILABLE,
                CapabilityIds.TRIGGER_APP_USAGE to BlockCause.USAGE_ACCESS,
            ),
            bare.blockCauses(setOf(CapabilityIds.TRIGGER_NFC_TAG, CapabilityIds.TRIGGER_APP_USAGE)),
        )

        val full = probe(state(nfc = true, usage = true))
        assertTrue(full.current().availableCapabilities.containsAll(setOf(CapabilityIds.TRIGGER_NFC_TAG, CapabilityIds.TRIGGER_APP_USAGE)))
        assertTrue(full.probe(DeviceState()).availableTriggers.containsAll(listOf("nfc_tag", "app_usage", "next_alarm")))
    }

    @Test
    fun `shake and flip are published where the phone has an accelerometer`() = runTest {
        val sensors = AndroidSensorCapabilitySource {
            listOf(accelerometer(SensorKind.SHAKE), accelerometer(SensorKind.FLIP_FACE_DOWN))
        }
        val probe = probe(state(), sensors)
        val available = probe.current().availableCapabilities
        assertTrue(CapabilityIds.triggerSensor(SensorKind.SHAKE) in available)
        assertTrue(CapabilityIds.triggerSensor(SensorKind.FLIP_FACE_DOWN) in available)
        val triggers = probe.probe(DeviceState()).availableTriggers
        assertTrue("sensor.shake" in triggers && "sensor.flip_face_down" in triggers)

        val none = probe(state()).current().availableCapabilities
        assertFalse(CapabilityIds.triggerSensor(SensorKind.SHAKE) in none)
        assertEquals(
            mapOf(CapabilityIds.triggerSensor(SensorKind.SHAKE) to BlockCause.SENSOR_UNAVAILABLE),
            probe(state()).blockCauses(setOf(CapabilityIds.triggerSensor(SensorKind.SHAKE))),
        )
    }

    @Test
    fun `none of the physical triggers is still listed as not available yet`() {
        val mine = setOf(
            CapabilityIds.TRIGGER_NFC_TAG,
            CapabilityIds.TRIGGER_NEXT_ALARM,
            CapabilityIds.TRIGGER_APP_USAGE,
            CapabilityIds.triggerSensor(SensorKind.SHAKE),
            CapabilityIds.triggerSensor(SensorKind.FLIP_FACE_DOWN),
        )
        assertTrue(mine.none { it in AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES })
    }
}
