package dev.argus.automation

import dev.argus.brain.BridgeConfigurationStore
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.brain.ReasoningEffort
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
                FakeProviderConfiguration(ProviderId.HERMES) { "bearer-0123456789abcdef" },
                FakePreferences(privacyAccepted = true),
            ).current(),
        )
        assertEquals(
            GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false),
            AndroidGenerativeRuntimeReadiness(
                FakeProviderConfiguration(ProviderId.HERMES) { null },
                FakePreferences(privacyAccepted = false),
            ).current(),
        )
    }

    @Test
    fun `bearer read failure is fail closed not ready`() = runTest {
        assertEquals(
            GenerativeReadiness(bridgeConfigured = false, privacyAccepted = true),
            AndroidGenerativeRuntimeReadiness(
                FakeProviderConfiguration(ProviderId.HERMES) { error("keystore offline") },
                FakePreferences(privacyAccepted = true),
            ).current(),
        )
    }

    @Test
    fun `custom endpoint without a key is ready, a keyed provider without a key is not`() = runTest {
        assertEquals(
            GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true),
            AndroidGenerativeRuntimeReadiness(
                FakeProviderConfiguration(ProviderId.CUSTOM_OPENAI_COMPAT) { null },
                FakePreferences(privacyAccepted = true),
            ).current(),
        )
        assertEquals(
            GenerativeReadiness(bridgeConfigured = false, privacyAccepted = true),
            AndroidGenerativeRuntimeReadiness(
                FakeProviderConfiguration(ProviderId.OPENAI) { null },
                FakePreferences(privacyAccepted = true),
            ).current(),
        )
    }

    @Test
    fun `custom endpoint without a key still needs the privacy consent`() = runTest {
        assertEquals(
            GenerativeReadiness(bridgeConfigured = true, privacyAccepted = false),
            AndroidGenerativeRuntimeReadiness(
                FakeProviderConfiguration(ProviderId.CUSTOM_OPENAI_COMPAT) { null },
                FakePreferences(privacyAccepted = false),
            ).current(),
        )
    }

    @Test
    fun `a bearer-only store keeps the legacy hermes readiness`() = runTest {
        val legacy = object : BridgeConfigurationStore {
            override fun baseUrl(): String = "https://hermes.example"
            override suspend fun saveConfiguration(baseUrl: String, bearerToken: String?) = false
            override suspend fun saveBaseUrl(value: String) = false
            override suspend fun saveBearerToken(value: String) = false
            override suspend fun clearBearerToken() = false
            override suspend fun bearerToken(): String? = "bearer-0123456789abcdef"
        }

        assertEquals(
            GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true),
            AndroidGenerativeRuntimeReadiness(legacy, FakePreferences(privacyAccepted = true)).current(),
        )
    }

    private class FakeProviderConfiguration(
        private val selected: ProviderId,
        private val key: () -> String?,
    ) : ProviderConfigStore {
        override fun selectedProviderId(): ProviderId = selected
        override suspend fun selectProvider(id: ProviderId) = false
        override fun providerConfig(id: ProviderId) = ProviderConfig(id, "https://hermes.example", null)
        override suspend fun saveProviderConfig(
            id: ProviderId,
            baseUrl: String?,
            model: String?,
            apiKey: String?,
            reasoningEffort: ReasoningEffort?,
        ) = false
        override suspend fun apiKey(id: ProviderId): String? = key()
        override suspend fun hasApiKey(id: ProviderId): Boolean = key() != null
        override suspend fun clearApiKey(id: ProviderId) = false
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
