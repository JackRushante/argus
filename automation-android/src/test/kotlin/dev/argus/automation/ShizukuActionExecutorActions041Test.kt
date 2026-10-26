package dev.argus.automation

import dev.argus.automation.base.AndroidSystemActionExecutor
import dev.argus.automation.base.MediaKeySurface
import dev.argus.automation.base.SpeechEngine
import dev.argus.automation.base.SpeechOutcome
import dev.argus.automation.base.SystemSettingsSurface
import dev.argus.automation.http.HttpRequestExecutor
import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.device.DeviceController
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.MediaCommand
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.lang.reflect.Proxy
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Cablaggio delle azioni 0.4.1 nell'esecutore delle foglie: percorso piatto, percorso P4 con segnaposto
 * risolti dall'interpolatore, e fallimento pulito quando l'esecutore dedicato non c'è.
 */
class ShizukuActionExecutorActions041Test {
    private val context = FireContext(
        event = TriggerEvent.NotificationPosted("com.example"),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** I comandi privilegiati non devono mai servire a queste azioni: qualsiasi chiamata fa fallire il test. */
    private val noTools: DeviceController = Proxy.newProxyInstance(
        DeviceController::class.java.classLoader,
        arrayOf(DeviceController::class.java),
    ) { _, method, _ -> error("le azioni 0.4.1 non usano Shizuku: ${method.name}") } as DeviceController

    private class FakeSpeech : SpeechEngine {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, timeoutMs: Long): SpeechOutcome {
            spoken += text
            return SpeechOutcome.DONE
        }
    }

    private class FakeMedia : MediaKeySurface {
        val events = mutableListOf<Pair<Int, Boolean>>()
        override fun dispatch(keyCode: Int, down: Boolean) {
            events += keyCode to down
        }
    }

    private class FakeSettings(var writable: Boolean = true) : SystemSettingsSurface {
        val writes = mutableListOf<Pair<String, Int>>()
        override fun canWrite() = writable
        override fun putInt(name: String, value: Int): Boolean {
            writes += name to value
            return true
        }
    }

    private fun executor(
        system: AndroidSystemActionExecutor? = null,
        http: HttpRequestExecutor? = null,
    ) = ShizukuActionExecutor(
        tools = noTools,
        notifier = AutomationNotifier { _, _, _ -> },
        generativeLane = GenerativeLane { _, _ -> false },
        replies = NotificationReplyGateway { NotificationReplyDelivery.Failed("reply_channel_unavailable") },
        clipboard = object : ClipboardCopier {
            override fun copy(event: TriggerEvent, extractionRegex: String?) = ActionResult.Success
            override fun copyLiteral(text: String) = ActionResult.Success
        },
        systemActions = system,
        httpRequests = http,
    )

    private fun tainted(text: String) = VarValue(
        text, VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, setOf(ValueProvenance.SMS),
    )

    @Test
    fun `the flat path routes the system actions to the dedicated executor`() = runTest {
        val speech = FakeSpeech()
        val media = FakeMedia()
        val settings = FakeSettings()
        val exec = executor(AndroidSystemActionExecutor(speech, media, settings))

        assertEquals(ActionResult.Success, exec.execute(Action.Speak("Ciao"), context))
        assertEquals(ActionResult.Success, exec.execute(Action.MediaControl(MediaCommand.NEXT), context))
        assertEquals(ActionResult.Success, exec.execute(Action.SetBrightness(level = 100), context))
        assertEquals(ActionResult.Success, exec.execute(Action.SetRotation(auto = true), context))

        assertEquals(listOf("Ciao"), speech.spoken)
        assertEquals(2, media.events.size)
        assertEquals(
            listOf("screen_brightness_mode" to 0, "screen_brightness" to 255, "accelerometer_rotation" to 1),
            settings.writes,
        )
    }

    @Test
    fun `without the dedicated executors the actions fail clean and touch nothing`() = runTest {
        val exec = executor()
        listOf(
            Action.Speak("x"),
            Action.MediaControl(MediaCommand.PLAY),
            Action.SetBrightness(auto = true),
            Action.SetRotation(true),
            Action.HttpRequest(HttpMethod.GET, "https://example.org/"),
        ).forEach { action ->
            assertEquals(
                ActionResult.Failure("base_executor_unavailable"),
                exec.execute(action, context),
                action.toString(),
            )
        }
    }

    @Test
    fun `brightness without the special permission is a typed failure`() = runTest {
        val settings = FakeSettings(writable = false)
        val exec = executor(AndroidSystemActionExecutor(FakeSpeech(), FakeMedia(), settings))

        assertEquals(
            ActionResult.Failure("write_settings_unavailable"),
            exec.execute(Action.SetBrightness(level = 30), context),
        )
        assertEquals(
            ActionResult.Failure("write_settings_unavailable"),
            exec.execute(Action.SetRotation(false), context),
        )
        assertEquals(emptyList(), settings.writes)
    }

    @Test
    fun `the flat path sends the http request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val exec = executor(http = HttpRequestExecutor())

        val result = exec.execute(
            Action.HttpRequest(
                HttpMethod.POST,
                "http://127.0.0.1:${server.port}/hook",
                body = "{}",
                contentType = "application/json",
            ),
            context,
        )

        assertEquals(ActionResult.Success, result)
        assertEquals("/hook", server.takeRequest().path)
    }

    @Test
    fun `a program resolves a tainted sms into the spoken text and into an escaped json body`() = runTest {
        val speech = FakeSpeech()
        server.enqueue(MockResponse().setResponseCode(200))
        val exec = executor(
            AndroidSystemActionExecutor(speech, FakeMedia(), FakeSettings()),
            HttpRequestExecutor(),
        )
        val interpolator = TaintAwareInterpolator()
        val scope = VarScope(mapOf("sms" to tainted("ciao \"mondo\", {\"admin\": true}\n")))

        val speak = assertIs<ActionResolution.Resolved>(
            interpolator.resolve(Action.Speak("Messaggio: \${sms}"), scope),
        ).value
        assertEquals(ActionResult.Success, exec.execute(speak, context).result)
        assertEquals(listOf("Messaggio: ciao \"mondo\", {\"admin\": true}\n"), speech.spoken)

        val call = assertIs<ActionResolution.Resolved>(
            interpolator.resolve(
                Action.HttpRequest(
                    HttpMethod.POST,
                    "http://127.0.0.1:${server.port}/sms",
                    body = "{\"text\":\"\${sms}\",\"source\":\"argus\"}",
                    contentType = "application/json",
                ),
                scope,
            ),
        ).value
        assertEquals(ActionResult.Success, exec.execute(call, context).result)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertEquals(setOf("text", "source"), body.keys)
        assertEquals("ciao \"mondo\", {\"admin\": true}\n", (body["text"] as JsonPrimitive).content)
    }
}
