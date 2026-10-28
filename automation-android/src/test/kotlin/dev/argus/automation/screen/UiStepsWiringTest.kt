package dev.argus.automation.screen

import dev.argus.automation.AutomationNotifier
import dev.argus.automation.ClipboardCopier
import dev.argus.automation.GenerativeLane
import dev.argus.automation.ShizukuActionExecutor
import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.device.AccessibilityEnablement
import dev.argus.device.DeviceController
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.UiStep
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.VarScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `ui_steps` nell'esecutore delle foglie e nel manifest: il cablaggio che porta la macro allo schermo. */
class UiStepsWiringTest {
    private fun context(event: TriggerEvent) = FireContext(
        event = event,
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private val trusted = context(
        TriggerEvent.ImmediateFired(AutomationId("automation-1"), ApprovalFingerprint("0".repeat(64))),
    )

    private val clock = FakeClock()

    private val noTools: DeviceController = Proxy.newProxyInstance(
        DeviceController::class.java.classLoader,
        arrayOf(DeviceController::class.java),
    ) { _, method, _ -> error("ui_steps non usa Shizuku: ${method.name}") } as DeviceController

    private fun executor(runner: UiStepsRunner?) = ShizukuActionExecutor(
        tools = noTools,
        notifier = AutomationNotifier { _, _, _ -> },
        generativeLane = GenerativeLane { _, _ -> false },
        replies = NotificationReplyGateway { NotificationReplyDelivery.Failed("reply_channel_unavailable") },
        clipboard = object : ClipboardCopier {
            override fun copy(event: TriggerEvent, extractionRegex: String?) = ActionResult.Success
            override fun copyLiteral(text: String) = ActionResult.Success
        },
        uiSteps = runner,
    )

    private fun runnerFor(root: FakeNode, session: FakeSession = FakeSession()) =
        UiStepsRunner(FakeScreen(time = clock.now, tree = { root }), session, clock.now, clock.pause)

    @Test
    fun `the flat path runs the macro on the screen`() = runTest {
        val button = FakeNode(text = "Invia", isClickable = true)
        val result = executor(runnerFor(FakeNode(children = listOf(button))))
            .execute(Action.UiSteps(null, listOf(UiStep.Click(text = "Invia"))), trusted)
        assertEquals(ActionResult.Success, result)
        assertEquals(1, button.clicks.size)
    }

    @Test
    fun `a macro that cannot find its element fails with the closed code`() = runTest {
        val result = executor(runnerFor(FakeNode()))
            .execute(Action.UiSteps(null, listOf(UiStep.Click(text = "Invia"))), trusted)
        assertEquals(ActionResult.Failure("ui_element_not_found"), result)
    }

    @Test
    fun `without the runner the macro fails clean`() = runTest {
        val result = executor(null).execute(Action.UiSteps(null, listOf(UiStep.Back)), trusted)
        assertEquals(ActionResult.Failure("base_executor_unavailable"), result)
    }

    @Test
    fun `an event that anyone can cause cannot start a macro, even if the rule got that far`() = runTest {
        val button = FakeNode(text = "Invia", isClickable = true)
        val sms = context(TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+39001", "x"))
        val result = executor(runnerFor(FakeNode(children = listOf(button))))
            .execute(Action.UiSteps(null, listOf(UiStep.Click(text = "Invia"))), sms)
        assertEquals(ActionResult.Failure("ui_steps_external_trigger"), result)
        assertEquals(emptyList(), button.clicks)
    }

    @Test
    fun `the app is opened through the launch app path`() = runTest {
        // Senza Shizuku né esecutore base, launch_app ripiega sugli strumenti, che qui falliscono: il
        // fallimento ferma la macro prima di toccare lo schermo.
        val button = FakeNode(text = "Invia", isClickable = true)
        val result = executor(runnerFor(FakeNode(children = listOf(button))))
            .execute(Action.UiSteps("com.example.shop", listOf(UiStep.Click(text = "Invia"))), trusted)
        assertIs<ActionResult.Failure>(result)
        assertEquals(emptyList(), button.clicks)
    }

    @Test
    fun `the typed text of a macro takes a tainted value as a local sink`() = runTest {
        val tainted = VarValue(
            "ciao", VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, setOf(ValueProvenance.SMS),
        )
        val resolved = assertIs<ActionResolution.Resolved>(
            TaintAwareInterpolator().resolve(
                Action.UiSteps(null, listOf(UiStep.Type("Dico: \${m}"))),
                VarScope(mapOf("m" to tainted)),
            ),
        ).value
        val field = FakeNode(isEditable = true)
        val runner = UiStepsRunner(
            FakeScreen(time = clock.now, tree = { FakeNode() }, focus = { field }),
            FakeSession(),
            clock.now,
            clock.pause,
        )
        val result = executor(runner).execute(resolved, trusted)
        assertEquals(ActionResult.Success, result.result)
        assertEquals(listOf("Dico: ciao"), field.typed)
    }

    // --- manifest e risorse --------------------------------------------------------------------

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `the accessibility service is bound by the system only and points at its config`() {
        val service = Regex(
            """<service\s+android:name="dev\.argus\.automation\.screen\.ArgusAccessibilityService".*?</service>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)?.value.orEmpty()
        assertTrue(service.isNotEmpty(), "servizio di Accessibilità non dichiarato")
        assertTrue("""android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"""" in service)
        assertTrue("android.accessibilityservice.AccessibilityService" in service)
        assertTrue("""android:resource="@xml/argus_accessibility_service"""" in service)
    }

    @Test
    fun `the service config asks for the minimum`() {
        val config = File("src/main/res/xml/argus_accessibility_service.xml").readText()
        assertTrue("""android:canRetrieveWindowContent="true"""" in config)
        assertTrue("""android:accessibilityEventTypes="typeWindowStateChanged"""" in config)
        assertFalse("android:canPerformGestures" in config)
        assertFalse("canRequestFilterKeyEvents" in config)
        assertFalse("typeAllMask" in config)
        assertFalse("typeViewTextChanged" in config)
        assertTrue("@string/a11y51_service_description" in config)
    }

    @Test
    fun `the stop receiver is private and listens to no external action`() {
        val receiver = Regex(
            """<receiver\s+android:name="dev\.argus\.automation\.screen\.UiMacroStopReceiver"[^>]*/>""",
        ).find(manifest)?.value.orEmpty()
        assertTrue(receiver.isNotEmpty(), "receiver di stop non dichiarato")
        assertTrue("""android:exported="false"""" in receiver)
        assertFalse("intent-filter" in receiver)
    }

    @Test
    fun `the service class name used by the enablement helper is the declared class`() {
        assertEquals(ArgusAccessibilityService::class.java.name, AccessibilityEnablement.SERVICE_CLASS)
        assertTrue(AccessibilityEnablement.SERVICE_CLASS in manifest)
    }

    @Test
    fun `the service reads nothing on its own`() {
        val source = File("src/main/kotlin/dev/argus/automation/screen/ArgusAccessibilityService.kt").readText()
        assertTrue("override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit" in source)
        assertFalse("rootInActiveWindow" in source.substringAfter("class ArgusAccessibilityService"))
        assertFalse("Log." in source)
    }

    @Test
    fun `no screen content reaches the log`() {
        File("src/main/kotlin/dev/argus/automation/screen").listFiles().orEmpty()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val source = file.readText()
                assertFalse("Log." in source, "${file.name} scrive nel Log")
                assertFalse("println" in source, "${file.name} stampa")
            }
    }
}
