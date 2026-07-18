package dev.argus.automation.sensor

import android.content.Context
import android.hardware.TriggerEventListener
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.SensorKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Robolectric non fornisce un sensore significant-motion, quindi qui provo solo i path negativi
 * deterministici. Il path positivo (registrazione reale + callback) è il gate fisico di Lorenzo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSignificantMotionBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a non significant-motion kind is unavailable`() = runTest {
        val backend = AndroidSignificantMotionBackend(context, backgroundScope) { }
        assertEquals(
            SensorRegistrationOutcome.Unavailable,
            backend.register(SensorKind.STEP_COUNTER),
        )
    }

    @Test
    fun `without the hardware sensor the registration is unavailable, not a crash`() = runTest {
        val backend = AndroidSignificantMotionBackend(context, backgroundScope) { }
        // Robolectric non registra il default significant-motion sensor.
        assertEquals(
            SensorRegistrationOutcome.Unavailable,
            backend.register(SensorKind.SIGNIFICANT_MOTION),
        )
    }

    @Test
    fun `cancelling an unregistered kind is a no-op success`() = runTest {
        val backend = AndroidSignificantMotionBackend(context, backgroundScope) { }
        assertEquals(true, backend.cancel(SensorKind.SIGNIFICANT_MOTION))
    }

    @Test
    fun `a synchronous one-shot callback cannot leave a dead listener registered`() = runTest {
        val driver = RecordingSignificantMotionDriver(fireSynchronously = true)
        val backend = AndroidSignificantMotionBackend(driver, backgroundScope) { }

        assertEquals(SensorRegistrationOutcome.Registered, backend.register(SensorKind.SIGNIFICANT_MOTION))
        assertEquals(SensorRegistrationOutcome.Registered, backend.register(SensorKind.SIGNIFICANT_MOTION))
        assertEquals(2, driver.requestCalls)
    }

    @Test
    fun `a failed cancel retains the listener and can be retried`() = runTest {
        val driver = RecordingSignificantMotionDriver(
            cancelResults = ArrayDeque(listOf(false, true)),
        )
        val backend = AndroidSignificantMotionBackend(driver, backgroundScope) { }

        assertEquals(SensorRegistrationOutcome.Registered, backend.register(SensorKind.SIGNIFICANT_MOTION))
        assertEquals(false, backend.cancel(SensorKind.SIGNIFICANT_MOTION))
        assertEquals(
            SensorRegistrationOutcome.AlreadyRegistered,
            backend.register(SensorKind.SIGNIFICANT_MOTION),
        )
        assertEquals(true, backend.cancel(SensorKind.SIGNIFICANT_MOTION))
        assertEquals(SensorRegistrationOutcome.Registered, backend.register(SensorKind.SIGNIFICANT_MOTION))
    }
}

private class RecordingSignificantMotionDriver(
    private val fireSynchronously: Boolean = false,
    private val cancelResults: ArrayDeque<Boolean> = ArrayDeque(),
) : SignificantMotionDriver {
    override val available: Boolean = true
    var requestCalls: Int = 0

    override fun request(listener: TriggerEventListener): Boolean {
        requestCalls += 1
        if (fireSynchronously) listener.onTrigger(null)
        return true
    }

    override fun cancel(listener: TriggerEventListener): Boolean =
        cancelResults.removeFirstOrNull() ?: true
}
