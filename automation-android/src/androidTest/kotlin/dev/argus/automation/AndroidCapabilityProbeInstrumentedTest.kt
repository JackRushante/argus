package dev.argus.automation

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.StateKeys
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
            val probe = AndroidCapabilityProbe(context, gateway, StaticWhitelist(contact))
            val manifest = probe.probe(DeviceState())
            val policy = probe.current()

            val expectedAndroid = Build.VERSION.RELEASE.substringBefore('.').toIntOrNull()
                ?: Build.VERSION.SDK_INT
            assertEquals(Build.MODEL, manifest.deviceModel)
            assertEquals(expectedAndroid, manifest.androidVersion)
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
}

private class StaticWhitelist(
    private val contact: WhitelistedContact,
) : ContactWhitelistStore {
    override suspend fun all() = listOf(contact)
    override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(listOf(contact))
    override suspend fun upsert(contact: WhitelistedContact) = Unit
    override suspend fun remove(conversationId: String) = Unit
}
