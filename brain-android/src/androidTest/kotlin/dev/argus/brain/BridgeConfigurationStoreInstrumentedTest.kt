package dev.argus.brain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeConfigurationStoreInstrumentedTest {
    @Test
    fun keystoreBackedBearerRoundTripsWithoutPlaintextPreferences(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidBridgeConfigurationStore(context)
        val fixture = "instrumentation-token-0123456789"
        try {
            assertTrue(store.clearBearerToken())
            assertTrue(store.saveBearerToken(fixture))
            assertEquals(fixture, store.bearerToken())
            val preferences = context.getSharedPreferences(
                "argus_bridge_private",
                android.content.Context.MODE_PRIVATE,
            )
            assertTrue(preferences.all.values.none { it == fixture })
        } finally {
            store.clearBearerToken()
        }
        assertNull(store.bearerToken())
    }

    @Test
    fun configurationUpdateIsAtomicAndBlankTokenRetainsCiphertext(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidBridgeConfigurationStore(context)
        val original = "atomic-token-0123456789"
        try {
            store.clearBearerToken()
            assertTrue(store.saveConfiguration("https://first.example", original))
            assertFalse(store.saveConfiguration("http://invalid.example", "replacement-token-012345"))
            assertEquals("https://first.example", store.baseUrl())
            assertEquals(original, store.bearerToken())

            assertTrue(store.saveConfiguration("https://second.example", bearerToken = null))
            assertEquals("https://second.example", store.baseUrl())
            assertEquals(original, store.bearerToken())
        } finally {
            store.clearBearerToken()
            store.saveBaseUrl(AndroidBridgeConfigurationStore.DEFAULT_BASE_URL)
        }
    }

    @Test
    fun corruptedExistingTokenCannotBePreservedAsConfigured(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidBridgeConfigurationStore(context)
        val preferences = context.getSharedPreferences(
            "argus_bridge_private",
            android.content.Context.MODE_PRIVATE,
        )
        try {
            store.clearBearerToken()
            assertTrue(store.saveBaseUrl("https://first.example"))
            assertTrue(preferences.edit().putString("bearer_v1", "not-a-gcm-payload").commit())

            assertFalse(store.saveConfiguration("https://second.example", bearerToken = null))
            assertEquals("https://first.example", store.baseUrl())
            assertNull(store.bearerToken())
        } finally {
            store.clearBearerToken()
            store.saveBaseUrl(AndroidBridgeConfigurationStore.DEFAULT_BASE_URL)
        }
    }
}
