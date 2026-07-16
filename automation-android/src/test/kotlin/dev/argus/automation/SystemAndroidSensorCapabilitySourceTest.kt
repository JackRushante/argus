package dev.argus.automation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.SensorKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemAndroidSensorCapabilitySourceTest {
    @Test fun `sensor manager metadata is captured without vendor names or samples`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(SensorManager::class.java)
        val significant = sensor(
            type = Sensor.TYPE_SIGNIFICANT_MOTION,
            reportingMode = Sensor.REPORTING_MODE_ONE_SHOT,
            wakeUp = true,
            fifo = 64,
            minDelayUs = 25_000,
            maxDelayUs = 2_000_000,
        )
        val steps = sensor(
            type = Sensor.TYPE_STEP_COUNTER,
            reportingMode = Sensor.REPORTING_MODE_ON_CHANGE,
        )
        shadowOf(manager).addSensor(significant)
        shadowOf(manager).addSensor(steps)

        val denied = SystemAndroidSensorCapabilitySource(context).read(false)
        val motion = denied.single { it.kind == SensorKind.SIGNIFICANT_MOTION }
        assertEquals(SensorReportingMode.ONE_SHOT, motion.reportingMode)
        assertTrue(motion.wakeUp)
        assertEquals(64, motion.fifoMaxEventCount)
        assertEquals(25_000, motion.minDelayUs)
        assertEquals(2_000_000, motion.maxDelayUs)
        assertFalse(denied.single { it.kind == SensorKind.STEP_COUNTER }.permissionGranted)

        val granted = SystemAndroidSensorCapabilitySource(context).read(true)
        assertTrue(granted.single { it.kind == SensorKind.STEP_COUNTER }.permissionGranted)
    }

    private fun sensor(
        type: Int,
        reportingMode: Int,
        wakeUp: Boolean = false,
        fifo: Int = 0,
        minDelayUs: Int = 0,
        maxDelayUs: Int = 0,
    ): Sensor {
        val sensor = org.robolectric.shadows.ShadowSensor.newInstance(type)
        val flags = (reportingMode shl 1) or if (wakeUp) 1 else 0
        ReflectionHelpers.setField(sensor, "mFlags", flags)
        ReflectionHelpers.setField(sensor, "mFifoMaxEventCount", fifo)
        ReflectionHelpers.setField(sensor, "mMaxDelay", maxDelayUs)
        shadowOf(sensor).setMinDelay(minDelayUs)
        return sensor
    }
}
