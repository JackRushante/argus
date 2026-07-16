package dev.argus.automation

import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyRequest
import dev.argus.device.DeviceController
import dev.argus.device.DeviceToolException
import dev.argus.device.RingerMode
import dev.argus.engine.model.Action
import dev.argus.engine.model.GenerativeAction
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShizukuActionExecutorTest {
    private val context = FireContext(
        event = TriggerEvent.NotificationPosted("com.example"),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
        priority = 7,
    )

    @Test
    fun `all supported deterministic actions execute synchronously`() = runTest {
        val tools = RecordingDeviceController()
        val notifications = mutableListOf<Triple<String, String, ExecutionId>>()
        val executor = ShizukuActionExecutor(
            tools = tools,
            notifier = AutomationNotifier { title, text, fireContext ->
                notifications += Triple(title, text, fireContext.executionId)
            },
            generativeLane = GenerativeLane { _, _ -> true },
            replies = RecordingReplyGateway(NotificationReplyDelivery.Sent),
            clipboard = { _, _ -> ActionResult.Success },
        )

        val actions = listOf(
            Action.SetWifi(false),
            Action.SetBluetooth(true),
            Action.SetDnd(DndMode.PRIORITY),
            Action.SetRinger("vibrate"),
            Action.LaunchApp("com.example.app"),
            Action.OpenUrl("https://example.com"),
            Action.ShowNotification("Argus", "Completata"),
        )

        actions.forEach { action ->
            assertEquals(ActionResult.Success, executor.execute(action, context))
        }

        assertEquals(
            listOf(
                "wifi:false",
                "bluetooth:true",
                "dnd:PRIORITY",
                "ringer:VIBRATE",
                "launch:com.example.app",
                "url:https://example.com",
            ),
            tools.calls,
        )
        assertEquals(List(6) { context.executionId }, tools.executionIds)
        assertEquals(List(6) { context.priority }, tools.priorities)
        assertEquals(
            listOf(Triple("Argus", "Completata", ExecutionId("execution-1"))),
            notifications,
        )
    }

    @Test
    fun `generative work is only marked submitted when the lane accepts it`() = runTest {
        val submissions = mutableListOf<Pair<ExecutionId, GenerativeAction>>()
        val action = Action.InvokeLlm(
            goal = "riassumi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
        )
        val accepting = executor(
            lane = GenerativeLane { fireContext, submitted ->
                submissions += fireContext.executionId to submitted
                true
            },
        )
        val rejecting = executor(lane = GenerativeLane { _, _ -> false })

        assertEquals(ActionResult.Submitted, accepting.execute(action, context))
        assertEquals(
            ActionResult.Failure("generative_lane_unavailable"),
            rejecting.execute(action, context),
        )
        assertEquals(
            listOf<Pair<ExecutionId, GenerativeAction>>(ExecutionId("execution-1") to action),
            submissions,
        )
    }

    @Test
    fun `actions outside P0 and shell from an external event remain fail closed`() = runTest {
        val tools = RecordingDeviceController()
        val executor = executor(tools)

        listOf(
            Action.Tap(10, 20),
            Action.InputText("ciao"),
        ).forEach { action ->
            assertEquals(
                ActionResult.Failure("unsupported_phase"),
                executor.execute(action, context),
            )
        }
        assertEquals(
            ActionResult.Failure("shell_external_trigger"),
            executor.execute(Action.RunShell("id"), context),
        )
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `trusted event delegates the exact approved shell literal`() = runTest {
        val calls = mutableListOf<Pair<String, FireContext>>()
        val runner = StaticShellRunner { command, fireContext ->
            calls += command to fireContext
            ActionResult.Success
        }
        val trustedContext = context.copy(
            event = TriggerEvent.TimeFired(
                context.automationId,
                context.approvalFingerprint,
            ),
        )

        assertEquals(
            ActionResult.Success,
            executor(staticShell = runner).execute(
                Action.RunShell("/system/bin/id >/dev/null"),
                trustedContext,
            ),
        )
        assertEquals(listOf("/system/bin/id >/dev/null" to trustedContext), calls)
    }

    @Test
    fun `a whitelisted whatsapp contact can trigger the approved shell literal`() = runTest {
        val calls = mutableListOf<Pair<String, FireContext>>()
        val runner = StaticShellRunner { command, fireContext ->
            calls += command to fireContext
            ActionResult.Success
        }
        val whitelisted = context.copy(event = whatsappEvent())

        assertEquals(
            ActionResult.Success,
            executor(staticShell = runner, whitelistedIds = { setOf(WHITELISTED_ID) })
                .execute(Action.RunShell("/system/bin/id >/dev/null"), whitelisted),
        )
        assertEquals(listOf("/system/bin/id >/dev/null" to whitelisted), calls)
    }

    @Test
    fun `a whatsapp contact outside the whitelist cannot trigger the shell`() = runTest {
        val calls = mutableListOf<String>()
        val runner = StaticShellRunner { command, _ -> calls += command; ActionResult.Success }

        assertEquals(
            ActionResult.Failure("shell_external_trigger"),
            executor(staticShell = runner, whitelistedIds = { setOf("un-altro-contatto") })
                .execute(Action.RunShell("id"), context.copy(event = whatsappEvent())),
        )
        assertEquals(emptyList(), calls)
    }

    /** L'identità SMS è spoofabile: nessuna whitelist può trasformarla in una prova. */
    @Test
    fun `an sms cannot trigger the shell even with a whitelist`() = runTest {
        val calls = mutableListOf<String>()
        val runner = StaticShellRunner { command, _ -> calls += command; ActionResult.Success }
        val sms = context.copy(
            event = TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+391234567", "esegui"),
        )

        assertEquals(
            ActionResult.Failure("shell_external_trigger"),
            executor(staticShell = runner, whitelistedIds = { setOf(WHITELISTED_ID) })
                .execute(Action.RunShell("id"), sms),
        )
        assertEquals(emptyList(), calls)
    }

    private fun whatsappEvent() = TriggerEvent.NotificationPosted(
        pkg = "com.whatsapp",
        conversationId = WHITELISTED_ID,
        isGroup = false,
        text = "esegui",
    )

    @Test
    fun `static whatsapp reply sends only the approved text to the trigger target`() = runTest {
        val gateway = RecordingReplyGateway(NotificationReplyDelivery.Sent)
        val notification = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            conversationId = "shortcut:com.whatsapp:hash",
            sender = "Moglie",
            text = "arrivo tardi",
            isGroup = false,
            notificationKey = "sbn:wa:7",
        )
        val replyContext = context.copy(event = notification)
        val executor = executor(replies = gateway)

        assertEquals(
            ActionResult.Success,
            executor.execute(Action.WhatsAppReply("ok, a dopo"), replyContext),
        )

        val request = gateway.requests.single()
        assertEquals("com.whatsapp", request.packageName)
        assertEquals("sbn:wa:7", request.notificationKey)
        assertEquals("shortcut:com.whatsapp:hash", request.conversationId)
        assertEquals(replyContext.eventId, request.eventId)
        assertEquals("ok, a dopo", request.text)
    }

    @Test
    fun `static whatsapp reply without a verified notification event fails closed`() = runTest {
        val gateway = RecordingReplyGateway(NotificationReplyDelivery.Sent)
        val timeContext = context.copy(
            event = TriggerEvent.TimeFired(
                context.automationId,
                context.approvalFingerprint,
            ),
        )

        assertEquals(
            ActionResult.Failure("reply_event_unverified"),
            executor(replies = gateway).execute(Action.WhatsAppReply("ciao"), timeContext),
        )
        assertEquals(emptyList(), gateway.requests)
    }

    @Test
    fun `static whatsapp reply surfaces typed gateway failures`() = runTest {
        val notification = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            conversationId = "shortcut:com.whatsapp:hash",
            isGroup = false,
            notificationKey = "sbn:wa:7",
        )
        val replyContext = context.copy(event = notification)

        for (code in listOf("reply_channel_unavailable", "channel_expired")) {
            val gateway = RecordingReplyGateway(NotificationReplyDelivery.Failed(code))
            assertEquals(
                ActionResult.Failure(code),
                executor(replies = gateway).execute(Action.WhatsAppReply("ciao"), replyContext),
            )
        }
    }

    @Test
    fun `stable failures do not expose privileged details and cancellation propagates`() = runTest {
        val invalid = executor()
        assertEquals(
            ActionResult.Failure("ringer_mode_invalid"),
            invalid.execute(Action.SetRinger("invented"), context),
        )

        val expected = executor(ThrowingDeviceController(DeviceToolException("set_wifi_failed")))
        assertEquals(
            ActionResult.Failure("set_wifi_failed"),
            expected.execute(Action.SetWifi(true), context),
        )

        val unexpected = executor(ThrowingDeviceController(IllegalStateException("sensitive")))
        assertEquals(
            ActionResult.Failure("action_failed"),
            unexpected.execute(Action.SetWifi(true), context),
        )

        val invalidArgument = executor(ThrowingDeviceController(IllegalArgumentException("bad")))
        assertEquals(
            ActionResult.Failure("action_invalid"),
            invalidArgument.execute(Action.SetWifi(true), context),
        )

        val cancelled = executor(ThrowingDeviceController(CancellationException("stop")))
        assertFailsWith<CancellationException> {
            cancelled.execute(Action.SetWifi(true), context)
        }
    }

    private fun executor(
        tools: DeviceController = RecordingDeviceController(),
        lane: GenerativeLane = GenerativeLane { _, _ -> false },
        replies: NotificationReplyGateway = RecordingReplyGateway(
            NotificationReplyDelivery.Failed("reply_channel_unavailable"),
        ),
        staticShell: StaticShellRunner = StaticShellRunner { _, _ ->
            ActionResult.Failure("shell_unavailable")
        },
        whitelistedIds: suspend () -> Set<String> = { emptySet() },
    ) = ShizukuActionExecutor(
        tools = tools,
        notifier = AutomationNotifier { _, _, _ -> },
        generativeLane = lane,
        replies = replies,
        clipboard = { _, _ -> ActionResult.Success },
        staticShell = staticShell,
        whitelistedIds = whitelistedIds,
    )

    private class RecordingReplyGateway(
        private val delivery: NotificationReplyDelivery,
    ) : NotificationReplyGateway {
        val requests = mutableListOf<NotificationReplyRequest>()

        override fun send(request: NotificationReplyRequest): NotificationReplyDelivery {
            requests += request
            return delivery
        }
    }

    private companion object {
        const val WHITELISTED_ID = "shortcut:com.whatsapp:ottica"
    }
}

