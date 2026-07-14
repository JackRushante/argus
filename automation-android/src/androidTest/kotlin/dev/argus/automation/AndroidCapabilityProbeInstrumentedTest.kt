package dev.argus.automation

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.brain.AndroidBridgeConfigurationStore
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.StateKeys
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.DeviceState
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCapabilityProbeInstrumentedTest {
    @Test
    fun realApi36CapabilitySnapshotIsCoherent() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val gateway = ShizukuGateway(context)
        try {
            val contact = WhitelistedContact("Fixture", "fixture:conversation")
            val probe = AndroidCapabilityProbe(
                context,
                gateway,
                StaticWhitelist(contact),
                realReadiness(context),
            )
            val manifest = probe.probe(DeviceState())
            val policy = probe.current()

            val expectedAndroid = Build.VERSION.RELEASE.substringBefore('.').toIntOrNull()
                ?: Build.VERSION.SDK_INT
            assertEquals(Build.MODEL, manifest.deviceModel)
            assertEquals(expectedAndroid, manifest.androidVersion)
            assertEquals(Build.VERSION.SDK_INT, manifest.androidApi)
            assertEquals(StateKeys.ALL, manifest.stateKeys)
            assertEquals(listOf(contact), manifest.whitelistedContacts)
            assertEquals(
                gateway.status() == ShizukuGatewayStatus.AUTHORIZED,
                manifest.shizukuAvailable,
            )
            assertTrue(CapabilityIds.TRIGGER_TIME in policy.availableCapabilities)
            assertTrue("vision.analyze" in manifest.unavailableTools)
            assertFalse(manifest.availableTools.any(String::isBlank))
        } finally {
            gateway.close()
        }
    }

    /**
     * Gate device P1-5: il package instrumentato non ha listener access né battery exemption.
     * Le letture reali API 36 devono rispondere false senza lanciare — mai default permissivi —
     * e la probe non deve pubblicare capability notification o generative. Nessun grant globale
     * viene modificato da questo test.
     */
    @Test
    fun listenerAndBatteryGateCapabilitiesFailClosedOnRealDevice() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val gateway = ShizukuGateway(context)
        try {
            val state = SystemAndroidCapabilityStateSource(context, gateway).read()
            assertFalse(state.notificationListenerGranted)
            assertFalse(state.batteryOptimizationExempt)

            val probe = AndroidCapabilityProbe(
                context,
                gateway,
                StaticWhitelist(WhitelistedContact("Fixture", "fixture:conversation")),
                realReadiness(context),
            )
            val policy = probe.current()
            assertFalse(CapabilityIds.TRIGGER_NOTIFICATION in policy.availableCapabilities)
            assertFalse(CapabilityIds.ACTION_INVOKE_LLM in policy.availableCapabilities)
            assertFalse(ActionCapabilities.WHATSAPP_REPLY in policy.availableCapabilities)
            assertFalse(GenerativeContract.TOOL_WHATSAPP_REPLY in policy.availableCapabilities)
            assertEquals(
                AndroidCapabilityProbe.REASON_NOTIFICATION_LISTENER,
                probe.probe(DeviceState())
                    .unavailableTools[GenerativeContract.TOOL_WHATSAPP_REPLY],
            )
        } finally {
            gateway.close()
        }
    }

    /** Store reali del package di test: bearer assente e privacy mai accettata → not ready. */
    private fun realReadiness(context: Context) = AndroidGenerativeRuntimeReadiness(
        AndroidBridgeConfigurationStore(context),
        AndroidAppPreferencesStore(context),
    )
}

private class StaticWhitelist(
    private val contact: WhitelistedContact,
) : ContactWhitelistStore {
    override suspend fun all() = listOf(contact)
    override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(listOf(contact))
    override suspend fun upsert(contact: WhitelistedContact) = Unit
    override suspend fun remove(conversationId: String) = Unit
}
