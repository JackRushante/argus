package dev.argus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Gate device innocuo: prova il runner letterale reale sul processo shell di Shizuku. */
@RunWith(AndroidJUnit4::class)
class ArgusStaticShellInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            instrumentation.targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun approvedLiteralRunsThroughShizukuWithoutExposingOutput(): Unit = runBlocking {
        assertEquals(
            "Shizuku deve essere già attivo e autorizzato; il test non apre dialoghi",
            ShizukuGatewayStatus.AUTHORIZED,
            services.shizukuGateway().status(),
        )
        val automationId = AutomationId("device-shell-gate")
        val fingerprint = ApprovalFingerprint("0".repeat(64))
        val context = FireContext(
            event = TriggerEvent.TimeFired(automationId, fingerprint),
            state = DeviceState(),
            automationId = automationId,
            approvalFingerprint = fingerprint,
            eventId = TriggerEventId("device-shell-event"),
            executionId = ExecutionId("device-shell-execution"),
            actionIndex = 0,
            priority = 0,
        )

        assertEquals(
            ActionResult.Success,
            services.staticShellRunner().run("/system/bin/id >/dev/null", context),
        )
        assertEquals(
            ActionResult.Failure("shell_failed"),
            services.staticShellRunner().run("/system/bin/false", context),
        )

        val captured = services.staticShellRunner().runCaptured(
            "/system/bin/printf argus-p4-capture",
            context,
        )
        assertEquals(ActionResult.Success, captured.result)
        assertEquals("argus-p4-capture", captured.capturedText)
    }
}
