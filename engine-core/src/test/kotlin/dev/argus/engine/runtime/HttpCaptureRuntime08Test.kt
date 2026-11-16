package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.SecretRef
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 0.8: a captured web response at run time (labels) and the resolved shape of secrets. */
class HttpCaptureRuntime08Test {
    private val evaluator = ConditionEvaluator(Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC))
    private val trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC")
    private val event = TriggerEvent.TimeFired(AutomationId("a"), ApprovalFingerprint("f".repeat(64)))
    private val bearer = SecretRef("Authorization", "ha_token", "Bearer ")

    private suspend fun runCapture(request: Action.HttpRequest, bindings: List<VarBinding> = emptyList()): ResolvedProgramAction {
        val seen = mutableListOf<ResolvedProgramAction>()
        val interpreter = ProgramInterpreter(
            runner = ProgramActionRunner { action, _ ->
                seen += action
                if (action.action is Action.HttpRequest) {
                    ProgramActionResult(ActionResult.Success, capturedText = "21.5")
                } else {
                    ProgramActionResult(ActionResult.Success)
                }
            },
            stateProvider = { DeviceState() },
            conditionEvaluator = evaluator,
            pause = {},
        )
        val result = interpreter.execute(
            trigger,
            event,
            bindings,
            listOf(request, Action.ShowNotification("Casa", "Ci sono \${temp} gradi")),
        )
        assertTrue(result.completed)
        assertEquals("Ci sono 21.5 gradi", assertIs<Action.ShowNotification>(seen[1].action).text)
        return seen[1]
    }

    @Test
    fun `a captured response is tainted text from the network, private without secrets`() = runTest {
        val notification = runCapture(Action.HttpRequest(HttpMethod.GET, "https://example.org/t", captureAs = "temp"))
        assertEquals(IntegrityLabel.TAINTED, notification.inputIntegrity)
        assertEquals(ConfidentialityLabel.PRIVATE, notification.inputConfidentiality)
        assertTrue(ValueProvenance.NETWORK in notification.inputProvenance)
        assertFalse(ValueProvenance.CREDENTIAL in notification.inputProvenance)
    }

    @Test
    fun `a response to a request with a secret is secret`() = runTest {
        val notification = runCapture(
            Action.HttpRequest(HttpMethod.GET, "https://example.org/t", captureAs = "temp", secretHeaders = listOf(bearer)),
        )
        assertEquals(ConfidentialityLabel.SECRET, notification.inputConfidentiality)
    }

    @Test
    fun `a response to a request with a secret variable in the body is secret`() = runTest {
        val pin = VarBinding.Literal("pin", "1234", VarType.TEXT, ConfidentialityLabel.SECRET)
        val notification = runCapture(
            Action.HttpRequest(HttpMethod.POST, "https://example.org/t", body = "\${pin}", captureAs = "temp"),
            listOf(pin),
        )
        assertEquals(ConfidentialityLabel.SECRET, notification.inputConfidentiality)
    }

    @Test
    fun `the interpolator resolves capture and secret fields but never a secret over the local network`() {
        val interpreter = TaintAwareInterpolator()
        val ok = Action.HttpRequest(
            HttpMethod.GET,
            "https://example.org/t",
            captureAs = "temp",
            extract = "/state",
            secretHeaders = listOf(bearer),
        )
        val resolved = assertIs<ActionResolution.Resolved>(interpreter.resolve(ok, VarScope()))
        // Names only: the resolved action is exactly the approved one.
        assertEquals(ok, resolved.value.action)
        val lan = ok.copy(url = "http://192.168.1.20:8123/api")
        assertEquals(ActionResolution.Blocked("resolved_action_invalid"), interpreter.resolve(lan, VarScope()))
        assertIs<ActionResolution.Resolved>(interpreter.resolve(lan.copy(secretHeaders = null), VarScope()))
    }
}
