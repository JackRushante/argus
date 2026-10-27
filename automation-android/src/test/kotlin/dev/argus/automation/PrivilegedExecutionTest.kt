package dev.argus.automation

import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.privileged.GrantReport
import dev.argus.automation.privileged.PrivilegedAccessCenter
import dev.argus.automation.privileged.message
import dev.argus.automation.privileged.toUi
import dev.argus.device.DeviceController
import dev.argus.device.DirectSettings
import dev.argus.device.DirectWriteResult
import dev.argus.device.ForegroundAppSource
import dev.argus.device.GrantOutcome
import dev.argus.device.PermanentGrant
import dev.argus.device.PermanentGrantsController
import dev.argus.device.StateReader
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.shizuku.PrivilegedBackend
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.RootAccess
import dev.argus.shizuku.RootState
import dev.argus.shizuku.ShellResult
import dev.argus.shizuku.ShizukuAccess
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuIdentity
import dev.argus.shizuku.ShizukuPermissionOutcome
import dev.argus.shizuku.ShizukuShellException
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingDirect(
    private val result: DirectWriteResult = DirectWriteResult.WRITTEN,
) : DirectSettings {
    val writes = mutableListOf<Triple<SettingNamespace, String, String>>()
    override fun canWrite(namespace: SettingNamespace): Boolean = result != DirectWriteResult.NOT_PERMITTED
    override fun write(namespace: SettingNamespace, key: String, value: String): DirectWriteResult {
        writes += Triple(namespace, key, value)
        return result
    }
}

/** `write_setting`: con un backend privilegiato pronto non cambia nulla; senza, passa dai permessi permanenti. */
class DirectWriteSettingTest {
    private val context = FireContext(
        event = TriggerEvent.NotificationPosted("com.example"),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private class ToolCalls {
        val writes = mutableListOf<List<Any?>>()
        var failWith: Throwable? = null
    }

    private fun tools(calls: ToolCalls): DeviceController = Proxy.newProxyInstance(
        DeviceController::class.java.classLoader,
        arrayOf(DeviceController::class.java),
    ) { _, method, args ->
        if (method.name == "writeSetting") {
            calls.failWith?.let { throw it }
            calls.writes += args.orEmpty().take(3)
            Unit
        } else {
            error("unexpected call ${method.name}")
        }
    } as DeviceController

    private fun executor(
        calls: ToolCalls,
        direct: DirectSettings?,
        ready: Boolean,
    ) = ShizukuActionExecutor(
        tools = tools(calls),
        notifier = AutomationNotifier { _, _, _ -> },
        generativeLane = GenerativeLane { _, _ -> false },
        replies = NotificationReplyGateway { NotificationReplyDelivery.Failed("reply_channel_unavailable") },
        clipboard = object : ClipboardCopier {
            override fun copy(event: TriggerEvent, extractionRegex: String?) = ActionResult.Success
            override fun copyLiteral(text: String) = ActionResult.Success
        },
        shizukuReady = { ready },
        directSettings = direct,
    )

    private val secureAction = Action.WriteSetting(SettingNamespace.SECURE, "accessibility_display_daltonizer", "1")

    @Test
    fun `with a privileged backend ready the shell path is used and the direct writer is not touched`() = runTest {
        val calls = ToolCalls()
        val direct = RecordingDirect()
        val result = executor(calls, direct, ready = true).execute(secureAction, context)

        assertEquals(ActionResult.Success, result)
        assertEquals(1, calls.writes.size)
        assertEquals(SettingNamespace.SECURE, calls.writes.single()[0])
        assertTrue(direct.writes.isEmpty())
    }

    @Test
    fun `without a backend the granted permission writes directly and the shell is not touched`() = runTest {
        val calls = ToolCalls()
        val direct = RecordingDirect()

        listOf(SettingNamespace.SECURE, SettingNamespace.GLOBAL, SettingNamespace.SYSTEM).forEach { namespace ->
            val result = executor(calls, direct, ready = false)
                .execute(Action.WriteSetting(namespace, "some_key", "42"), context)
            assertEquals(ActionResult.Success, result, namespace.name)
        }
        assertEquals(
            listOf(SettingNamespace.SECURE, SettingNamespace.GLOBAL, SettingNamespace.SYSTEM),
            direct.writes.map { it.first },
        )
        assertTrue(calls.writes.isEmpty())
    }

    @Test
    fun `a missing permission keeps the usual typed codes`() = runTest {
        val calls = ToolCalls()
        val denied = RecordingDirect(DirectWriteResult.NOT_PERMITTED)
        val exec = executor(calls, denied, ready = false)

        assertEquals(ActionResult.Failure("shizuku_unavailable"), exec.execute(secureAction, context))
        assertEquals(
            ActionResult.Failure("shizuku_unavailable"),
            exec.execute(Action.WriteSetting(SettingNamespace.GLOBAL, "k", "1"), context),
        )
        assertEquals(
            ActionResult.Failure("write_settings_unavailable"),
            exec.execute(Action.WriteSetting(SettingNamespace.SYSTEM, "k", "1"), context),
        )
        assertTrue(calls.writes.isEmpty())
    }

    @Test
    fun `an accepted permission that Android refuses is a typed write failure`() = runTest {
        val exec = executor(ToolCalls(), RecordingDirect(DirectWriteResult.FAILED), ready = false)
        assertEquals(ActionResult.Failure("settings_write_failed"), exec.execute(secureAction, context))
    }

    @Test
    fun `the same shape validation applies to the direct path`() = runTest {
        val direct = RecordingDirect()
        val exec = executor(ToolCalls(), direct, ready = false)

        val badKey = Action.WriteSetting(SettingNamespace.SECURE, "bad key; reboot", "1")
        val badValue = Action.WriteSetting(SettingNamespace.SECURE, "key", "line\nbreak")
        assertEquals(ActionResult.Failure("action_invalid"), exec.execute(badKey, context))
        assertEquals(ActionResult.Failure("action_invalid"), exec.execute(badValue, context))
        assertTrue(direct.writes.isEmpty())
    }

    @Test
    fun `without a direct writer the behaviour is exactly the old one`() = runTest {
        val calls = ToolCalls().apply { failWith = ShizukuShellException(ShizukuShellException.UNAVAILABLE) }
        val result = executor(calls, direct = null, ready = false).execute(secureAction, context)
        assertEquals(ActionResult.Failure("shizuku_unavailable"), result)
    }
}

/** App in primo piano: da `UsageStatsManager` solo quando nessun backend privilegiato è disponibile. */
class UsageForegroundStateTest {
    private class CountingForeground(private val pkg: String?) : ForegroundAppSource {
        var calls = 0
        override fun current(): String? {
            calls++
            return pkg
        }
    }

