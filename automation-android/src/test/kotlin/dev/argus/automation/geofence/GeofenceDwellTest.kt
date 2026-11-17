package dev.argus.automation.geofence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8: geofence DWELL, ingresso verificato, ricontrollo dopo l'attesa, uscita che annulla, riavvio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeofenceDwellTest {
    private val id = AutomationId("home")
    private val fingerprint = ApprovalFingerprint("d".repeat(64))
    private val delay = 10 * 60_000L

    private class FakeTimer : GeofenceDwellTimer {
        val scheduled = mutableMapOf<AutomationId, Long>()
        override fun schedule(automationId: AutomationId, dueAtMillis: Long) {
            scheduled[automationId] = dueAtMillis
        }
        override fun cancel(automationId: AutomationId) {
            scheduled.remove(automationId)
        }
    }

    private inner class World(
        var now: Long = 1_000_000L,
        var inside: Boolean = true,
        val dwellStore: GeofenceDwellStore = MemoryGeofenceDwellStore(),
        val state: MemoryGeofenceStateStore = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
            activate(id, fingerprint)
        },
        val timer: FakeTimer = FakeTimer(),
    ) {
        val delivered = mutableListOf<TriggerEnvelope>()
        val checked = mutableListOf<Transition>()
        val ingress = GeofenceEventIngress(
            state,
            GeofenceEventDispatcher { delivered += it },
            verifier = { _, transition ->
                checked += transition
                transition != Transition.DWELL || inside
            },
            dwell = GeofenceDwellSupport(
                delayOf = { if (it == id) delay else null },
                store = dwellStore,
                timer = timer,
                nowMillis = { now },
            ),
        )
    }

    @Test
    fun `an entry waits for the delay and fires once if the phone is still inside`() = runTest {
        val world = World()
        assertTrue(world.ingress.onTransition(id, fingerprint, Transition.ENTER))
        // L'ingresso non va al motore: parte l'attesa.
        assertTrue(world.delivered.isEmpty())
        assertEquals(world.now + delay, world.timer.scheduled[id])

        world.now += delay
        assertTrue(world.ingress.onDwellDue(id))
        val event = world.delivered.single()
        assertEquals(TriggerEvent.GeofenceTransitioned(id, Transition.DWELL, fingerprint), event.event)
        assertEquals("geofence:${fingerprint.value}:dwell:1", event.id.value)
        assertNull(world.dwellStore.get(id))
        // Un secondo ricontrollo non riscatta.
        assertFalse(world.ingress.onDwellDue(id))
        assertEquals(1, world.delivered.size)
    }

    @Test
    fun `leaving before the delay cancels the dwell`() = runTest {
        val world = World()
        world.ingress.onTransition(id, fingerprint, Transition.ENTER)
        world.now += delay / 2
        world.ingress.onTransition(id, fingerprint, Transition.EXIT)
        assertNull(world.dwellStore.get(id))
        assertNull(world.timer.scheduled[id])
        world.now += delay
        assertFalse(world.ingress.onDwellDue(id))
        // 0.8: niente DWELL; l'uscita va al motore solo come possibile FINE di una regola-intervallo (una regola
        // DWELL senza fine non combacia con un EXIT).
        assertEquals(
            listOf(Transition.EXIT),
            world.delivered.map { (it.event as TriggerEvent.GeofenceTransitioned).transition },
        )
    }

    @Test
    fun `a position that says outside at the check does not fire`() = runTest {
        val world = World(inside = false)
        world.ingress.onTransition(id, fingerprint, Transition.ENTER)
        world.now += delay
        assertFalse(world.ingress.onDwellDue(id))
        assertTrue(Transition.DWELL in world.checked)
        assertTrue(world.delivered.isEmpty())
        assertNull(world.dwellStore.get(id))
    }

    @Test
    fun `an early alarm reschedules and a repeated entry keeps the deadline`() = runTest {
        val world = World()
        world.ingress.onTransition(id, fingerprint, Transition.ENTER)
        val due = world.now + delay
        world.now += 60_000L
        assertFalse(world.ingress.onDwellDue(id))
        assertEquals(due, world.timer.scheduled[id])
        // Il framework ripete l'ENTER (duplicato): la scadenza non si sposta.
        world.ingress.onTransition(id, fingerprint, Transition.ENTER)
        assertEquals(due, world.dwellStore.get(id)?.dueAtMillis)
    }

    @Test
    fun `a restart keeps the pending dwell, re-arms its alarm or checks it when due`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val persisted = PrefsGeofenceDwellStore(context)
        val first = World(dwellStore = persisted)
        first.ingress.onTransition(id, fingerprint, Transition.ENTER)
        val due = first.now + delay

        // Nuovo processo, prima della scadenza: la sveglia torna (un riavvio del telefono la cancella).
        val early = World(now = first.now + 1_000, dwellStore = PrefsGeofenceDwellStore(context), state = first.state)
        assertEquals(emptyList(), early.ingress.recoverDwells(setOf(id)))
        assertEquals(due, early.timer.scheduled[id])

        // Nuovo processo dopo la scadenza: si ricontrolla subito.
        val late = World(now = due + 5_000, dwellStore = PrefsGeofenceDwellStore(context), state = first.state)
        assertEquals(listOf(id), late.ingress.recoverDwells(setOf(id)))
        assertEquals(Transition.DWELL, (late.delivered.single().event as TriggerEvent.GeofenceTransitioned).transition)
        assertNull(PrefsGeofenceDwellStore(context).get(id))
    }

    @Test
    fun `a disarmed rule forgets its pending dwell`() = runTest {
        val world = World()
        world.ingress.onTransition(id, fingerprint, Transition.ENTER)
        world.ingress.pruneDwells(emptySet())
        assertNull(world.dwellStore.get(id))
        assertNull(world.timer.scheduled[id])
    }

    @Test
    fun `enter and exit rules are delivered as before`() = runTest {
        val world = World()
        val other = AutomationId("office")
        world.state.prepare(GeofenceRegistration(other, fingerprint, 45.0, 9.0, 150f))
        world.state.activate(other, fingerprint)
        world.ingress.onTransition(other, fingerprint, Transition.ENTER)
        assertEquals(Transition.ENTER, (world.delivered.single().event as TriggerEvent.GeofenceTransitioned).transition)
        assertTrue(world.dwellStore.all().isEmpty())
    }
}
