package dev.argus.automation

import dev.argus.brain.BridgeConfigurationStore
import dev.argus.brain.BridgeErrorKind
import dev.argus.brain.BridgeException
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.runtime.DeviceState
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
    }
}

private class FakeBridgeConfiguration : BridgeConfigurationStore {
    var url = "https://bridge.example"
    var token: String? = null

    override fun baseUrl(): String = url
    override suspend fun bearerToken(): String? = token
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
