package dev.argus.automation

import dev.argus.brain.BridgeConfigurationStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfiguredBridgeBrainTest {
    @Test
    fun `configured reflects the protected token store on every read`() = runTest {
        val configuration = FakeBridgeConfiguration()
        val brain = ConfiguredBridgeBrain(configuration)

        assertFalse(brain.configured())
        configuration.token = "a-valid-bridge-token"
        assertTrue(brain.configured())
        configuration.token = null
        assertFalse(brain.configured())
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