    private fun provider(
        shell: PrivilegedShell,
        usage: ForegroundAppSource?,
        status: ShizukuGatewayStatus,
        privilegedReady: (() -> Boolean)? = null,
    ) = LazyDeviceStateProvider(
        StateReader(shell),
        { status },
        CurrentLocationProvider { null },
        privilegedReady = privilegedReady,
        usageForeground = usage,
    )

    private val request = StateReadRequest(includeForegroundApp = true)

    @Test
    fun `without a backend the foreground app comes from usage stats and the shell is never used`() = runTest {
        val shell = ShellRecorder()
        val usage = CountingForeground("com.example.app")
        val state = provider(shell, usage, ShizukuGatewayStatus.NOT_INSTALLED).current(request)

        assertEquals("com.example.app", state.foregroundApp)
        assertEquals(1, usage.calls)
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `an unknown answer stays unknown`() = runTest {
        val state = provider(ShellRecorder(), CountingForeground(null), ShizukuGatewayStatus.NOT_INSTALLED)
            .current(request)
        assertNull(state.foregroundApp)
    }

    @Test
    fun `with Shizuku authorized usage stats are not consulted`() = runTest {
        val usage = CountingForeground("com.example.usage")
        provider(ShellRecorder(), usage, ShizukuGatewayStatus.AUTHORIZED).current(request)
        assertEquals(0, usage.calls)
    }

    @Test
    fun `with verified root usage stats are not consulted and the shell is used`() = runTest {
        val shell = ShellRecorder()
        val usage = CountingForeground("com.example.usage")
        provider(shell, usage, ShizukuGatewayStatus.NOT_INSTALLED, privilegedReady = { true }).current(request)
        assertEquals(0, usage.calls)
        assertTrue(shell.calls.isNotEmpty())
    }

    @Test
    fun `no usage source and no backend leaves it unknown as before`() = runTest {
        val state = provider(ShellRecorder(), null, ShizukuGatewayStatus.NOT_INSTALLED).current(request)
        assertNull(state.foregroundApp)
    }
}

private class FakeShizukuAccess(
    status: ShizukuGatewayStatus,
    identity: ShizukuIdentity? = null,
    private val sui: Boolean = false,
) : ShizukuAccess {
    val statusFlow = MutableStateFlow(status)
    val identityFlow = MutableStateFlow(identity)
    override fun status(): ShizukuGatewayStatus = statusFlow.value
    override fun observeStatus(): StateFlow<ShizukuGatewayStatus> = statusFlow
    override fun observeIdentity(): StateFlow<ShizukuIdentity?> = identityFlow
    override suspend fun requestPermissionOutcome(rationaleShown: Boolean) = ShizukuPermissionOutcome.GRANTED
    override fun isSui(): Boolean = sui
}

private class FakeRootAccess(initial: RootState = RootState.OFF) : RootAccess {
    val state = MutableStateFlow(initial)
    override fun observeState(): StateFlow<RootState> = state
    override fun isEnabled(): Boolean = state.value != RootState.OFF
    override suspend fun setEnabled(enabled: Boolean): RootState {
        state.value = if (enabled) RootState.ACTIVE else RootState.OFF
        return state.value
    }

