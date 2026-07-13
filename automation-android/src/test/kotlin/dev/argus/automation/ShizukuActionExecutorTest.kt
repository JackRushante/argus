package dev.argus.automation

import dev.argus.device.DeviceController
import dev.argus.device.DeviceToolException
import dev.argus.device.RingerMode
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.DndMode
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
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
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
        )

        val actions = listOf(
            Action.SetWifi(false),
            Action.SetBluetooth(true),
            Action.SetDnd(DndMode.PRIORITY),
            Action.SetRinger("vibrate"),
            Action.LaunchApp("com.example.app"),
            Action.OpenUrl("https://example.com"),
            Action.ShowNotification("Argus", "Completata"),
            Action.Tap(10, 20),
            Action.InputText("ciao"),
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
                "tap:10,20",
                "text:ciao",
            ),
            tools.calls,
        )
        assertEquals(
            listOf(Triple("Argus", "Completata", ExecutionId("execution-1"))),
            notifications,
        )
    }

    @Test
    fun `generative work is only marked submitted when the lane accepts it`() = runTest {
        val submissions = mutableListOf<Pair<ExecutionId, Action.InvokeLlm>>()
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
        assertEquals(listOf(ExecutionId("execution-1") to action), submissions)
    }

    @Test
    fun `run shell and unavailable reply remain fail closed`() = runTest {
        val tools = RecordingDeviceController()
        val executor = executor(tools)

        assertEquals(
            ActionResult.Failure("live_confirmation_required"),
            executor.execute(Action.RunShell("id"), context),
        )
        assertEquals(
            ActionResult.Failure("unsupported_phase"),
            executor.execute(Action.WhatsAppReply("ciao"), context),
        )
        assertEquals(emptyList(), tools.calls)
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

        val cancelled = executor(ThrowingDeviceController(CancellationException("stop")))
        assertFailsWith<CancellationException> {
            cancelled.execute(Action.SetWifi(true), context)
        }
    }

    private fun executor(
        tools: DeviceController = RecordingDeviceController(),
        lane: GenerativeLane = GenerativeLane { _, _ -> false },
    ) = ShizukuActionExecutor(
        tools = tools,
        notifier = AutomationNotifier { _, _, _ -> },
        generativeLane = lane,
    )
}

private class RecordingDeviceController : DeviceController {
    val calls = mutableListOf<String>()

    override suspend fun setWifi(on: Boolean) { calls += "wifi:$on" }
    override suspend fun setBluetooth(on: Boolean) { calls += "bluetooth:$on" }
    override suspend fun setDnd(mode: DndMode) { calls += "dnd:$mode" }
    override suspend fun setRinger(mode: RingerMode) { calls += "ringer:$mode" }
    override suspend fun launchApp(packageName: String) { calls += "launch:$packageName" }
    override suspend fun openUrl(url: String) { calls += "url:$url" }
    override suspend fun tap(x: Int, y: Int) { calls += "tap:$x,$y" }
    override suspend fun inputText(text: String) { calls += "text:$text" }
}

private class ThrowingDeviceController(private val failure: RuntimeException) : DeviceController {
    private fun fail(): Nothing = throw failure
    override suspend fun setWifi(on: Boolean) = fail()
    override suspend fun setBluetooth(on: Boolean) = fail()
    override suspend fun setDnd(mode: DndMode) = fail()
    override suspend fun setRinger(mode: RingerMode) = fail()
    override suspend fun launchApp(packageName: String) = fail()
    override suspend fun openUrl(url: String) = fail()
    override suspend fun tap(x: Int, y: Int) = fail()
    override suspend fun inputText(text: String) = fail()
}
