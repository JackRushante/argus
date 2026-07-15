package dev.argus.automation.geofence

import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.DeviceLocation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Il caso che ha motivato questa classe è il primo test: device fermo al CENTRO dell'area e
 * framework che annuncia EXIT — osservato sul campo il 2026-07-15, due volte in tre minuti.
 */
class LocationBackedTransitionVerifierTest {
    private val id = AutomationId("casa")
    private val area = Trigger.Geofence(
        lat = 45.0,
        lng = 9.0,
        radiusM = 200.0,
        transition = Transition.EXIT,
    )

    @Test
    fun `an exit announced while sitting at the centre is refused`() = runTest {
        assertFalse(verifier(DeviceLocation(45.0, 9.0)).accepts(id, Transition.EXIT))
    }

    @Test
    fun `an exit is believed when the position really is outside`() = runTest {
        // ~500 m a nord: fuori dai 200 m, ben oltre il margine di rumore.
        assertTrue(verifier(DeviceLocation(45.0045, 9.0)).accepts(id, Transition.EXIT))
    }

    @Test
    fun `near the boundary the framework keeps the last word`() = runTest {
        // ~189 m dal centro: dentro, ma entro i 25 m di isteresi. Lì non decidiamo noi.
        assertTrue(verifier(DeviceLocation(45.0017, 9.0)).accepts(id, Transition.EXIT))
    }

    @Test
    fun `an enter announced from far away is refused`() = runTest {
        assertFalse(verifier(DeviceLocation(45.0045, 9.0)).accepts(id, Transition.ENTER))
    }

    @Test
    fun `an unreadable position leaves the framework in charge`() = runTest {
        assertTrue(verifier(null).accepts(id, Transition.EXIT))
        assertTrue(
            LocationBackedTransitionVerifier({ area }) { error("provider giù") }
                .accepts(id, Transition.EXIT),
        )
    }

    /** Un fix non risolto (0,0) non è una smentita: è assenza di prova. */
    @Test
    fun `an unresolved fix is not evidence`() = runTest {
        assertTrue(verifier(DeviceLocation(0.0, 0.0)).accepts(id, Transition.EXIT))
    }

    @Test
    fun `a rule that no longer exists is not judged`() = runTest {
        val verifier = LocationBackedTransitionVerifier({ null }) { DeviceLocation(45.0, 9.0) }
        assertTrue(verifier.accepts(id, Transition.EXIT))
    }

    /** Un trigger non-geofence non ha una geometria da confrontare. */
    @Test
    fun `a non geofence trigger is not judged`() = runTest {
        val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
        val verifier = LocationBackedTransitionVerifier({ time }) { DeviceLocation(45.0, 9.0) }
        assertTrue(verifier.accepts(id, Transition.EXIT))
    }

    private fun verifier(fix: DeviceLocation?) =
        LocationBackedTransitionVerifier({ area }, CurrentLocationProvider { fix })
}
