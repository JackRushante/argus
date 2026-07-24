package dev.argus.brain

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportBackedBrainTest {

    private val manifest = CapabilityManifest(
        deviceModel = "Pixel-TEST-9",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.INTERNET"),
        availableTools = listOf("set_dnd"),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
    )

    /** Fake AgentTransport controllabile: throw dell'eccezione data o ritorno del risultato dato. */
    private class FakeTransport(
        val onCompile: () -> CompileResult = { error("not stubbed") },
        val onAct: () -> ActResult = { error("not stubbed") },
        val onActV2: () -> ActResult = { error("not stubbed") },
        val onHealth: () -> TransportHealth = { error("not stubbed") },
    ) : AgentTransport {
        override val providerId: ProviderId = ProviderId.HERMES
        override suspend fun compile(message: String, manifest: CapabilityManifest, state: DeviceState) = onCompile()
        override suspend fun act(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
        ) = onAct()
        override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2) = onActV2()
        override suspend fun health() = onHealth()
    }

    private fun fireContext() = FireContext(
        event = TriggerEvent.NotificationPosted(pkg = "com.whatsapp", text = "ciao", isGroup = false),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    @Test fun `compile maps every TransportErrorKind to bridge-prefixed metaError with user-safe reply`(): Unit = runBlocking {
        for (kind in TransportErrorKind.entries) {
            val brain = TransportBackedBrain(
                FakeTransport(onCompile = { throw TransportException(kind, "boom") }),
            )
            val result = brain.compile("x", manifest, DeviceState())
            assertEquals("bridge_${kind.name.lowercase()}", result.metaError)
            assertNull(result.draft)
            assertEquals(TransportBackedBrain.UNREACHABLE_REPLY, result.reply)
        }
    }

    @Test fun `compile exposes draft_invalid without a hidden unmetered retry`(): Unit = runBlocking {
        var calls = 0
        val brain = TransportBackedBrain(
            FakeTransport(onCompile = {
                calls++
                CompileResult(reply = "malformed", draft = null, metaError = "draft_invalid")
            }),
        )
        val result = brain.compile("x", manifest, DeviceState())
        assertEquals(1, calls)
        assertEquals("draft_invalid", result.metaError)
        assertEquals("malformed", result.reply)
    }

    @Test fun `compile does not retry on legitimate non-malformation outcomes`(): Unit = runBlocking {
        val legitimate = listOf(
            "clarification_required",
            "unsupported_capability",
            "unsupported_state",
            "unsafe_tainted_command",
            "limit_exceeded",
        )
        for (code in legitimate) {
            var calls = 0
            val brain = TransportBackedBrain(
                FakeTransport(onCompile = { calls++; CompileResult(reply = "r", draft = null, metaError = code) }),
            )
            val result = brain.compile("x", manifest, DeviceState())
            assertEquals(1, calls, "must not retry on $code")
            assertEquals(code, result.metaError)
        }
    }

    @Test fun `act and actV2 map transport failures and pass successes through`(): Unit = runBlocking {
        val ok = TransportBackedBrain(
            FakeTransport(
                onAct = { ActResult(text = "Ciao", metaError = null) },
                onActV2 = { ActResult(text = "Ciao2", metaError = null) },
            ),
        )
        assertEquals("Ciao", ok.act(fireContext(), "g", listOf("notification"), listOf("whatsapp_reply")).text)
        val actV2Action = Action.InvokeLlmV2(
            goal = "g",
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 30_000L,
            stateContext = emptyList(),
        )
        assertEquals("Ciao2", ok.actV2(fireContext(), actV2Action).text)

        val failing = TransportBackedBrain(
            FakeTransport(
                onAct = { throw TransportException(TransportErrorKind.AUTH, "nope") },
                onActV2 = { throw TransportException(TransportErrorKind.BUDGET, "nope") },
            ),
        )
        val actFail = failing.act(fireContext(), "g", listOf("notification"), listOf("whatsapp_reply"))
        assertNull(actFail.text)
        assertEquals("bridge_auth", actFail.metaError)
        val actV2Fail = failing.actV2(fireContext(), actV2Action)
        assertNull(actV2Fail.text)
        assertEquals("bridge_budget", actV2Fail.metaError)
    }

    @Test fun `usage from the transport flows through act and actV2 untouched`(): Unit = runBlocking {
        val ok = TransportBackedBrain(
            FakeTransport(
                onAct = { ActResult("Ciao", null, TurnUsage(7, 3, model = "m")) },
                onActV2 = { ActResult("Ciao2", null, TurnUsage(11, 5, cachedInputTokens = 2, model = "m2")) },
            ),
        )
        val actUsage = assertNotNull(
            ok.act(fireContext(), "g", listOf("notification"), listOf("whatsapp_reply")).usage,
        )
        assertEquals(7L, actUsage.inputTokens)
        assertEquals(3L, actUsage.outputTokens)
        assertEquals("m", actUsage.model)

        val actV2Action = Action.InvokeLlmV2(
            goal = "g",
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 30_000L,
            stateContext = emptyList(),
        )
        val actV2Usage = assertNotNull(ok.actV2(fireContext(), actV2Action).usage)
        assertEquals(11L, actV2Usage.inputTokens)
        assertEquals(2L, actV2Usage.cachedInputTokens)

        // Ramo failure: nessuna risposta ⇒ nessun conteggio.
        val failing = TransportBackedBrain(
            FakeTransport(onAct = { throw TransportException(TransportErrorKind.AUTH, "nope") }),
        )
        val failed = failing.act(fireContext(), "g", listOf("notification"), listOf("whatsapp_reply"))
        assertNull(failed.usage)
    }

    @Test fun `cancellation propagates untouched`(): Unit = runBlocking {
        val brain = TransportBackedBrain(
            FakeTransport(onCompile = { throw CancellationException("cancelled") }),
        )
        assertFailsWith<CancellationException> {
            brain.compile("x", manifest, DeviceState())
        }
    }

    @Test fun `unexpected runtime exceptions are not swallowed`(): Unit = runBlocking {
        val brain = TransportBackedBrain(
            FakeTransport(onCompile = { throw IllegalStateException("bug") }),
        )
        assertFailsWith<IllegalStateException> {
            brain.compile("x", manifest, DeviceState())
        }
    }

    @Test fun `cli bridge transport is an AgentTransport`(): Unit = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(
                        """{"schema_version":2,"request_id":"req-t-1","reply":"ok","meta":{"draft":{"name":"dnd dopo le 23","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_dnd","mode":"PRIORITY"}]},"error_code":null}}""",
                    ),
            )
            val t: AgentTransport = CliBridgeTransport(
                baseUrl = server.url("/").toString(),
                authProvider = BridgeAuthProvider { "test-token-0123456789abcdef" },
                client = CliBridgeTransport.defaultClient(),
                allowCleartextForTests = true,
                requestIdFactory = { "req-t-1" },
            )
            assertEquals(ProviderId.HERMES, t.providerId)
            val result = t.compile("dopo le 23 metti DND", manifest, DeviceState())
            assertEquals("ok", result.reply)
            assertNotNull(result.draft)
        } finally {
            runCatching { server.shutdown() }
        }
    }
}
