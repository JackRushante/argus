package dev.argus.automation.privileged

import dev.argus.device.DeviceToolException
import dev.argus.device.PrivilegedActionController
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Il runner delle azioni privilegiate 0.5.1: controlli sul telefono prima dei comandi, esiti tipizzati. */
class PrivilegedActionRunnerTest {
    private val context = FireContext(
        event = TriggerEvent.NotificationPosted("com.example"),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
        priority = 3,
    )
    private val youtube = "com.google.android.youtube"
    private val mic = "android.permission.RECORD_AUDIO"

    private class FakeTools(private val failWith: Throwable? = null) : PrivilegedActionController {
        val calls = mutableListOf<String>()
        var png: ByteArray = byteArrayOf(1, 2, 3)

        private fun record(call: String) {
            calls += call
            failWith?.let { throw it }
        }

        override suspend fun appControl(pkg: String, op: AppControlOp, executionId: ExecutionId, priority: Int) =
            record("app_control $pkg $op ${executionId.value} $priority")

        override suspend fun setAirplane(on: Boolean, executionId: ExecutionId, priority: Int) = record("airplane $on")
        override suspend fun setLocation(on: Boolean, executionId: ExecutionId, priority: Int) = record("location $on")
        override suspend fun setNfc(on: Boolean, executionId: ExecutionId, priority: Int) = record("nfc $on")
        override suspend fun setBatterySaver(on: Boolean, executionId: ExecutionId, priority: Int) =
            record("battery_saver $on")

        override suspend fun lockScreen(executionId: ExecutionId, priority: Int) = record("lock")
        override suspend fun screenshot(): ByteArray {
            record("screenshot")
            return png
        }

        override suspend fun appPermission(
            pkg: String,
            permission: String,
            grant: Boolean,
            executionId: ExecutionId,
            priority: Int,
        ) = record("permission $pkg $permission $grant")
    }

    private class FakeStore(private val ok: Boolean = true) : ScreenshotStore {
        val saved = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun save(displayName: String, png: ByteArray): Boolean {
            saved += displayName to png
            return ok
        }
    }

    private class FakeEnvironment(
        private val nfc: Boolean = true,
        private val launchers: Set<String> = setOf("com.example.launcher"),
    ) : PrivilegedActionEnvironment {
        override fun hasNfc() = nfc
        override fun launcherPackages() = launchers
    }

    private val noon = 1_793_188_800_000L // 2026-10-28 12:00:00 UTC

    private fun runner(
        tools: PrivilegedActionController,
        store: ScreenshotStore = FakeStore(),
        env: PrivilegedActionEnvironment = FakeEnvironment(),
    ) = PrivilegedActionRunner(tools, store, env, clock = { noon }, zone = { ZoneId.of("UTC") })

    @Test
    fun `each action runs its command with the execution identity and priority`() = runTest {
        val tools = FakeTools()
        val runner = runner(tools)

        listOf(
            Action.AppControl(youtube, AppControlOp.FORCE_STOP),
            Action.SetAirplane(true),
            Action.SetLocation(false),
            Action.SetNfc(true),
            Action.SetBatterySaver(false),
            Action.LockScreen,
            Action.AppPermission(youtube, mic, grant = false),
        ).forEach { assertEquals(ActionResult.Success, runner.execute(it, context), it.toString()) }

        assertEquals(
            listOf(
                "app_control $youtube FORCE_STOP execution-1 3",
                "airplane true",
                "location false",
                "nfc true",
                "battery_saver false",
                "lock",
                "permission $youtube $mic false",
            ),
            tools.calls,
        )
    }

