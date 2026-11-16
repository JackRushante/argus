package dev.argus.automation.sensor

import dev.argus.engine.model.SensorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8: doppia scossa e faccia in giù, rilevatori puri e backend con un driver finto. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GestureSensorTest {
    private val g = ShakeDetector.GRAVITY

    /** Un colpo forte lungo x preceduto da un campione calmo (il fronte di salita). */
    private fun ShakeDetector.jolt(at: Long): Boolean {
        onSample(0f, 0f, g, at - 10)
        return onSample(g + 20f, 0f, 0f, at)
    }
    private fun ShakeDetector.rest(at: Long) = onSample(0f, 0f, g, at)

    @Test
    fun `a clear double shake fires once`() {
        val detector = ShakeDetector()
        assertFalse(detector.jolt(0))
        assertFalse(detector.rest(50))
        assertFalse(detector.jolt(200))
        assertFalse(detector.jolt(450))
        assertTrue(detector.jolt(700))
        // Subito dopo è sordo: un'altra raffica non riscatta.
        assertFalse(detector.jolt(900))
        assertFalse(detector.jolt(1_100))
        assertFalse(detector.jolt(1_300))
        assertFalse(detector.jolt(1_500))
    }

    @Test
    fun `a single shake, slow knocks and walking do not fire`() {
        val single = ShakeDetector()
        assertFalse(single.jolt(0))
        assertFalse(single.jolt(200))
        val slow = ShakeDetector()
        (0 until 6).forEach { assertFalse(slow.jolt(it * 800L)) }
        // Passi: picchi intorno a 1,5 g, sotto la soglia.
        val walking = ShakeDetector()
        (0 until 40).forEach { assertFalse(walking.onSample(0f, 0f, g * 1.5f, it * 250L)) }
        // Vibrazione continua sopra soglia: un colpo solo.
        val buzz = ShakeDetector()
        (0 until 50).forEach { assertFalse(buzz.onSample(g + 20f, 0f, 0f, it * 20L)) }
    }

    private fun FaceDownDetector.down(at: Long) = onAcceleration(0.2f, -0.3f, -g, at)
    private fun FaceDownDetector.up(at: Long) = onAcceleration(0f, 0f, g, at)

    @Test
    fun `face down for a second fires once until the phone is lifted`() {
        val detector = FaceDownDetector()
        assertFalse(detector.up(0))
        // Il filtro della gravità impiega qualche campione a girarsi.
        var fired = 0
        (1..40).forEach { if (detector.down(it * 60L)) fired++ }
        assertEquals(1, fired)
        // Rialzato e rigirato: scatta di nuovo.
        (41..60).forEach { detector.up(it * 60L) }
        (61..100).forEach { if (detector.down(it * 60L)) fired++ }
        assertEquals(2, fired)
    }

    @Test
    fun `face down needs about a second and the proximity sensor when there is one`() {
        val quick = FaceDownDetector()
        quick.up(0)
        (1..10).forEach { assertFalse(quick.down(it * 60L)) }
        (11..20).forEach { quick.up(it * 60L) }

        val uncovered = FaceDownDetector()
        uncovered.proximityNear = false
        (0..40).forEach { assertFalse(uncovered.down(it * 60L)) }
        // Il tavolo copre il sensore: il conteggio riparte da qui.
        assertFalse(uncovered.onProximity(true, 2_500))
        assertFalse(uncovered.down(3_000))
        assertTrue(uncovered.down(3_600))
    }

    private class FakeDriver(
        override val accelerometerAvailable: Boolean = true,
        override val proximityAvailable: Boolean = true,
        var screenOn: Boolean = true,
    ) : GestureSensorDriver {
        var samples: GestureSamples? = null
        var proximity = false
        var starts = 0
        var watching: ((Boolean) -> Unit)? = null

        override fun interactive() = screenOn
        override fun start(samples: GestureSamples, proximity: Boolean): Boolean {
            this.samples = samples
            this.proximity = proximity
            starts++
            return true
        }
        override fun stop() {
            samples = null
        }
        override fun watchScreen(onChange: (Boolean) -> Unit) {
            watching = onChange
        }
        override fun unwatchScreen() {
            watching = null
        }
    }

    @Test
    fun `sensors run only while a rule wants them and the screen is on`() = runTest {
        val driver = FakeDriver(screenOn = false)
        val fired = mutableListOf<SensorKind>()
        val backend = AndroidGestureSensorBackend(driver, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { fired += it }

        assertEquals(SensorRegistrationOutcome.Registered, backend.register(SensorKind.SHAKE))
        // Schermo spento: solo il receiver, nessun sensore.
        assertFalse(backend.sensorsActive)
        assertTrue(driver.watching != null)

        driver.screenOn = true
        driver.watching!!(true)
        assertTrue(backend.sensorsActive)
        assertFalse(driver.proximity, "la prossimità serve solo al faccia in giù")

        assertEquals(SensorRegistrationOutcome.Registered, backend.register(SensorKind.FLIP_FACE_DOWN))
        assertTrue(driver.proximity)
        assertEquals(SensorRegistrationOutcome.AlreadyRegistered, backend.register(SensorKind.SHAKE))

        val samples = driver.samples!!
        listOf(100L, 300L, 550L, 800L).forEach {
            samples.onAcceleration(0f, 0f, g, it - 10)
            samples.onAcceleration(g + 20f, 0f, 0f, it)
        }
        assertEquals(listOf(SensorKind.SHAKE), fired)

        driver.watching!!(false)
        assertFalse(backend.sensorsActive)

        assertTrue(backend.cancel(SensorKind.SHAKE))
        assertTrue(backend.cancel(SensorKind.FLIP_FACE_DOWN))
        assertEquals(null, driver.watching, "senza regole niente receiver")
    }

    @Test
    fun `other kinds and phones without an accelerometer are unavailable`() = runTest {
        val backend = AndroidGestureSensorBackend(FakeDriver(), backgroundScope) { }
        assertEquals(SensorRegistrationOutcome.Unavailable, backend.register(SensorKind.SIGNIFICANT_MOTION))
        val none = AndroidGestureSensorBackend(FakeDriver(accelerometerAvailable = false), backgroundScope) { }
        assertEquals(SensorRegistrationOutcome.Unavailable, none.register(SensorKind.SHAKE))
    }

    @Test
    fun `the composite routes each kind to its backend`() = runTest {
        val gestures = AndroidGestureSensorBackend(FakeDriver(), backgroundScope) { }
        val composite = CompositeSensorTriggerBackend(mapOf(SensorKind.SHAKE to gestures))
        assertEquals(SensorRegistrationOutcome.Registered, composite.register(SensorKind.SHAKE))
        assertEquals(SensorRegistrationOutcome.Unavailable, composite.register(SensorKind.STEP_COUNTER))
        assertTrue(composite.cancel(SensorKind.STEP_COUNTER))
    }
}
