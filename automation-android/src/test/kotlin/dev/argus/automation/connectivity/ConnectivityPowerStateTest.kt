package dev.argus.automation.connectivity

import android.content.Intent
import android.os.BatteryManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import dev.argus.engine.model.ConnState
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ConnectivityPowerStateTest {
    @Test
    fun `missing battery snapshot remains unknown instead of inventing disconnect`() {
        assertNull(powerConnectionState(null))
        assertNull(powerConnectionState(Intent(Intent.ACTION_BATTERY_CHANGED)))
    }

    @Test
    fun `plugged extra maps every powered source to connected`() {
        fun state(plugged: Int) = powerConnectionState(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged),
        )

        assertEquals(ConnState.DISCONNECTED, state(0))
        assertEquals(ConnState.CONNECTED, state(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(ConnState.CONNECTED, state(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals(ConnState.CONNECTED, state(BatteryManager.BATTERY_PLUGGED_WIRELESS))
    }
}