    override suspend fun verify(): RootState = state.value
}

private class RecordingShell(private val exit: (List<String>) -> Int = { 0 }) : PrivilegedShell {
    val commands = mutableListOf<List<String>>()
    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        commands += command
        return ShellResult(exit(command))
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = run(command)
}

class PrivilegedAccessCenterTest {
    private fun center(
        shizuku: ShizukuAccess,
        root: RootAccess,
        shell: RecordingShell,
        status: () -> Map<PermanentGrant, Boolean>,
    ) = PrivilegedAccessCenter(shizuku, root, PermanentGrantsController(shell, "dev.argus"), status)

    private val none = PermanentGrant.entries.associateWith { false }

    @Test
    fun `the snapshot names the backend for every combination`() = runTest {
        suspend fun backend(shizuku: FakeShizukuAccess, root: FakeRootAccess = FakeRootAccess()) =
            center(shizuku, root, RecordingShell()) { none }.snapshots.first().backend

        assertEquals(
            PrivilegedBackend.NONE,
            backend(FakeShizukuAccess(ShizukuGatewayStatus.NOT_INSTALLED)),
        )
        assertEquals(
            PrivilegedBackend.SHIZUKU_SHELL,
            backend(FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED, ShizukuIdentity(2000))),
        )
        assertEquals(
            PrivilegedBackend.SHIZUKU_ROOT,
            backend(FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED, ShizukuIdentity(0))),
        )
        assertEquals(
            PrivilegedBackend.SUI,
            backend(FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED, ShizukuIdentity(0), sui = true)),
        )
        assertEquals(
            PrivilegedBackend.ROOT,
            backend(FakeShizukuAccess(ShizukuGatewayStatus.INSTALLED_NOT_RUNNING), FakeRootAccess(RootState.ACTIVE)),
        )
        assertEquals(
            PrivilegedBackend.NONE,
            backend(FakeShizukuAccess(ShizukuGatewayStatus.INSTALLED_NOT_RUNNING), FakeRootAccess(RootState.DENIED)),
        )
    }

    @Test
    fun `granting without a backend runs nothing`() = runTest {
        val shell = RecordingShell()
        val center = center(FakeShizukuAccess(ShizukuGatewayStatus.NOT_INSTALLED), FakeRootAccess(), shell) { none }

        val report = center.grant(setOf(PermanentGrant.USAGE_STATS))

        assertTrue(shell.commands.isEmpty())
        assertEquals(mapOf(PermanentGrant.USAGE_STATS to GrantOutcome.UNAVAILABLE), report.outcomes)
        assertEquals(
            "No privileged backend is available: start Shizuku or turn on root first.",
            report.message(RenderLanguage.EN),
        )
    }

    @Test
    fun `granting with a backend runs the exact commands and reports the real state`() = runTest {
        val shell = RecordingShell()
        val granted = mutableMapOf<PermanentGrant, Boolean>().apply { putAll(none) }
        val center = center(
            FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED, ShizukuIdentity(2000)),
            FakeRootAccess(),
            shell,
        ) { granted.toMap() }

        // Android applica i primi due; l'accesso ai dati di utilizzo resta negato.
        shell.commands.clear()
        val selected = setOf(PermanentGrant.WRITE_SECURE_SETTINGS, PermanentGrant.USAGE_STATS, PermanentGrant.DND_ACCESS)
        granted[PermanentGrant.WRITE_SECURE_SETTINGS] = true
        granted[PermanentGrant.DND_ACCESS] = true
        val report = center.grant(selected)

        assertEquals(
            listOf(
                listOf("pm", "grant", "dev.argus", "android.permission.WRITE_SECURE_SETTINGS"),
                listOf("appops", "set", "dev.argus", "GET_USAGE_STATS", "allow"),
                listOf("cmd", "notification", "allow_dnd", "dev.argus"),
            ),
            shell.commands,
        )
        assertEquals(
            listOf(PermanentGrant.WRITE_SECURE_SETTINGS, PermanentGrant.DND_ACCESS),
            report.grantedNow,
        )
        assertEquals(listOf(PermanentGrant.USAGE_STATS), report.notGranted)
        assertEquals("Permissions granted: 2 of 3. Check the list in System.", report.message(RenderLanguage.EN))
        assertEquals("Permessi concessi: 2 su 3. Controlla l'elenco in Sistema.", report.message(RenderLanguage.IT))
    }

    @Test
    fun `granting with root su works the same`() = runTest {
        val shell = RecordingShell()
        val center = center(
            FakeShizukuAccess(ShizukuGatewayStatus.NOT_INSTALLED),
            FakeRootAccess(RootState.ACTIVE),
            shell,
        ) { none }
        center.grant(setOf(PermanentGrant.WRITE_SETTINGS))
        assertEquals(listOf(listOf("appops", "set", "dev.argus", "WRITE_SETTINGS", "allow")), shell.commands)
    }

    @Test
    fun `notification access is never offered and the grant list is fixed`() {
        val rows = center(
            FakeShizukuAccess(ShizukuGatewayStatus.NOT_INSTALLED),
            FakeRootAccess(),
            RecordingShell(),
        ) { none }
        assertEquals(4, PermanentGrant.entries.size)
        assertTrue(PermanentGrant.entries.none { grant -> grant.id.contains("listener") })
        assertTrue(rows.backendAvailable().not())
    }

    @Test
    fun `the UI model has one row per permission and offers granting only with a backend and something missing`() =
        runTest {
            val denied = center(
                FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED, ShizukuIdentity(2000)),
                FakeRootAccess(),
                RecordingShell(),
            ) { none }.snapshots.first().toUi()
            assertEquals(4, denied.grants.size)
            assertTrue(denied.canGrant)

            val allGranted = center(
                FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED, ShizukuIdentity(2000)),
                FakeRootAccess(),
                RecordingShell(),
            ) { PermanentGrant.entries.associateWith { true } }.snapshots.first().toUi()
            assertTrue(allGranted.grants.all { it.granted })
            assertTrue(allGranted.canGrant.not())

            val noBackend = center(
                FakeShizukuAccess(ShizukuGatewayStatus.NOT_INSTALLED),
                FakeRootAccess(),
                RecordingShell(),
            ) { none }.snapshots.first().toUi()
            assertTrue(noBackend.canGrant.not())
        }

    @Test
    fun `turning root on and off goes through the root access`() = runTest {
        val root = FakeRootAccess()
        val center = center(FakeShizukuAccess(ShizukuGatewayStatus.NOT_INSTALLED), root, RecordingShell()) { none }

        assertEquals(RootState.ACTIVE, center.setRootEnabled(true))
        assertEquals(PrivilegedBackend.ROOT, center.snapshots.first().backend)
        assertEquals(RootState.OFF, center.setRootEnabled(false))
        assertEquals(PrivilegedBackend.NONE, center.snapshots.first().backend)
    }

    @Test
    fun `root messages are short and in both languages`() {
        assertEquals("Root (su) is on.", RootState.ACTIVE.message(RenderLanguage.EN))
        assertEquals("Root (su) attivo.", RootState.ACTIVE.message(RenderLanguage.IT))
        assertNull(RootState.OFF.message(RenderLanguage.EN))
        assertNull(RootState.CHECKING.message(RenderLanguage.IT))
        assertTrue(RootState.DENIED.message(RenderLanguage.IT)!!.contains("gestore root"))
        assertTrue(RootState.NO_SU.message(RenderLanguage.EN)!!.contains("No su"))
    }

    @Test
    fun `an empty report says so`() {
        assertEquals("Nothing selected.", GrantReport(emptyMap(), emptyMap()).message(RenderLanguage.EN))
    }
}
