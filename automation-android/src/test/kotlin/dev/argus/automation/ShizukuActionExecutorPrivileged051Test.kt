package dev.argus.automation

import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.privileged.PrivilegedActionEnvironment
import dev.argus.automation.privileged.PrivilegedActionRunner
import dev.argus.automation.privileged.ScreenshotStore
import dev.argus.device.DeviceController
import dev.argus.device.DeviceToolException
import dev.argus.device.PrivilegedActionController
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.VarScope
import dev.argus.shizuku.ShizukuShellException
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Cablaggio delle azioni privilegiate 0.5.1 nell'esecutore: solo con un backend pronto, solo dal
 * runner, mai dai comandi dei toggle esistenti, e ogni errore dei comandi diventa un codice del Log.
 */
class ShizukuActionExecutorPrivileged051Test {
    private val context = FireContext(
        event = TriggerEvent.NotificationPosted("com.example"),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )
    private val youtube = "com.google.android.youtube"
    private val mic = "android.permission.RECORD_AUDIO"

    /** I comandi dei toggle esistenti non devono servire a queste azioni. */
    private val noTools: DeviceController = Proxy.newProxyInstance(
        DeviceController::class.java.classLoader,
        arrayOf(DeviceController::class.java),
    ) { _, method, _ -> error("le azioni 0.5.1 non passano da DeviceController: ${method.name}") } as DeviceController

    private class RecordingTools(private val failWith: Throwable? = null) : PrivilegedActionController {
        val calls = mutableListOf<String>()
        private fun record(call: String) {
            calls += call
            failWith?.let { throw it }
        }

        override suspend fun appControl(pkg: String, op: AppControlOp, executionId: ExecutionId, priority: Int) =
            record("app_control $pkg $op")

        override suspend fun setAirplane(on: Boolean, executionId: ExecutionId, priority: Int) = record("airplane $on")
        override suspend fun setLocation(on: Boolean, executionId: ExecutionId, priority: Int) = record("location $on")
        override suspend fun setNfc(on: Boolean, executionId: ExecutionId, priority: Int) = record("nfc $on")
        override suspend fun setBatterySaver(on: Boolean, executionId: ExecutionId, priority: Int) =
            record("battery_saver $on")

        override suspend fun lockScreen(executionId: ExecutionId, priority: Int) = record("lock")
        override suspend fun screenshot(): ByteArray {
            record("screenshot")
            return byteArrayOf(1)
        }

        override suspend fun appPermission(
            pkg: String,
            permission: String,
            grant: Boolean,
            executionId: ExecutionId,
            priority: Int,
        ) = record("permission $pkg $permission $grant")
    }

    private val store = ScreenshotStore { _, _ -> true }
    private val environment = object : PrivilegedActionEnvironment {
        override fun hasNfc() = true
        override fun launcherPackages() = setOf("com.example.launcher")
    }

    private fun executor(
        tools: PrivilegedActionController? = null,
        ready: Boolean = true,
    ) = ShizukuActionExecutor(
        tools = noTools,
        notifier = AutomationNotifier { _, _, _ -> },
        generativeLane = GenerativeLane { _, _ -> false },
        replies = NotificationReplyGateway { NotificationReplyDelivery.Failed("reply_channel_unavailable") },
        clipboard = object : ClipboardCopier {
            override fun copy(event: TriggerEvent, extractionRegex: String?) = ActionResult.Success
            override fun copyLiteral(text: String) = ActionResult.Success
        },
        shizukuReady = { ready },
        privilegedActions = tools?.let { PrivilegedActionRunner(it, store, environment) },
    )

    private val all: List<Action> = listOf(
        Action.AppControl(youtube, AppControlOp.FORCE_STOP),
        Action.SetAirplane(true),
        Action.SetLocation(false),
        Action.SetNfc(true),
        Action.SetBatterySaver(true),
        Action.LockScreen,
        Action.Screenshot,
        Action.AppPermission(youtube, mic, grant = true),
    )

    @Test
    fun `the flat path routes the eight actions to the runner`() = runTest {
        val tools = RecordingTools()
        val exec = executor(tools)

        all.forEach {
            val result = exec.execute(it, context)
            // Lo screenshot riuscito porta il nome del file (come codice); le altre azioni, solo il successo.
            if (it == Action.Screenshot) {
                assertEquals(true, result is ActionResult.SuccessWithDetail, it.toString())
            } else {
                assertEquals(ActionResult.Success, result, it.toString())
            }
        }

        assertEquals(
            listOf(
                "app_control $youtube FORCE_STOP", "airplane true", "location false", "nfc true",
                "battery_saver true", "lock", "screenshot", "permission $youtube $mic true",
            ),
            tools.calls,
        )
    }

    @Test
    fun `without a ready backend nothing runs and the code is the usual one`() = runTest {
        val tools = RecordingTools()
        val exec = executor(tools, ready = false)

        all.forEach {
            assertEquals(ActionResult.Failure(ShizukuShellException.UNAVAILABLE), exec.execute(it, context), it.toString())
        }
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `without the runner the actions fail clean`() = runTest {
        val exec = executor(tools = null)

        all.forEach {
            assertEquals(ActionResult.Failure(ShizukuShellException.UNAVAILABLE), exec.execute(it, context), it.toString())
        }
    }

    @Test
    fun `a command error becomes its code in the log`() = runTest {
        assertEquals(
            ActionResult.Failure("set_airplane_exit_1"),
            executor(RecordingTools(DeviceToolException("set_airplane_exit_1"))).execute(Action.SetAirplane(true), context),
        )
        assertEquals(
            ActionResult.Failure("permission_not_applied"),
            executor(RecordingTools(DeviceToolException("permission_not_applied")))
                .execute(Action.AppPermission(youtube, mic, false), context),
        )
        assertEquals(
            ActionResult.Failure(ShizukuShellException.TIMEOUT),
            executor(RecordingTools(ShizukuShellException(ShizukuShellException.TIMEOUT))).execute(Action.LockScreen, context),
        )
    }

    @Test
    fun `a program path resolves the literal action and runs it through the same runner`() = runTest {
        val tools = RecordingTools()
        val exec = executor(tools)
        val action = Action.AppControl(youtube, AppControlOp.UNFREEZE)

        val resolved = assertIs<ActionResolution.Resolved>(TaintAwareInterpolator().resolve(action, VarScope(emptyMap()))).value

        assertEquals(ActionResult.Success, exec.execute(resolved, context).result)
        assertEquals(listOf("app_control $youtube UNFREEZE"), tools.calls)
    }

    @Test
    fun `a forbidden target never reaches the command`() = runTest {
        val tools = RecordingTools()
        val exec = executor(tools)

        assertEquals(
            ActionResult.Failure("target_forbidden"),
            exec.execute(Action.AppControl("dev.argus", AppControlOp.FREEZE), context),
        )
        assertEquals(
            ActionResult.Failure("target_forbidden"),
            exec.execute(Action.AppControl("com.example.launcher", AppControlOp.FORCE_STOP), context),
        )
        assertEquals(emptyList(), tools.calls)
    }
}
