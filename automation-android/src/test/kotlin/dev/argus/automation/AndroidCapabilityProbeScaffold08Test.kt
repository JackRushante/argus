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
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.StoredOp
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

/**
 * Vocabolario 0.8 spento: anche con ogni permesso concesso il probe non annuncia nessun tipo nuovo al
 * modello, non lo rende armabile e ne dà la causa «non disponibile in questa versione».
 */
class AndroidCapabilityProbeScaffold08Test {
    private val everything = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.AUTHORIZED,
        shizukuPermissionGranted = true,
        notificationsGranted = true,
        notificationListenerGranted = true,
        foregroundLocationGranted = true,
        backgroundLocationGranted = true,
        exactAlarmsGranted = true,
        batteryOptimizationExempt = true,
        receiveSmsGranted = true,
        readPhoneStateGranted = true,
        readCallLogGranted = true,
        bluetoothConnectGranted = true,
        activityRecognitionGranted = true,
        dndPolicyGranted = true,
        multiSim = true,
        writeSettingsGranted = true,
        rootActive = true,
        secureSettingsGranted = true,
        usageStatsGranted = true,
        nfcPresent = true,
        accessibilityEnabled = true,
    )

    private val probe = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { everything },
        object : ContactWhitelistStore {
            override suspend fun all(): List<WhitelistedContact> = emptyList()
            override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
            override suspend fun upsert(contact: WhitelistedContact) = Unit
            override suspend fun remove(conversationId: String) = Unit
        },
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true) },
        baseTierActive = true,
    )

    private val newActionTypes = setOf(
        ActionTypeIds.ASK,
        ActionTypeIds.SET_STORED,
        ActionTypeIds.SET_USER_MODE,
        ActionTypeIds.NOTIFICATION_REPLY,
        ActionTypeIds.NOTIFICATION_ACTION,
    )

    private val newTriggers = setOf(
        "calendar_event", "nfc_tag", "next_alarm", "app_usage", "user_mode_changed",
        "sensor.${SensorKind.SHAKE.wireName}", "sensor.${SensorKind.FLIP_FACE_DOWN.wireName}",
    )

    @Test
    fun `nothing new is announced to the model, not even as unavailable`() = runTest {
        val manifest = probe.probe(DeviceState())
        assertTrue(manifest.availableTools.none { it in newActionTypes }, "${manifest.availableTools}")
        assertTrue(manifest.unavailableTools.keys.none { it in newActionTypes }, "${manifest.unavailableTools}")
        assertTrue(manifest.availableTriggers.none { it in newTriggers }, "${manifest.availableTriggers}")
        assertTrue(manifest.availableTriggers.isNotEmpty())
    }

    @Test
    fun `no new capability is available and each one says why`() = runTest {
        val available = probe.current().availableCapabilities
        AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES.forEach { capability ->
            assertFalse(capability in available, capability)
        }
        assertEquals(
            AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES.associateWith { BlockCause.NOT_AVAILABLE_YET },
            probe.blockCauses(AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES),
        )
        assertTrue(CapabilityIds.triggerSensor(SensorKind.SHAKE) in AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES)
    }

    @Test
    fun `the executor fails closed on every new action with the shared code`() = runTest {
        val context = FireContext(
            event = TriggerEvent.NotificationPosted("com.example"),
            state = DeviceState(),
            automationId = AutomationId("automation-1"),
            approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
            eventId = TriggerEventId("event-1"),
            executionId = ExecutionId("execution-1"),
            actionIndex = 0,
        )
        val noTools: DeviceController = Proxy.newProxyInstance(
            DeviceController::class.java.classLoader,
            arrayOf(DeviceController::class.java),
        ) { _, method, _ -> error("un'azione spenta non deve toccare il device: ${method.name}") } as DeviceController
        val executor = ShizukuActionExecutor(
            tools = noTools,
            notifier = AutomationNotifier { _, _, _ -> error("un'azione spenta non notifica") },
            generativeLane = GenerativeLane { _, _ -> error("un'azione spenta non chiama l'AI") },
            replies = NotificationReplyGateway { NotificationReplyDelivery.Failed("reply_channel_unavailable") },
            clipboard = object : ClipboardCopier {
                override fun copy(event: TriggerEvent, extractionRegex: String?) = ActionResult.Success
                override fun copyLiteral(text: String) = ActionResult.Success
            },
            shizukuReady = { true },
        )
        val actions = listOf(
            Action.Ask("?", listOf("a"), "answer"),
            Action.SetStored("k", StoredOp.CLEAR),
            Action.SetUserMode("lavoro"),
            Action.NotificationReply("ok"),
            Action.NotificationAction(NotificationActionOp.DISMISS),
            Action.HttpRequest(HttpMethod.GET, "https://example.org", captureAs = "body"),
            Action.InvokeLlm("g", emptyList(), emptyList(), false, output = OutputSpec(OutputKind.BOOLEAN)),
        )
        actions.forEach { action ->
            assertEquals(ActionResult.Failure("not_available_yet"), executor.execute(action, context), "$action")
        }
    }
}
