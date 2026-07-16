package dev.argus.automation

import dev.argus.brain.AgentTransport
import dev.argus.brain.BridgeErrorKind
import dev.argus.brain.BridgeException
import dev.argus.brain.DefaultTransportFactory
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.brain.TransportErrorKind
import dev.argus.brain.TransportException
import dev.argus.brain.TransportFactory
import dev.argus.brain.TransportHealth
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TEST_MANIFEST = CapabilityManifest(
    deviceModel = "test",
    androidVersion = 16,
    androidApi = 36,
    shizukuAvailable = false,
    grantedPermissions = emptyList(),
    availableTools = emptyList(),
    unavailableTools = emptyMap(),
    whitelistedContacts = emptyList(),
)

class ConfiguredBridgeBrainTest {
    @Test
    fun `configured reflects the protected token store on every read`() = runTest {
        val configuration = FakeBridgeConfiguration()
        val brain = ConfiguredBridgeBrain(configuration, privacyAccepted = { true })

        assertFalse(brain.configured())
        configuration.token = "a-valid-bridge-token"
        assertTrue(brain.configured())
        configuration.token = null
        assertFalse(brain.configured())
    }

    @Test
    fun `compile is blocked before privacy consent without touching the network`() = runTest {
        val configuration = FakeBridgeConfiguration().apply {
            token = "a-valid-bridge-token"
        }
        val brain = ConfiguredBridgeBrain(configuration, privacyAccepted = { false })

        val error = assertFailsWith<BridgeException> {
            brain.compile("crea una regola", TEST_MANIFEST, DeviceState())
        }
        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertEquals(0, configuration.configReads)
    }

    @Test
    fun `health is blocked before privacy consent without constructing a transport`() = runTest {
        val configuration = FakeBridgeConfiguration().apply {
            token = "a-valid-bridge-token"
        }
        val brain = ConfiguredBridgeBrain(configuration, privacyAccepted = { false })

        assertEquals(
            BridgeHealthResult.Unreachable(BridgeErrorKind.CONFIGURATION),
            brain.health(),
        )
        assertEquals(0, configuration.configReads)
    }

    @Test
    fun `act is blocked before privacy consent without exposing notification context`() = runTest {
        val configuration = FakeBridgeConfiguration().apply {
            token = "a-valid-bridge-token"
        }
        val brain = ConfiguredBridgeBrain(configuration, privacyAccepted = { false })
        val context = notificationContext()

        val error = assertFailsWith<BridgeException> {
            brain.act(
                context,
                "rispondi",
                listOf("notification"),
                listOf("whatsapp_reply"),
            )
        }

        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertEquals(0, configuration.configReads)
    }

    @Test
    fun `transport is cached per provider url and model and rebuilt on change`() = runTest {
        val configuration = FakeBridgeConfiguration().apply { token = "a-valid-bridge-token" }
        val factory = FakeTransportFactory()
        val brain = ConfiguredBridgeBrain(configuration, privacyAccepted = { true }, factory = factory)

        brain.compile("una", TEST_MANIFEST, DeviceState())
        brain.compile("due", TEST_MANIFEST, DeviceState())
        assertEquals(1, factory.created)

        configuration.model = "gpt-x"
        brain.compile("tre", TEST_MANIFEST, DeviceState())
        assertEquals(2, factory.created)

        // Cambiare solo il token non tocca la ProviderConfig: nessuna ricostruzione del transport.
        configuration.token = "another-valid-bridge-token"
        brain.compile("quattro", TEST_MANIFEST, DeviceState())
        assertEquals(2, factory.created)
    }

    @Test
    fun `a factory configuration error propagates out of compile`() = runTest {
        // Proprieta' documentata (ConfiguredBridgeBrain §currentTransport): il throw della factory
        // per un provider che non puo' essere costruito si propaga FUORI dal try di
        // TransportBackedBrain, quindi compile() rilancia la TransportException invece di mapparla a
        // metaError. In Wave 1 lo scatenava un provider non implementato; ora tutti i provider hanno
        // un transport, quindi si verifica il canale con una factory che lancia, a prescindere dal
        // provider concreto.
        val configuration = FakeBridgeConfiguration().apply { token = "a-valid-bridge-token" }
        val factory = TransportFactory {
            throw TransportException(TransportErrorKind.CONFIGURATION, "provider non configurato")
        }
        val brain = ConfiguredBridgeBrain(configuration, privacyAccepted = { true }, factory = factory)

        val error = assertFailsWith<TransportException> {
            brain.compile("una regola", TEST_MANIFEST, DeviceState())
        }
        assertEquals(TransportErrorKind.CONFIGURATION, error.kind)
    }

    @Test
    fun `health flows through the factory transport`() = runTest {
        val configuration = FakeBridgeConfiguration().apply { token = "a-valid-bridge-token" }
        val factory = FakeTransportFactory()
        val brain = ConfiguredBridgeBrain(configuration, privacyAccepted = { true }, factory = factory)

        val result = brain.health()

        assertTrue(result is BridgeHealthResult.Reachable)
        assertEquals("fake-model", result.health.model)
    }

    private fun notificationContext(): FireContext = FireContext(
        event = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            text = "privato",
            isGroup = false,
        ),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )
}

private class FakeBridgeConfiguration : ProviderConfigStore {
    var url = "https://bridge.example"
    var token: String? = null
    var model: String? = null
    var selected: ProviderId = ProviderId.HERMES
    var configReads = 0

    override fun selectedProviderId(): ProviderId = selected

    override suspend fun selectProvider(id: ProviderId): Boolean {
        selected = id
        return true
    }

    override fun providerConfig(id: ProviderId): ProviderConfig {
        configReads += 1
        return ProviderConfig(providerId = id, baseUrl = url, model = model)
    }

    override suspend fun saveProviderConfig(
        id: ProviderId,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
    ): Boolean {
        baseUrl?.let { url = it }
        model?.let { this.model = it }
        apiKey?.let { token = it }
        return true
    }

    override suspend fun apiKey(id: ProviderId): String? = token
    override suspend fun hasApiKey(id: ProviderId): Boolean = token != null
    override suspend fun clearApiKey(id: ProviderId): Boolean {
        token = null
        return true
    }
}

private class FakeTransportFactory : TransportFactory {
    var created = 0

    override fun create(config: ProviderConfig): AgentTransport {
        created += 1
        return FakeAgentTransport(config.providerId)
    }
}

private class FakeAgentTransport(
    override val providerId: ProviderId,
) : AgentTransport {
    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult = CompileResult(reply = "ok", draft = null, metaError = null)

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = ActResult(text = "ok", metaError = null)

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        ActResult(text = "ok", metaError = null)

    override suspend fun health(): TransportHealth = object : TransportHealth {
        override val model: String = "fake-model"
    }
}
