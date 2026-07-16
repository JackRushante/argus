package dev.argus.brain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica su device reale (AndroidKeyStore vero) che la migrazione legacy -> multi-provider non
 * perda il token, che le chiavi per-provider siano isolate/cifrate/cancellabili, e che il contratto
 * legacy di [BridgeConfigurationStore] resti intatto sul nuovo store. NON gira nel gate host.
 */
@RunWith(AndroidJUnit4::class)
class ProviderConfigStoreInstrumentedTest {

    private val prefsName = "argus_bridge_private"

    @Before
    fun wipePrefs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun legacyHermesConfigurationSurvivesMigration(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "instrumentation-token-0123456789"
        // Semina con lo store legacy, poi costruisci il nuovo store (init esegue la migrazione).
        val legacy = AndroidBridgeConfigurationStore(context)
        assertTrue(legacy.saveConfiguration("https://first.example", token))

        val store = AndroidProviderConfigStore(context)
        try {
            assertEquals(ProviderId.HERMES, store.selectedProviderId())
            assertEquals("https://first.example", store.providerConfig(ProviderId.HERMES).baseUrl)
            assertEquals(token, store.apiKey(ProviderId.HERMES))
            assertEquals(token, store.bearerToken())
            assertEquals("https://first.example", store.baseUrl())

            val preferences = context.getSharedPreferences(
                prefsName,
                android.content.Context.MODE_PRIVATE,
            )
            assertTrue(preferences.all.values.none { it == token })
        } finally {
            store.clearApiKey(ProviderId.HERMES)
        }
    }

    @Test
    fun perProviderKeysAreIsolatedEncryptedAndClearable(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidProviderConfigStore(context)
        val hermesToken = "hermes-token-0123456789"
        val openaiToken = "sk-openai-token-0123456789"
        try {
            assertTrue(store.saveProviderConfig(ProviderId.HERMES, apiKey = hermesToken))
            assertTrue(store.saveProviderConfig(ProviderId.OPENAI, apiKey = openaiToken))

            assertEquals(openaiToken, store.apiKey(ProviderId.OPENAI))
            assertNull(store.apiKey(ProviderId.ANTHROPIC))

            val preferences = context.getSharedPreferences(
                prefsName,
                android.content.Context.MODE_PRIVATE,
            )
            assertTrue(preferences.all.values.none { it == openaiToken || it == hermesToken })

            assertTrue(store.clearApiKey(ProviderId.OPENAI))
            assertNull(store.apiKey(ProviderId.OPENAI))
            // Cancellare OPENAI non tocca HERMES.
            assertEquals(hermesToken, store.apiKey(ProviderId.HERMES))
        } finally {
            store.clearApiKey(ProviderId.HERMES)
            store.clearApiKey(ProviderId.OPENAI)
        }
    }

    @Test
    fun legacyContractSemanticsPreserved(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidProviderConfigStore(context)
        val original = "atomic-token-0123456789"
        val preferences = context.getSharedPreferences(
            prefsName,
            android.content.Context.MODE_PRIVATE,
        )
        try {
            assertTrue(store.saveConfiguration("https://first.example", original))
            assertFalse(store.saveConfiguration("http://invalid.example", "replacement-token-012345"))
            assertEquals("https://first.example", store.baseUrl())
            assertEquals(original, store.bearerToken())

            assertTrue(store.saveConfiguration("https://second.example", bearerToken = null))
            assertEquals("https://second.example", store.baseUrl())
            assertEquals(original, store.bearerToken())

            // Token corrotto non può essere preservato come configurato.
            store.clearApiKey(ProviderId.HERMES)
            assertTrue(store.saveBaseUrl("https://third.example"))
            assertTrue(
                preferences.edit()
                    .putString("provider.hermes.key_v1", "not-a-gcm-payload")
                    .commit(),
            )
            assertFalse(store.saveConfiguration("https://fourth.example", bearerToken = null))
            assertEquals("https://third.example", store.baseUrl())
            assertNull(store.bearerToken())
        } finally {
            store.clearApiKey(ProviderId.HERMES)
        }
    }
}
