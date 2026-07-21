package dev.argus.automation

import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.automation.base.BaseActionSurface
import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyRequest
import dev.argus.device.DeviceController
import dev.argus.device.DeviceToolException
import dev.argus.device.RingerMode
import dev.argus.engine.brain.ActResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.GenerativeAction
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.ProgramActionResult
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.VarScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
            clipboard = RecordingClipboard(),
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
    fun `resolved shell capture preserves policy check and returns concrete stdout`() = runTest {
        val calls = mutableListOf<String>()
        val runner = object : StaticShellRunner {
            override suspend fun run(command: String, context: FireContext): ActionResult =
                error("flat run non atteso")

            override suspend fun runCaptured(
                command: String,
                context: FireContext,
            ): ProgramActionResult {
                calls += command
                return ProgramActionResult(ActionResult.Success, capturedText = "uid=2000")
            }
        }
        val action = Action.RunShell("id", captureAs = "identity")
        val resolved = (TaintAwareInterpolator().resolve(action, VarScope()) as ActionResolution.Resolved).value
        val trusted = context.copy(
            event = TriggerEvent.TimeFired(context.automationId, context.approvalFingerprint),
        )

        val captured = executor(staticShell = runner).execute(resolved, trusted)
        assertEquals(ActionResult.Success, captured.result)
        assertEquals("uid=2000", captured.capturedText)
        assertEquals(listOf("id"), calls)

        val blocked = executor(staticShell = runner).execute(resolved, context)
        assertEquals(ActionResult.Failure("shell_external_trigger"), blocked.result)
        assertEquals(listOf("id"), calls)
    }

    @Test
    fun `resolved shell capture contains transport failures but propagates cancellation`() = runTest {
        val resolved = (
            TaintAwareInterpolator().resolve(
                Action.RunShell("id", captureAs = "identity"),
                VarScope(),
            ) as ActionResolution.Resolved
            ).value
        val trusted = context.copy(
            event = TriggerEvent.TimeFired(context.automationId, context.approvalFingerprint),
        )

        fun throwingRunner(error: Exception) = object : StaticShellRunner {
            override suspend fun run(command: String, context: FireContext): ActionResult =
                error("flat run non atteso")

            override suspend fun runCaptured(
                command: String,
                context: FireContext,
            ): ProgramActionResult = throw error
        }

        assertEquals(
            ActionResult.Failure("action_failed"),
            executor(staticShell = throwingRunner(IllegalStateException("sensitive")))
                .execute(resolved, trusted)
                .result,
        )
        assertFailsWith<CancellationException> {
            executor(staticShell = throwingRunner(CancellationException("stop")))
                .execute(resolved, trusted)
        }
    }

    @Test
    fun `resolved generative capture routes through the lane and returns concrete text`() = runTest {
        val capture = Action.InvokeLlm(
            goal = "riassumi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            captureAs = "summary",
        )
        val resolved = (
            TaintAwareInterpolator().resolve(capture, VarScope()) as ActionResolution.Resolved
            ).value
        val lane = RecordingLane(ActResult("riassunto concreto", null))

        val result = executor(lane = lane).execute(resolved, context)

        assertEquals(ActionResult.Success, result.result)
        assertEquals("riassunto concreto", result.capturedText)
        assertEquals(capture, lane.awaited.single().first)
        assertTrue(lane.submitted.isEmpty(), "la capture NON deve usare il canale async trySubmit")
    }

    @Test
    fun `resolved generative capture maps a metaError to a failure without capture`() = runTest {
        val capture = Action.InvokeLlm(
            goal = "riassumi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            captureAs = "summary",
        )
        val resolved = (
            TaintAwareInterpolator().resolve(capture, VarScope()) as ActionResolution.Resolved
            ).value
        val lane = RecordingLane(ActResult(null, "budget_exceeded"))

        val result = executor(lane = lane).execute(resolved, context)

        assertEquals(ActionResult.Failure("budget_exceeded"), result.result)
        assertEquals(null, result.capturedText)
    }

    @Test
    fun `resolved generative delivery sink without capture stays fail closed`() = runTest {
        val deliver = Action.InvokeLlm(
            goal = "genera il cambio",
            contextSources = listOf("state"),
            allowedTools = emptyList(),
            replyTargetSender = false,
            deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
            notificationTitle = "Argus",
        )
        val resolved = (
            TaintAwareInterpolator().resolve(deliver, VarScope()) as ActionResolution.Resolved
            ).value
        val lane = RecordingLane(ActResult("non richiesto", null))

        val result = executor(lane = lane).execute(resolved, context)

        assertEquals(ActionResult.Failure("p4_generative_deliver_unavailable"), result.result)
        assertTrue(lane.awaited.isEmpty())
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
        baseActions: AndroidBaseActionExecutor? = null,
        shizukuReady: () -> Boolean = { false },
        clipboard: ClipboardCopier = RecordingClipboard(),
    ) = ShizukuActionExecutor(
        tools = tools,
        notifier = AutomationNotifier { _, _, _ -> },
        generativeLane = lane,
        replies = replies,
        clipboard = clipboard,
        staticShell = staticShell,
        whitelistedIds = whitelistedIds,
        baseActions = baseActions,
        shizukuReady = shizukuReady,
    )

    @Test
    fun `mobile data toggle routes to the privileged device controller`() = runTest {
        val tools = RecordingDeviceController()
        val exec = executor(tools = tools)

        assertEquals(ActionResult.Success, exec.execute(Action.SetMobileData(true), context))
        assertEquals(ActionResult.Success, exec.execute(Action.SetMobileData(false), context))

        assertEquals(listOf("mobile_data:true", "mobile_data:false"), tools.calls)
    }

    @Test
    fun `copy text writes the literal string via copyLiteral`() = runTest {
        val clipboard = RecordingClipboard()
        val exec = executor(clipboard = clipboard)

        assertEquals(ActionResult.Success, exec.execute(Action.CopyText("ordine #4821"), context))

        assertEquals(listOf("ordine #4821"), clipboard.literals)
        // copy_text non passa mai dal percorso event-based copy().
        assertTrue(clipboard.eventCopies.isEmpty())
    }

    @Test
    fun `activity-launch actions prefer privileged am start when shizuku is ready`() = runTest {
        val surface = RecordingBaseSurface()
        val tools = RecordingDeviceController()
        val exec = executor(
            tools = tools,
            baseActions = AndroidBaseActionExecutor(surface),
            shizukuReady = { true },
        )

        assertEquals(ActionResult.Success, exec.execute(Action.SetAlarm(7, 30, "Palestra"), context))
        assertEquals(ActionResult.Success, exec.execute(Action.SetTimer(300), context))
        assertEquals(ActionResult.Success, exec.execute(Action.OpenSettingsScreen(SettingsScreen.WIFI), context))
        assertEquals(ActionResult.Success, exec.execute(Action.LaunchApp("com.example.app"), context))
        assertEquals(ActionResult.Success, exec.execute(Action.OpenUrl("https://example.com"), context))

        // Con Shizuku pronto le activity-launch passano dal privilegiato `am start`, mai dall'Intent BASE.
        assertEquals(
            listOf(
                "alarm:7:30:Palestra:true",
                "timer:300:null:true",
                "settings:WIFI:null",
                "launch:com.example.app",
                "url:https://example.com",
            ),
            tools.calls,
        )
        assertTrue(surface.alarms.isEmpty() && surface.timers.isEmpty())
        assertTrue(surface.settingsScreens.isEmpty() && surface.launched.isEmpty() && surface.opened.isEmpty())
    }

    @Test
    fun `manager actions stay on the base executor even when shizuku is ready`() = runTest {
        val surface = RecordingBaseSurface()
        val tools = RecordingDeviceController()
        val exec = executor(
            tools = tools,
            baseActions = AndroidBaseActionExecutor(surface),
            shizukuReady = { true },
        )

        assertEquals(ActionResult.Success, exec.execute(Action.SetDnd(DndMode.PRIORITY), context))
        // level è una PERCENTUALE: 100% mappa sul max reale dello stream (RecordingBaseSurface: 15).
        assertEquals(ActionResult.Success, exec.execute(Action.SetVolume(VolumeStream.MEDIA, 100), context))
        assertEquals(ActionResult.Success, exec.execute(Action.SetFlashlight(on = true), context))
        assertEquals(ActionResult.Success, exec.execute(Action.Vibrate(durationMs = 200), context))

        // Le azioni Manager funzionano già da background: nessun motivo per lo shell privilegiato.
        assertEquals(listOf(DndMode.PRIORITY), surface.dndModes)
        assertEquals(listOf("MEDIA:15"), surface.volumes)
        assertEquals(listOf(true), surface.torch)
        assertEquals(listOf(200), surface.vibrations)
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `base actions route to the normal-api executor instead of shizuku`() = runTest {
        val surface = RecordingBaseSurface()
        val tools = RecordingDeviceController()
        val exec = executor(tools = tools, baseActions = AndroidBaseActionExecutor(surface))

        assertEquals(ActionResult.Success, exec.execute(Action.SetDnd(DndMode.PRIORITY), context))
        assertEquals(ActionResult.Success, exec.execute(Action.SetRinger("normal"), context))
        assertEquals(ActionResult.Success, exec.execute(Action.LaunchApp("com.example.app"), context))
        assertEquals(ActionResult.Success, exec.execute(Action.OpenUrl("https://example.com"), context))

        assertEquals(listOf(DndMode.PRIORITY), surface.dndModes)
        assertEquals(listOf(RingerMode.NORMAL), surface.ringerModes)
        assertEquals(listOf("com.example.app"), surface.launched)
        assertEquals(listOf("https://example.com"), surface.opened)
        // Nessuna azione base passa dallo shell privilegiato quando il base executor è presente.
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `privileged actions still go through shizuku when a base executor is present`() = runTest {
        val surface = RecordingBaseSurface()
        val tools = RecordingDeviceController()
        val exec = executor(tools = tools, baseActions = AndroidBaseActionExecutor(surface))

        assertEquals(ActionResult.Success, exec.execute(Action.SetWifi(true), context))
        assertEquals(ActionResult.Success, exec.execute(Action.SetBluetooth(false), context))

        assertEquals(listOf("wifi:true", "bluetooth:false"), tools.calls)
        assertTrue(surface.dndModes.isEmpty() && surface.launched.isEmpty())
    }

    @Test
    fun `invalid ringer mode fails before reaching the base executor`() = runTest {
        val surface = RecordingBaseSurface()
        val exec = executor(baseActions = AndroidBaseActionExecutor(surface))
        assertEquals(
            ActionResult.Failure("ringer_mode_invalid"),
            exec.execute(Action.SetRinger("invented"), context),
        )
        assertTrue(surface.ringerModes.isEmpty())
    }

    @Test
    fun `base executor grant failures surface as typed failures`() = runTest {
        val surface = RecordingBaseSurface(dndGranted = false)
        val exec = executor(baseActions = AndroidBaseActionExecutor(surface))
        assertEquals(
            ActionResult.Failure("dnd_policy_unavailable"),
            exec.execute(Action.SetDnd(DndMode.TOTAL), context),
        )
    }

    @Test
    fun `write setting dispatches through the privileged shell with literal namespace key and value`() = runTest {
        val tools = RecordingDeviceController()
        val exec = executor(tools = tools)

        assertEquals(
            ActionResult.Success,
            exec.execute(
                Action.WriteSetting(dev.argus.engine.model.SettingNamespace.SECURE, "adb_enabled", "1"),
                context,
            ),
        )
        assertEquals(listOf("setting:secure:adb_enabled=1"), tools.calls)
        assertEquals(listOf(context.executionId), tools.executionIds)
        assertEquals(listOf(context.priority), tools.priorities)
    }

    @Test
    fun `write setting surfaces the typed device failure code`() = runTest {
        val exec = executor(ThrowingDeviceController(DeviceToolException("write_setting_failed")))
        assertEquals(
            ActionResult.Failure("write_setting_failed"),
            exec.execute(
                Action.WriteSetting(dev.argus.engine.model.SettingNamespace.GLOBAL, "airplane_mode_on", "1"),
                context,
            ),
        )
    }

    @Test
    fun `set alarm and set timer route to the base executor`() = runTest {
        val surface = RecordingBaseSurface()
        val tools = RecordingDeviceController()
        val exec = executor(tools = tools, baseActions = AndroidBaseActionExecutor(surface))

        assertEquals(
            ActionResult.Success,
            exec.execute(Action.SetAlarm(hour = 7, minute = 30, label = "Palestra"), context),
        )
        assertEquals(
            ActionResult.Success,
            exec.execute(Action.SetTimer(seconds = 300), context),
        )
        assertEquals(listOf("7:30:Palestra:true"), surface.alarms)
        assertEquals(listOf("300:null:true"), surface.timers)
        // Le sveglie/timer sono BASE: non toccano mai lo shell privilegiato.
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `manager pack actions route to the base executor and never touch shizuku`() = runTest {
        val surface = RecordingBaseSurface()
        val tools = RecordingDeviceController()
        val exec = executor(tools = tools, baseActions = AndroidBaseActionExecutor(surface))

        // level è una PERCENTUALE: 100% mappa sul max reale dello stream (RecordingBaseSurface: 15).
        assertEquals(
            ActionResult.Success,
            exec.execute(Action.SetVolume(VolumeStream.MEDIA, level = 100), context),
        )
        assertEquals(ActionResult.Success, exec.execute(Action.SetFlashlight(on = true), context))
        assertEquals(
            ActionResult.Success,
            exec.execute(Action.OpenSettingsScreen(SettingsScreen.WIFI), context),
        )
        assertEquals(ActionResult.Success, exec.execute(Action.Vibrate(durationMs = 200), context))

        assertEquals(listOf("MEDIA:15"), surface.volumes)
        assertEquals(listOf(true), surface.torch)
        assertEquals(listOf("WIFI:null"), surface.settingsScreens)
        assertEquals(listOf(200), surface.vibrations)
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `manager pack actions are base-only and fail clean without a base executor`() = runTest {
        val exec = executor(baseActions = null)
        listOf(
            Action.SetVolume(VolumeStream.RING, level = 3),
            Action.SetFlashlight(on = false),
            Action.OpenSettingsScreen(SettingsScreen.SETTINGS),
            Action.Vibrate(durationMs = 100),
        ).forEach { action ->
            assertEquals(
                ActionResult.Failure("base_executor_unavailable"),
                exec.execute(action, context),
            )
        }
    }

    @Test
    fun `set alarm is base-only and fails clean without a base executor`() = runTest {
        val exec = executor(baseActions = null)
        assertEquals(
            ActionResult.Failure("base_executor_unavailable"),
            exec.execute(Action.SetAlarm(hour = 7, minute = 0), context),
        )
        assertEquals(
            ActionResult.Failure("base_executor_unavailable"),
            exec.execute(Action.SetTimer(seconds = 60), context),
        )
    }

    @Test
    fun `set alarm rejects an invalid range through the base executor`() = runTest {
        val surface = RecordingBaseSurface()
        val exec = executor(baseActions = AndroidBaseActionExecutor(surface))
        assertEquals(
            ActionResult.Failure("action_invalid"),
            exec.execute(Action.SetAlarm(hour = 24, minute = 0), context),
        )
        assertTrue(surface.alarms.isEmpty())
    }

    private class RecordingLane(private val result: ActResult) : GenerativeLane {
        val submitted = mutableListOf<Pair<FireContext, GenerativeAction>>()
        val awaited = mutableListOf<Pair<Action.InvokeLlm, List<RuntimeDataBinding>>>()

        override fun trySubmit(context: FireContext, action: GenerativeAction): Boolean {
            submitted += context to action
            return true
        }

        override suspend fun submitAndAwait(
            context: FireContext,
            action: Action.InvokeLlm,
            runtimeData: List<RuntimeDataBinding>,
        ): ActResult {
            awaited += action to runtimeData
            return result
        }
    }

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
    override suspend fun setMobileData(on: Boolean, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "mobile_data:$on")
    override suspend fun setDnd(mode: DndMode, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "dnd:$mode")
    override suspend fun setRinger(mode: RingerMode, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "ringer:$mode")
    override suspend fun launchApp(packageName: String, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "launch:$packageName")
    override suspend fun openUrl(url: String, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "url:$url")
    override suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
        skipUi: Boolean,
        executionId: ExecutionId,
        priority: Int,
    ) = record(executionId, priority, "alarm:$hour:$minute:$label:$skipUi")
    override suspend fun setTimer(
        seconds: Int,
        label: String?,
        skipUi: Boolean,
        executionId: ExecutionId,
        priority: Int,
    ) = record(executionId, priority, "timer:$seconds:$label:$skipUi")
    override suspend fun openSettingsScreen(
        screen: SettingsScreen,
        pkg: String?,
        executionId: ExecutionId,
        priority: Int,
    ) = record(executionId, priority, "settings:$screen:$pkg")
    override suspend fun tap(x: Int, y: Int, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "tap:$x,$y")
    override suspend fun inputText(text: String, executionId: ExecutionId, priority: Int) =
        record(executionId, priority, "text:$text")
    override suspend fun writeSetting(
        namespace: dev.argus.engine.model.SettingNamespace,
        key: String,
        value: String,
        executionId: ExecutionId,
        priority: Int,
    ) = record(executionId, priority, "setting:${namespace.name.lowercase()}:$key=$value")
}

private class RecordingClipboard : ClipboardCopier {
    val literals = mutableListOf<String>()
    val eventCopies = mutableListOf<String?>()
    override fun copy(event: TriggerEvent, extractionRegex: String?): ActionResult {
        eventCopies += extractionRegex
        return ActionResult.Success
    }
    override fun copyLiteral(text: String): ActionResult {
        literals += text
        return ActionResult.Success
    }
}

private class ThrowingDeviceController(private val failure: RuntimeException) : DeviceController {
    private fun fail(): Nothing = throw failure
    override suspend fun setWifi(on: Boolean, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun setBluetooth(on: Boolean, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun setMobileData(on: Boolean, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun setDnd(mode: DndMode, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun setRinger(mode: RingerMode, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun launchApp(packageName: String, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun openUrl(url: String, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
        skipUi: Boolean,
        executionId: ExecutionId,
        priority: Int,
    ) = fail()
    override suspend fun setTimer(
        seconds: Int,
        label: String?,
        skipUi: Boolean,
        executionId: ExecutionId,
        priority: Int,
    ) = fail()
    override suspend fun openSettingsScreen(
        screen: SettingsScreen,
        pkg: String?,
        executionId: ExecutionId,
        priority: Int,
    ) = fail()
    override suspend fun tap(x: Int, y: Int, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun inputText(text: String, executionId: ExecutionId, priority: Int) = fail()
    override suspend fun writeSetting(
        namespace: dev.argus.engine.model.SettingNamespace,
        key: String,
        value: String,
        executionId: ExecutionId,
        priority: Int,
    ) = fail()
}

private class RecordingBaseSurface(private val dndGranted: Boolean = true) : BaseActionSurface {
    val dndModes = mutableListOf<DndMode>()
    val ringerModes = mutableListOf<RingerMode>()
    val launched = mutableListOf<String>()
    val opened = mutableListOf<String>()
    val alarms = mutableListOf<String>()
    val timers = mutableListOf<String>()
    val volumes = mutableListOf<String>()
    val torch = mutableListOf<Boolean>()
    val settingsScreens = mutableListOf<String>()
    val vibrations = mutableListOf<Int>()
    override fun isDndPolicyGranted(): Boolean = dndGranted
    override fun setInterruptionFilter(mode: DndMode) { dndModes += mode }
    override fun setRingerMode(mode: RingerMode) { ringerModes += mode }
    override fun launchPackage(pkg: String): Boolean { launched += pkg; return true }
    override fun openHttpUrl(url: String) { opened += url }
    override fun setAlarm(hour: Int, minute: Int, label: String?, skipUi: Boolean): Boolean {
        alarms += "$hour:$minute:$label:$skipUi"; return true
    }
    override fun setTimer(seconds: Int, label: String?, skipUi: Boolean): Boolean {
        timers += "$seconds:$label:$skipUi"; return true
    }
    override fun maxStreamVolume(stream: VolumeStream): Int = 15
    override fun setStreamVolume(stream: VolumeStream, level: Int) { volumes += "$stream:$level" }
    override fun setTorchMode(on: Boolean): Boolean { torch += on; return true }
    override fun openSettingsScreen(screen: SettingsScreen, pkg: String?): Boolean {
        settingsScreens += "$screen:$pkg"; return true
    }
    override fun vibrateOneShot(durationMs: Int): Boolean { vibrations += durationMs; return true }
}
