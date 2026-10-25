package dev.argus.automation.connectivity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityMonitorMarkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `marker defaults to inactive and survives process recreation`() {
        PrefsConnectivityMonitorMarker(context).setActive(false)
        assertFalse(PrefsConnectivityMonitorMarker(context).wasActive())

        PrefsConnectivityMonitorMarker(context).setActive(true)
        assertTrue(PrefsConnectivityMonitorMarker(context).wasActive())

        PrefsConnectivityMonitorMarker(context).setActive(false)
        assertFalse(PrefsConnectivityMonitorMarker(context).wasActive())
    }

    @Test
    fun `explicit backend stop clears the marker so the next start is only a reference`() = runTest {
        val marker = RecordingMarker(active = true)
        val backend = AndroidConnectivitySentinelBackend(
            context,
            ConnectivitySentinelStatus(),
            monitorMarker = marker,
        )

        assertTrue(backend.stop())

        assertEquals(listOf(false), marker.writes)
        assertFalse(marker.wasActive())
    }
}

private class RecordingMarker(private var active: Boolean) : ConnectivityMonitorMarker {
    val writes = mutableListOf<Boolean>()
    override fun wasActive(): Boolean = active
    override fun setActive(active: Boolean) {
        this.active = active
        writes += active
    }
}
