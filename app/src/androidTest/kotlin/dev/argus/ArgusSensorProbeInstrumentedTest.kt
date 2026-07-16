package dev.argus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.engine.model.SensorKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica di sola lettura (non tocca lo store né le regole): sul OnePlus reale il probe deve
 * pubblicare `sensor.significant_motion` ora che il backend è collegato. Chiude il punto 1 del
 * gate device (§7.8) senza richiedere Lorenzo. L'hardware è confermato: SMD one-shot wake-up.
 */
@RunWith(AndroidJUnit4::class)
class ArgusSensorProbeInstrumentedTest {
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun significantMotionCapabilityIsPublishedOnRealHardware(): Unit = runBlocking {
        val snapshot = services.capabilityProbe().probe(
            services.deviceStateSnapshotProvider().current(),
        )
        val wire = "sensor.${SensorKind.SIGNIFICANT_MOTION.wireName}"
        println("ArgusSensorProbe: availableTriggers contiene $wire = ${wire in snapshot.availableTriggers}")
        assertTrue(
            "Il probe non pubblica $wire: availableTriggers=${snapshot.availableTriggers}",
            wire in snapshot.availableTriggers,
        )
    }
}
