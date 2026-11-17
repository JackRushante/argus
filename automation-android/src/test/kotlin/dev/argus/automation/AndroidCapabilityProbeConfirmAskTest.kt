package dev.argus.automation

import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.device.DeviceController
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.shizuku.ShizukuGatewayStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8: `confirm` e `ask` sono notifiche dell'app: disponibili con il permesso delle notifiche, non senza. */
class AndroidCapabilityProbeConfirmAskTest {
    private fun state(notifications: Boolean) = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        shizukuPermissionGranted = false,
        notificationsGranted = notifications,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = true,
        batteryOptimizationExempt = true,
        receiveSmsGranted = false,
        readPhoneStateGranted = false,
        readCallLogGranted = false,
        bluetoothConnectGranted = false,
        activityRecognitionGranted = false,
        dndPolicyGranted = false,
        multiSim = false,
        writeSettingsGranted = false,
        rootActive = false,
        secureSettingsGranted = false,
        usageStatsGranted = false,
        nfcPresent = false,
        accessibilityEnabled = false,
    )

    private fun probe(notifications: Boolean) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state(notifications) },
        object : ContactWhitelistStore {
            override suspend fun all(): List<WhitelistedContact> = emptyList()
            override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
            override suspend fun upsert(contact: WhitelistedContact) = Unit
            override suspend fun remove(conversationId: String) = Unit
        },
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
        baseTierActive = true,
    )

    private val ours = setOf(CapabilityIds.RULE_CONFIRM, CapabilityIds.ACTION_ASK)

    @Test
    fun `with notifications allowed confirm and ask are available and announced`() = runTest {
        val probe = probe(notifications = true)
        assertTrue(probe.current().availableCapabilities.containsAll(ours))
        assertTrue(ActionTypeIds.ASK in probe.probe(DeviceState()).availableTools)
        assertFalse(ours.any { it in AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES })
    }

    @Test
    fun `without notifications they are blocked by the notification permission`() = runTest {
        val probe = probe(notifications = false)
        assertTrue(probe.current().availableCapabilities.none { it in ours })
        assertEquals(ours.associateWith { BlockCause.NOTIFICATIONS_PERMISSION }, probe.blockCauses(ours))
        assertTrue(ActionTypeIds.ASK in probe.probe(DeviceState()).unavailableTools)
    }

    @Test
    fun `the leaf executor never runs an ask`() = runTest {
        val noTools: DeviceController = Proxy.newProxyInstance(
            DeviceController::class.java.classLoader,
            arrayOf(DeviceController::class.java),
        ) { _, method, _ -> error("ask non tocca il device: ${method.name}") } as DeviceController
        val executor = ShizukuActionExecutor(
            tools = noTools,
            notifier = AutomationNotifier { _, _, _ -> error("ask non passa dal notifier delle regole") },
            generativeLane = GenerativeLane { _, _ -> error("ask non chiama l'AI") },
            replies = NotificationReplyGateway { NotificationReplyDelivery.Failed("reply_channel_unavailable") },
            clipboard = object : ClipboardCopier {
                override fun copy(event: TriggerEvent, extractionRegex: String?) = ActionResult.Success
                override fun copyLiteral(text: String) = ActionResult.Success
            },
            shizukuReady = { true },
        )
        val context = FireContext(
            event = TriggerEvent.NotificationPosted("com.example"),
            state = DeviceState(),
            automationId = AutomationId("automation-1"),
            approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
            eventId = TriggerEventId("event-1"),
            executionId = ExecutionId("execution-1"),
            actionIndex = 0,
        )
        assertEquals(
            ActionResult.Failure(ASK_OUTSIDE_PROGRAM),
            executor.execute(Action.Ask("Esco?", listOf("Sì", "No"), "answer"), context),
        )
    }
}
