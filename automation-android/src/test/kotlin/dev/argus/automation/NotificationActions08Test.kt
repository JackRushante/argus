package dev.argus.automation

import dev.argus.automation.notification.LiveNotification
import dev.argus.automation.notification.LiveNotificationButton
import dev.argus.automation.notification.LiveNotifications
import dev.argus.automation.notification.ListenerNotificationActionGateway
import dev.argus.automation.notification.NotificationActionGateway
import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyRequest
import dev.argus.device.DeviceController
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.NotificationActionPolicy
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8: `notification_reply` e `notification_action` nel probe, nell'esecutore e sul listener. */
class NotificationActions08Test {
    private val telegram = "org.telegram.messenger"
    private val chat = "shortcut:$telegram:" + "a".repeat(64)

    // --- Probe -----------------------------------------------------------------------------------

    private val granted = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        shizukuPermissionGranted = false,
        notificationsGranted = true,
        notificationListenerGranted = true,
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
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true) },
        baseTierActive = true,
    )

    private val capabilities = setOf(CapabilityIds.ACTION_NOTIFICATION_REPLY, CapabilityIds.ACTION_NOTIFICATION_ACTION)
    private val types = setOf(ActionTypeIds.NOTIFICATION_REPLY, ActionTypeIds.NOTIFICATION_ACTION)

    @Test
    fun `the probe publishes both actions with notification access, without needing shizuku`() = runTest {
        val probe = probe(granted)
        assertTrue(probe.current().availableCapabilities.containsAll(capabilities))
        val manifest = probe.probe(DeviceState())
        assertTrue(manifest.availableTools.containsAll(types), "${manifest.availableTools}")
        assertTrue(capabilities.none { it in AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES })
    }

    @Test
    fun `without notification access both are unavailable and say why`() = runTest {
        val probe = probe(granted.copy(notificationListenerGranted = false))
        assertTrue(probe.current().availableCapabilities.none { it in capabilities })
        val manifest = probe.probe(DeviceState())
        assertTrue(manifest.availableTools.none { it in types })
        types.forEach { assertEquals(AndroidCapabilityProbe.REASON_NOTIFICATION_LISTENER, manifest.unavailableTools[it]) }
        assertEquals(capabilities.associateWith { BlockCause.NOTIFICATION_ACCESS }, probe.blockCauses(capabilities))
    }

    // --- Esecutore -------------------------------------------------------------------------------

    private fun context(event: TriggerEvent) = FireContext(
        event = event,
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private val noTools: DeviceController = Proxy.newProxyInstance(
        DeviceController::class.java.classLoader,
        arrayOf(DeviceController::class.java),
    ) { _, method, _ -> error("non deve toccare il device: ${method.name}") } as DeviceController

    private fun executor(
        replies: NotificationReplyGateway = NotificationReplyGateway { error("nessuna reply attesa") },
        notificationActions: NotificationActionGateway? = null,
    ) = ShizukuActionExecutor(
        tools = noTools,
        notifier = AutomationNotifier { _, _, _ -> error("nessuna notifica") },
        generativeLane = GenerativeLane { _, _ -> error("nessuna AI") },
        replies = replies,
        clipboard = object : ClipboardCopier {
            override fun copy(event: TriggerEvent, extractionRegex: String?) = ActionResult.Success
            override fun copyLiteral(text: String) = ActionResult.Success
        },
        notificationActions = notificationActions,
    )

    private fun posted(pkg: String = telegram, conversationId: String? = chat, key: String? = "0|$pkg|1|null|10") =
        TriggerEvent.NotificationPosted(pkg, conversationId, isGroup = false, text = "ciao", notificationKey = key)

    @Test
    fun `notification_reply goes through the reply gateway to the live conversation`() = runTest {
        var sent: NotificationReplyRequest? = null
        val executor = executor(replies = NotificationReplyGateway { sent = it; NotificationReplyDelivery.Sent })
        assertEquals(ActionResult.Success, executor.execute(Action.NotificationReply("Arrivo"), context(posted())))
        assertEquals(telegram, sent?.packageName)
        assertEquals(chat, sent?.conversationId)
        assertEquals("Arrivo", sent?.text)
    }

    @Test
    fun `each reply type stays on its own apps`() = runTest {
        val executor = executor()
        assertEquals(
            ActionResult.Failure("reply_package_untrusted"),
            executor.execute(Action.NotificationReply("x"), context(posted("com.whatsapp", "jid:42"))),
        )
        assertEquals(
            ActionResult.Failure("reply_package_untrusted"),
            executor.execute(Action.WhatsAppReply("x"), context(posted())),
        )
        assertEquals(
            ActionResult.Failure("reply_event_unverified"),
            executor.execute(Action.NotificationReply("x"), context(TriggerEvent.TimeFired(AutomationId("a"), ApprovalFingerprint("0".repeat(64))))),
        )
    }

    @Test
    fun `notification_action acts on the key of the event that fired`() = runTest {
        var seen: Triple<String, String, Action.NotificationAction>? = null
        val action = Action.NotificationAction(NotificationActionOp.SNOOZE, snoozeMinutes = 10)
        val executor = executor(
            notificationActions = NotificationActionGateway { pkg, key, a ->
                seen = Triple(pkg, key, a)
                ActionResult.Success
            },
        )
        assertEquals(ActionResult.Success, executor.execute(action, context(posted(key = "k1"))))
        assertEquals(Triple(telegram, "k1", action), seen)
        assertEquals(
            ActionResult.Failure(NotificationActionPolicy.CODE_EVENT_UNVERIFIED),
            executor.execute(action, context(posted(key = null))),
        )
        assertEquals(
            ActionResult.Failure(NotificationActionPolicy.CODE_UNAVAILABLE),
            executor().execute(action, context(posted())),
        )
    }

    // --- Listener --------------------------------------------------------------------------------

    private class FakeLive(
        var notification: LiveNotification?,
        override val connected: Boolean = true,
    ) : LiveNotifications {
        val dismissed = mutableListOf<String>()
        val snoozed = mutableListOf<Pair<String, Long>>()
        override fun find(notificationKey: String) = notification
        override fun dismiss(notificationKey: String) { dismissed += notificationKey }
        override fun snooze(notificationKey: String, durationMillis: Long) { snoozed += notificationKey to durationMillis }
    }

    private val pressed = mutableListOf<String>()

    private fun button(title: String?, needsInput: Boolean = false) =
        LiveNotificationButton(title, needsInput) { pressed += title.orEmpty() }

    private fun mail(vararg buttons: LiveNotificationButton) = LiveNotification("com.example.mail", buttons.toList())

    private fun click(label: String) = Action.NotificationAction(NotificationActionOp.CLICK, label = label)

    @Test
    fun `dismiss and snooze act on the live notification`() {
        val live = FakeLive(mail())
        val gateway = ListenerNotificationActionGateway(live)
        assertEquals(ActionResult.Success, gateway.perform("com.example.mail", "k", Action.NotificationAction(NotificationActionOp.DISMISS)))
        assertEquals(listOf("k"), live.dismissed)
        assertEquals(
            ActionResult.Success,
            gateway.perform("com.example.mail", "k", Action.NotificationAction(NotificationActionOp.SNOOZE, snoozeMinutes = 30)),
        )
        assertEquals(listOf("k" to 1_800_000L), live.snoozed)
    }

    @Test
    fun `click presses exactly the button with the approved label`() {
        val gateway = ListenerNotificationActionGateway(FakeLive(mail(button("Rispondi", needsInput = true), button(" Archivia "), button("Elimina"))))
        assertEquals(ActionResult.Success, gateway.perform("com.example.mail", "k", click("archivia")))
        assertEquals(listOf(" Archivia "), pressed)
    }

    @Test
    fun `click fails closed and presses nothing else`() {
        fun code(live: FakeLive, label: String, pkg: String = "com.example.mail") =
            (ListenerNotificationActionGateway(live).perform(pkg, "k", click(label)) as ActionResult.Failure).reason
        assertEquals(NotificationActionPolicy.CODE_BUTTON_NOT_FOUND, code(FakeLive(mail(button("Archivia"))), "Arch"))
        assertEquals(NotificationActionPolicy.CODE_BUTTON_AMBIGUOUS, code(FakeLive(mail(button("OK"), button("ok"))), "OK"))
        assertEquals(NotificationActionPolicy.CODE_BUTTON_NEEDS_INPUT, code(FakeLive(mail(button("Rispondi", needsInput = true))), "Rispondi"))
        assertEquals(NotificationActionPolicy.CODE_NOTIFICATION_GONE, code(FakeLive(null), "OK"))
        assertEquals(NotificationActionPolicy.CODE_UNAVAILABLE, code(FakeLive(mail(button("OK")), connected = false), "OK"))
        assertEquals(NotificationActionPolicy.CODE_EVENT_UNVERIFIED, code(FakeLive(mail(button("OK"))), "OK", pkg = "com.example.other"))
        assertEquals(
            NotificationActionPolicy.CODE_TARGET_FORBIDDEN,
            code(FakeLive(LiveNotification("com.android.systemui", listOf(button("OK")))), "OK", pkg = "com.android.systemui"),
        )
        assertTrue(pressed.isEmpty())
    }

    @Test
    fun `never on argus own notifications`() {
        val live = FakeLive(LiveNotification("dev.argus", listOf(button("Ferma"))))
        val gateway = ListenerNotificationActionGateway(live)
        assertEquals(
            ActionResult.Failure(NotificationActionPolicy.CODE_TARGET_FORBIDDEN),
            gateway.perform("dev.argus", "k", Action.NotificationAction(NotificationActionOp.DISMISS)),
        )
        assertTrue(live.dismissed.isEmpty())
        assertFalse(pressed.isNotEmpty())
        assertNull(live.snoozed.firstOrNull())
    }
}
