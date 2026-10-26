package dev.argus.automation.triggers

import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceEventIngressTest {
    private val dispatched = mutableListOf<TriggerEnvelope>()
    private val store = InMemoryBatteryLevelStore()
    private var ingress = newIngress()

    private fun newIngress() = DeviceEventIngress(DeviceEventParser(), store) { dispatched += it }

    private val events get() = dispatched.map { it.event }

    // --- Batteria --------------------------------------------------------------------------------

    @Test
    fun `the first battery reading of a fresh monitor is only a reference and never fires`() = runTest {
        ingress.startBatteryObservation(recoverMissed = false)
        ingress.onBatteryLevel(15, atMillis = 1_000L)

        assertTrue(dispatched.isEmpty())
        assertEquals(BatteryReference(15, 1_000L), store.last())
    }

    @Test
    fun `a change of level is delivered with previous and current so the matcher can see the crossing`() =
        runTest {
            ingress.startBatteryObservation(recoverMissed = false)
            ingress.onBatteryLevel(21, 1_000L)
            ingress.onBatteryLevel(20, 2_000L)
            ingress.onBatteryLevel(19, 3_000L)
            // Stesso livello (temperatura, tensione...): nessun evento.
            ingress.onBatteryLevel(19, 4_000L)

            assertEquals(
                listOf(
                    TriggerEvent.BatteryLevelChanged(21, 20),
                    TriggerEvent.BatteryLevelChanged(20, 19),
                ),
                events,
            )
        }

    @Test
    fun `a restart at the same level never fires again`() = runTest {
        ingress.startBatteryObservation(recoverMissed = false)
        ingress.onBatteryLevel(19, 1_000L)

        // Il processo muore e rinasce: il monitor era attivo in continuo (kill), stesso livello.
        ingress = newIngress()
        ingress.startBatteryObservation(recoverMissed = true)
        ingress.onBatteryLevel(19, 60_000L)

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `after a kill a crossing that happened meanwhile is recovered once`() = runTest {
        ingress.startBatteryObservation(recoverMissed = false)
        ingress.onBatteryLevel(22, 1_000L)

        ingress = newIngress()
        ingress.startBatteryObservation(recoverMissed = true)
        ingress.onBatteryLevel(18, 60_000L)
        // La stessa lettura ripetuta (sticky consegnato anche al receiver) non raddoppia lo scatto.
        ingress.onBatteryLevel(18, 60_050L)

        assertEquals(listOf<TriggerEvent>(TriggerEvent.BatteryLevelChanged(22, 18)), events)
    }

    @Test
    fun `after an explicit stop the saved level is forgotten and the next start is a fresh reference`() =
        runTest {
            ingress.startBatteryObservation(recoverMissed = false)
            ingress.onBatteryLevel(50, 1_000L)
            ingress.stopBatteryObservation()
            assertNull(store.last())

            ingress.startBatteryObservation(recoverMissed = true)
            ingress.onBatteryLevel(10, 2_000L)

            assertTrue(dispatched.isEmpty())
        }

    @Test
    fun `a saved level too old is only a reference even after a kill`() = runTest {
        ingress.startBatteryObservation(recoverMissed = false)
        ingress.onBatteryLevel(60, 1_000L)

        ingress = newIngress()
        ingress.startBatteryObservation(recoverMissed = true)
        ingress.onBatteryLevel(10, 1_000L + DeviceEventIngress.MAX_RECOVERY_AGE_MILLIS + 1)

        assertTrue(dispatched.isEmpty())
        assertEquals(10, store.last()?.level)
    }

    @Test
    fun `the level is saved before the delivery so a failed delivery cannot fire twice`() = runTest {
        var failing = true
        val flaky = DeviceEventIngress(DeviceEventParser(), store) {
            if (failing) error("dispatch failed")
            dispatched += it
        }
        flaky.startBatteryObservation(recoverMissed = false)
        flaky.onBatteryLevel(21, 1_000L)
        runCatching { flaky.onBatteryLevel(19, 2_000L) }
        assertEquals(19, store.last()?.level)

        failing = false
        flaky.onBatteryLevel(19, 3_000L)
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `incoherent levels are ignored`() = runTest {
        ingress.startBatteryObservation(recoverMissed = false)
        ingress.onBatteryLevel(-1, 1_000L)
        ingress.onBatteryLevel(101, 1_000L)
        assertNull(store.last())
    }

    // --- Modalità --------------------------------------------------------------------------------

    @Test
    fun `a mode change to the same value does not fire and the baseline never does`() = runTest {
        ingress.setModeBaseline(ModeKind.RINGER, "normal")
        ingress.onMode(ModeKind.RINGER, "normal", 1_000L)
        assertTrue(dispatched.isEmpty())

        ingress.onMode(ModeKind.RINGER, "silent", 2_000L)
        ingress.onMode(ModeKind.RINGER, "silent", 3_000L)
        ingress.onMode(ModeKind.RINGER, "normal", 4_000L)

        assertEquals(
            listOf(
                TriggerEvent.DeviceModeChanged(ModeKind.RINGER, to = "silent", from = "normal"),
                TriggerEvent.DeviceModeChanged(ModeKind.RINGER, to = "normal", from = "silent"),
            ),
            events,
        )
    }

    @Test
    fun `values outside the vocabulary are ignored and the modes are independent`() = runTest {
        ingress.setModeBaseline(ModeKind.DND, "off")
        ingress.setModeBaseline(ModeKind.RINGER, "normal")

        // «Solo sveglie» e valori ignoti non hanno un valore nel dominio.
        ingress.onMode(ModeKind.DND, "alarms", 1_000L)
        ingress.onMode(ModeKind.DND, "silent", 1_000L)
        assertTrue(dispatched.isEmpty())

        ingress.onMode(ModeKind.DND, "priority", 2_000L)
        // Tornare a off dopo un valore ignoto è un cambio rispetto all'ultimo valore NOTO (priority).
        ingress.onMode(ModeKind.DND, "off", 3_000L)
        ingress.onMode(ModeKind.RINGER, "vibrate", 4_000L)

        assertEquals(
            listOf<TriggerEvent>(
                TriggerEvent.DeviceModeChanged(ModeKind.DND, "priority", "off"),
                TriggerEvent.DeviceModeChanged(ModeKind.DND, "off", "priority"),
                TriggerEvent.DeviceModeChanged(ModeKind.RINGER, "vibrate", "normal"),
            ),
            events,
        )
    }

    @Test
    fun `a forgotten mode starts again from a new baseline`() = runTest {
        ingress.setModeBaseline(ModeKind.DND, "off")
        ingress.forgetMode(ModeKind.DND)
        ingress.setModeBaseline(ModeKind.DND, "total")
        ingress.onMode(ModeKind.DND, "total", 1_000L)
        assertTrue(dispatched.isEmpty())
    }

    // --- Schermo, uscita audio, riproduzione ---------------------------------------------------------

    @Test
    fun `screen audio and media events are delivered as they are`() = runTest {
        ingress.onScreen(ScreenEvent.UNLOCKED, 1_000L)
        ingress.onAudioOutput(AudioOutputKind.WIRED, ConnState.CONNECTED, "audio-port-7-3", 2_000L)
        ingress.onMedia(MediaPlayState.PLAYING, 3_000L)

        assertEquals(
            listOf<TriggerEvent>(
                TriggerEvent.ScreenChanged(ScreenEvent.UNLOCKED),
                TriggerEvent.AudioOutputChanged(AudioOutputKind.WIRED, ConnState.CONNECTED),
                TriggerEvent.MediaPlaybackChanged(MediaPlayState.PLAYING),
            ),
            events,
        )
        // ANY non è un'uscita reale: nessun evento.
        ingress.onAudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED, "x", 4_000L)
        assertEquals(3, dispatched.size)
    }
}

internal class InMemoryBatteryLevelStore : BatteryLevelStore {
    private var value: BatteryReference? = null
    override fun last(): BatteryReference? = value
    override fun record(level: Int, atMillis: Long) {
        value = BatteryReference(level, atMillis)
    }
    override fun clear() {
        value = null
    }
}
