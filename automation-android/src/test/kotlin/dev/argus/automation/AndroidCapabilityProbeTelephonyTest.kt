package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.ActionTypeIds
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

/** 0.8: SMS, chiamate, Intent e filtro chiamate si pubblicano solo col loro permesso (o ruolo). */
class AndroidCapabilityProbeTelephonyTest {
    private val base = AndroidCapabilityState(
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
        readPhoneStateGranted = true,
        readCallLogGranted = true,
        telephonyPresent = true,
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

    private val telephonyCapabilities = setOf(
        CapabilityIds.ACTION_SEND_SMS,
        CapabilityIds.ACTION_CALL_NUMBER,
        CapabilityIds.ACTION_SCREEN_CALL,
        CapabilityIds.ACTION_SCREEN_CALL_CONTACTS,
        CapabilityIds.ACTION_SEND_INTENT_SERVICE,
    )

    @Test
    fun `without permissions only send_intent is published and the model learns where to turn the rest on`() = runTest {
        val probe = probe(base)
        val available = probe.current().availableCapabilities
        assertTrue(CapabilityIds.ACTION_SEND_INTENT in available)
        assertTrue(telephonyCapabilities.none { it in available }, "$available")
        val manifest = probe.probe(DeviceState())
        assertTrue(ActionTypeIds.SEND_INTENT in manifest.availableTools)
        assertEquals(AndroidCapabilityProbe.REASON_SEND_SMS, manifest.unavailableTools[ActionTypeIds.SEND_SMS])
        assertEquals(AndroidCapabilityProbe.REASON_CALL_PHONE, manifest.unavailableTools[ActionTypeIds.CALL_NUMBER])
        assertEquals(AndroidCapabilityProbe.REASON_CALL_SCREENING, manifest.unavailableTools[ActionTypeIds.SCREEN_CALL])
        assertEquals(
            mapOf(
                CapabilityIds.ACTION_SEND_SMS to BlockCause.SEND_SMS_PERMISSION,
                CapabilityIds.ACTION_CALL_NUMBER to BlockCause.CALL_PHONE_PERMISSION,
                CapabilityIds.ACTION_SCREEN_CALL to BlockCause.CALL_SCREENING_ROLE,
                CapabilityIds.ACTION_SCREEN_CALL_CONTACTS to BlockCause.CONTACTS_PERMISSION,
                CapabilityIds.ACTION_SEND_INTENT_SERVICE to BlockCause.SHIZUKU_NOT_INSTALLED,
            ),
            probe.blockCauses(telephonyCapabilities),
        )
    }

    @Test
    fun `each grant publishes its own action`() = runTest {
        val granted = base.copy(
            sendSmsGranted = true,
            callPhoneGranted = true,
            callScreeningRoleHeld = true,
            readContactsGranted = true,
            shizukuStatus = ShizukuGatewayStatus.AUTHORIZED,
            shizukuPermissionGranted = true,
        )
        val probe = probe(granted)
        assertEquals(telephonyCapabilities, probe.current().availableCapabilities.intersect(telephonyCapabilities))
        val tools = probe.probe(DeviceState()).availableTools
        listOf(ActionTypeIds.SEND_SMS, ActionTypeIds.CALL_NUMBER, ActionTypeIds.SEND_INTENT, ActionTypeIds.SCREEN_CALL)
            .forEach { assertTrue(it in tools, it) }
    }

    @Test
    fun `a phone without telephony never offers sms, calls or screening`() = runTest {
        val noPhone = base.copy(telephonyPresent = false, sendSmsGranted = true, callPhoneGranted = true, callScreeningRoleHeld = true)
        val probe = probe(noPhone)
        val manifest = probe.probe(DeviceState())
        listOf(ActionTypeIds.SEND_SMS, ActionTypeIds.CALL_NUMBER, ActionTypeIds.SCREEN_CALL).forEach {
            assertFalse(it in manifest.availableTools, it)
            assertEquals(AndroidCapabilityProbe.REASON_NO_TELEPHONY, manifest.unavailableTools[it])
        }
        assertEquals(
            BlockCause.NO_TELEPHONY,
            probe.blockCauses(setOf(CapabilityIds.ACTION_SEND_SMS))[CapabilityIds.ACTION_SEND_SMS],
        )
    }
}