    @Test
    fun `Argus, the system packages and the launcher are never touched`() = runTest {
        val tools = FakeTools()
        val runner = runner(tools)

        listOf("dev.argus", "dev.argus.debug", "com.android.systemui", "com.google.android.gms", "com.example.launcher")
            .forEach { pkg ->
                AppControlOp.entries.forEach { op ->
                    assertEquals(
                        ActionResult.Failure("target_forbidden"),
                        runner.execute(Action.AppControl(pkg, op), context),
                        "$pkg $op",
                    )
                }
            }
        assertEquals(ActionResult.Failure("target_forbidden"), runner.execute(Action.AppPermission("dev.argus", mic, true), context))
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `a malformed package or a permission outside the list is an invalid action and runs nothing`() = runTest {
        val tools = FakeTools()
        val runner = runner(tools)

        assertEquals(ActionResult.Failure("action_invalid"), runner.execute(Action.AppControl("x y", AppControlOp.FREEZE), context))
        assertEquals(ActionResult.Failure("action_invalid"), runner.execute(Action.AppPermission("\${p}", mic, true), context))
        assertEquals(
            ActionResult.Failure("action_invalid"),
            runner.execute(Action.AppPermission(youtube, "android.permission.WRITE_SECURE_SETTINGS", true), context),
        )
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `the launcher is not protected for permission changes, only Argus is`() = runTest {
        val tools = FakeTools()
        // Per i permessi vale solo il divieto su Argus: il launcher non è protetto.
        assertEquals(
            ActionResult.Success,
            runner(tools).execute(Action.AppPermission("com.example.launcher", mic, true), context),
        )
        assertEquals(listOf("permission com.example.launcher $mic true"), tools.calls)
    }

    @Test
    fun `nfc on a phone without NFC fails with its own code and runs nothing`() = runTest {
        val tools = FakeTools()
        val result = runner(tools, env = FakeEnvironment(nfc = false)).execute(Action.SetNfc(true), context)

        assertEquals(ActionResult.Failure("nfc_unavailable"), result)
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `the screenshot is saved with a name made of date and time only`() = runTest {
        val tools = FakeTools().apply { png = byteArrayOf(9, 8, 7) }
        val store = FakeStore()

        // Il nome del file arriva al Log come codice chiuso (data e ora), lo stesso del file salvato.
        assertEquals(
            ActionResult.SuccessWithDetail("screenshot_20261028_120000"),
            runner(tools, store).execute(Action.Screenshot, context),
        )

        assertEquals(listOf("Argus-20261028-120000.png"), store.saved.map { it.first })
        assertEquals(
            "Pictures/Argus/Argus-20261028-120000.png",
            ScreenshotLogDetail.displayPath("screenshot_20261028_120000"),
        )
        assertEquals(listOf(9, 8, 7), store.saved.single().second.map { it.toInt() })
        assertEquals("Argus-20261028-130000.png", PrivilegedActionRunner.screenshotName(noon, ZoneId.of("Europe/Rome")))
    }

    @Test
    fun `a screenshot that cannot be saved fails with its own code`() = runTest {
        val result = runner(FakeTools(), FakeStore(ok = false)).execute(Action.Screenshot, context)

        assertEquals(ActionResult.Failure("screenshot_save_failed"), result)
    }

    @Test
    fun `a failing command is not hidden by the runner`() = runTest {
        val runner = runner(FakeTools(failWith = DeviceToolException("set_airplane_exit_1")))

        val error = assertFailsWith<DeviceToolException> { runner.execute(Action.SetAirplane(true), context) }
        assertEquals("set_airplane_exit_1", error.code)
    }

    @Test
    fun `an action of another family is not run here`() = runTest {
        val tools = FakeTools()

        assertEquals(ActionResult.Failure("action_invalid"), runner(tools).execute(Action.SetWifi(true), context))
        assertEquals(emptyList(), tools.calls)
    }

    @Test
    fun `the log code of a screenshot rebuilds exactly the saved file name`() {
        val zone = ZoneId.of("Europe/Rome")
        val code = ScreenshotLogDetail.code(noon, zone)

        assertEquals("screenshot_20261028_130000", code)
        assertEquals(PrivilegedActionRunner.screenshotName(noon, zone), ScreenshotLogDetail.fileName(code))
        assertEquals(null, ScreenshotLogDetail.fileName("screenshot_save_failed"))
        assertEquals(null, ScreenshotLogDetail.fileName("screenshot_2026"))
        assertEquals(null, ScreenshotLogDetail.fileName(null))
        // Il codice rispetta la forma chiusa del journal.
        assertEquals(ActionResult.SuccessWithDetail(code).detailCode, code)
    }
}
