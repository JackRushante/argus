package dev.argus.automation

import dev.argus.brain.BridgeConfigurationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GenerativeRuntimeReadinessTest {
    @Test
    fun `readiness requires a stored bearer and accepted privacy`() = runTest {
        assertEquals(
            GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true),
            AndroidGenerativeRuntimeReadiness(
                FakeBridgeConfiguration { "bearer-0123456789abcdef" },
                FakePreferences(privacyAccepted = true),
            ).current(),
        )
        assertEquals(
            GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false),
            AndroidGenerativeRuntimeReadiness(
                FakeBridgeConfiguration { null },
                FakePreferences(privacyAccepted = false),
            ).current(),
        )
    }

    @Test
    fun `bearer read failure is fail closed not ready`() = runTest {
        assertEquals(
            GenerativeReadiness(bridgeConfigured = false, privacyAccepted = true),
            AndroidGenerativeRuntimeReadiness(
                FakeBridgeConfiguration { error("keystore offline") },
                FakePreferences(privacyAccepted = true),
            ).current(),
        )
    }

    private class FakeBridgeConfiguration(
        private val bearer: () -> String?,
    ) : BridgeConfigurationStore {
        override fun baseUrl(): String = "https://hermes.example"
        override suspend fun saveConfiguration(baseUrl: String, bearerToken: String?) = false
        override suspend fun saveBaseUrl(value: String) = false
        override suspend fun saveBearerToken(value: String) = false
        override suspend fun clearBearerToken() = false
        override suspend fun bearerToken(): String? = bearer()
    }

    private class FakePreferences(privacyAccepted: Boolean) : AppPreferencesStore {
        private val state = MutableStateFlow(
            AppPreferences(privacyAccepted = privacyAccepted, onboardingCompleted = false),
        )

        override fun observe(): StateFlow<AppPreferences> = state.asStateFlow()
        override suspend fun setPrivacyAccepted(accepted: Boolean): Boolean = false
        override suspend fun setOnboardingCompleted(completed: Boolean): Boolean = false
    }
}
