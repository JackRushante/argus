package dev.argus.automation.connectivity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsConnectivityStateStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences(PrefsConnectivityStateStore.PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Before
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `snapshot and pending envelope survive process recreation then complete atomically`() {
        val sourceKey = "a".repeat(64)
        val envelope = TriggerEnvelope(
            TriggerEventId("connectivity:${"b".repeat(64)}"),
            TriggerEvent.ConnectivityChanged(ConnMedium.BT, ConnState.CONNECTED, "Auto"),
        )
        val snapshot = ConnectivityStateSnapshot(ConnState.CONNECTED, "Auto", 123L)

        PrefsConnectivityStateStore(context).record(sourceKey, snapshot, envelope)

        PrefsConnectivityStateStore(context).apply {
            assertEquals(snapshot, last(sourceKey))
            assertEquals(envelope, pending(sourceKey))
            assertEquals(listOf(sourceKey to envelope), pending())
            complete(sourceKey, envelope.id.value)
        }

        assertTrue(PrefsConnectivityStateStore(context).pending().isEmpty())
        assertEquals(snapshot, PrefsConnectivityStateStore(context).last(sourceKey))
    }

    @Test
    fun `corrupt pending record is ignored without losing the last known state`() {
        val sourceKey = "c".repeat(64)
        val snapshot = ConnectivityStateSnapshot(ConnState.DISCONNECTED, null, 456L)
        PrefsConnectivityStateStore(context).record(sourceKey, snapshot)
        preferences.edit()
            .putString("$sourceKey.pending_id", "connectivity:${"d".repeat(64)}")
            .putString("$sourceKey.pending_medium", ConnMedium.BT.name)
            .putString("$sourceKey.pending_state", ConnState.CONNECTED.name)
            .putString("$sourceKey.pending_name", "invalid\u0000name")
            .commit()

        PrefsConnectivityStateStore(context).apply {
            assertEquals(snapshot, last(sourceKey))
            assertTrue(pending().isEmpty())
        }
    }
}
