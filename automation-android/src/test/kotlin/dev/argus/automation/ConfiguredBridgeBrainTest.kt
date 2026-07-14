package dev.argus.automation

import dev.argus.brain.BridgeConfigurationStore
import dev.argus.brain.BridgeErrorKind
import dev.argus.brain.BridgeException
import dev.argus.engine.brain.CapabilityManifest
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
            brain.compile(
                "crea una regola",
                CapabilityManifest(
                    deviceModel = "test",
                    androidVersion = 16,
                    androidApi = 36,
                    shizukuAvailable = false,
                    grantedPermissions = emptyList(),
                    availableTools = emptyList(),
                    unavailableTools = emptyMap(),
                    whitelistedContacts = emptyList(),
                ),
                DeviceState(),
            )
        }
        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertEquals(0, configuration.baseUrlReads)
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
        assertEquals(0, configuration.baseUrlReads)
    }

    @Test
    fun `act is blocked before privacy consent without exposing notification context`() = runTest {
        val configuration = FakeBridgeConfiguration().apply {
            token = "a-valid-bridge-token"
        }
        val brain = ConfiguredBridgeBrain(configuration, privacyAccepted = { false })
        val context = FireContext(
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

        val error = assertFailsWith<BridgeException> {
            brain.act(
                context,
                "rispondi",
                listOf("notification"),
                listOf("whatsapp_reply"),
            )
        }

        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertEquals(0, configuration.baseUrlReads)
    }
}

private class FakeBridgeConfiguration : BridgeConfigurationStore {
    var url = "https://bridge.example"
    var token: String? = null
    var baseUrlReads = 0

    override fun baseUrl(): String {
        baseUrlReads += 1
        return url
    }
    override suspend fun bearerToken(): String? = token
    override suspend fun saveConfiguration(baseUrl: String, bearerToken: String?): Boolean {
        if (!saveBaseUrl(baseUrl)) return false
        return bearerToken?.let { saveBearerToken(it) } ?: (token != null)
    }
    override suspend fun saveBaseUrl(value: String): Boolean {
        url = value
        return true
    }
    override suspend fun saveBearerToken(value: String): Boolean {
        token = value
        return true
    }
    override suspend fun clearBearerToken(): Boolean {
        token = null
        return true
    }
}