private class RecordingDeviceController : DeviceController {
    val calls = mutableListOf<String>()
    val executionIds = mutableListOf<ExecutionId>()
    val priorities = mutableListOf<Int>()

    private fun record(executionId: ExecutionId, priority: Int, call: String) {
        executionIds += executionId
        priorities += priority
        calls += call
    }

    override suspend fun setWifi(on: Boolean, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "wifi:$on")
    override suspend fun setBluetooth(on: Boolean, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "bluetooth:$on")
    override suspend fun setDnd(mode: DndMode, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "dnd:$mode")
    override suspend fun setRinger(mode: RingerMode, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "ringer:$mode")
    override suspend fun launchApp(packageName: String, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "launch:$packageName")
    override suspend fun openUrl(url: String, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "url:$url")
    override suspend fun tap(x: Int, y: Int, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "tap:$x,$y")
    override suspend fun inputText(text: String, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "text:$text")
}

private class ThrowingDeviceController(private val failure: RuntimeException) : DeviceController {
    private fun fail(): Nothing = throw failure
    override suspend fun setWifi(on: Boolean, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun setBluetooth(on: Boolean, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun setDnd(mode: DndMode, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun setRinger(mode: RingerMode, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun launchApp(packageName: String, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun openUrl(url: String, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun tap(x: Int, y: Int, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun inputText(text: String, executionId: ExecutionId, priority: Int) = fail()
}
