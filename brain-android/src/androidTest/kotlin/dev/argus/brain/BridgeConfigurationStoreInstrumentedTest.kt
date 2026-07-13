package dev.argus.brain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
