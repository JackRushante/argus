package dev.argus.automation.geofence

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GeofenceEventIngressTest {
    /**
     * Bug trovato sul campo il 2026-07-15: il framework ha annunciato EXIT mentre il device era
     * fermo al centro del geofence. Il dedup non poteva vederlo — per lo store quell'ENTER
     * intermedio era reale — quindi la difesa deve stare prima: se la posizione contraddice il
     * bordo, il bordo non esiste.
     */
    @Test
    fun `a transition contradicted by the real position is dropped`() = runTest {
        val id = AutomationId("home")
        val fingerprint = ApprovalFingerprint("a".repeat(64))
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
            activate(id, fingerprint)
        }
        val delivered = mutableListOf<TriggerEnvelope>()
        val ingress = GeofenceEventIngress(
            state,
            GeofenceEventDispatcher { delivered += it },
            verifier = { _, _ -> false },
        )

        assertEquals(false, ingress.onTransition(id, fingerprint, Transition.EXIT))
        assertTrue(delivered.isEmpty())
        // Lo stato non deve avanzare: un bordo scartato non è un bordo avvenuto.
        assertNull(state.get(id)?.lastTransition)
    }

    /** Fail-open esplicito: perdere un attraversamento vero è peggio che accettarne uno spurio. */
    @Test
    fun `an unverifiable position keeps the framework edge`() = runTest {
        val id = AutomationId("far")
        val fingerprint = ApprovalFingerprint("b".repeat(64))
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
            activate(id, fingerprint)
        }
        val delivered = mutableListOf<TriggerEnvelope>()
        val ingress = GeofenceEventIngress(
            state,
            GeofenceEventDispatcher { delivered += it },
            verifier = { _, _ -> true },
        )

        assertTrue(ingress.onTransition(id, fingerprint, Transition.ENTER))
        assertEquals(1, delivered.size)
    }

    @Test
    fun `an enter or an exit from an unknown state is delivered and never a duplicate`() = runTest {
        listOf(Transition.ENTER, Transition.EXIT).forEach { edge ->
            val id = AutomationId("unknown-${edge.name}")
            val fingerprint = ApprovalFingerprint("a".repeat(64))
            val state = MemoryGeofenceStateStore().apply {
                prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
                activate(id, fingerprint)
                // Stato noto «dentro», poi dimenticato al riavvio con la posizione ignota.
                beginTransition(id, fingerprint, Transition.ENTER)!!.also {
                    completeTransition(id, fingerprint, it.sequence)
                }
                markUnknown(id, fingerprint)
            }
            val delivered = mutableListOf<TriggerEnvelope>()
            val ingress = GeofenceEventIngress(state, GeofenceEventDispatcher { delivered += it })

            assertTrue(ingress.onTransition(id, fingerprint, edge))

            assertEquals(1, delivered.size)
            assertEquals(edge, state.get(id)?.lastTransition)
        }
    }

    @Test
    fun `alternating transitions get stable sequence ids while duplicate callbacks are suppressed`() = runTest {
        val id = AutomationId("home")
        val fingerprint = ApprovalFingerprint("a".repeat(64))
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
            activate(id, fingerprint)
        }
        val delivered = mutableListOf<TriggerEnvelope>()
        val ingress = GeofenceEventIngress(state, GeofenceEventDispatcher { delivered += it })

        assertTrue(ingress.onTransition(id, fingerprint, Transition.ENTER))
        assertEquals(false, ingress.onTransition(id, fingerprint, Transition.ENTER))
        assertTrue(ingress.onTransition(id, fingerprint, Transition.EXIT))

        assertEquals(
            listOf(
                "geofence:${fingerprint.value}:1",
                "geofence:${fingerprint.value}:2",
            ),
            delivered.map { it.id.value },
        )
        assertEquals(
            listOf(Transition.ENTER, Transition.EXIT),
            delivered.map { (it.event as TriggerEvent.GeofenceTransitioned).transition },
        )
    }

    @Test
    fun `stale fingerprint or unknown registration never reaches engine`() = runTest {
        val id = AutomationId("home")
        val current = ApprovalFingerprint("b".repeat(64))
        val stale = ApprovalFingerprint("c".repeat(64))
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration(id, current, 45.0, 9.0, 150f))
            activate(id, current)
        }
        val delivered = mutableListOf<TriggerEnvelope>()
        val ingress = GeofenceEventIngress(state, GeofenceEventDispatcher { delivered += it })

        assertEquals(false, ingress.onTransition(id, stale, Transition.EXIT))
        assertEquals(
            false,
            ingress.onTransition(AutomationId("missing"), current, Transition.EXIT),
        )
        assertEquals(emptyList(), delivered)
    }

    @Test
    fun `failed dispatch remains pending and retries the exact same event id after process death`() =
        runTest {
            val id = AutomationId("recover")
            val fingerprint = ApprovalFingerprint("d".repeat(64))
            val state = MemoryGeofenceStateStore().apply {
                prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
                activate(id, fingerprint)
            }
            var failedId: String? = null
            val failing = GeofenceEventIngress(
                state,
                GeofenceEventDispatcher {
                    failedId = it.id.value
                    error("process dies before completion")
                },
            )

            assertFailsWith<IllegalStateException> {
                failing.onTransition(id, fingerprint, Transition.EXIT)
            }
            assertEquals("geofence:${fingerprint.value}:1", failedId)
            assertEquals(Transition.EXIT, state.pending(id, fingerprint)?.transition)

            val retried = mutableListOf<TriggerEnvelope>()
            val restarted = GeofenceEventIngress(state, GeofenceEventDispatcher { retried += it })
            assertEquals(listOf(id), restarted.recoverPending(setOf(id)))
            assertEquals(failedId, retried.single().id.value)
            assertNull(state.pending(id, fingerprint))
            assertEquals(Transition.EXIT, state.get(id)?.lastTransition)
        }

    @Test
    fun `recovery attempts every registration even when the first pending dispatch fails`() =
        runTest {
            val firstId = AutomationId("a-home")
            val secondId = AutomationId("b-office")
            val firstFingerprint = ApprovalFingerprint("e".repeat(64))
            val secondFingerprint = ApprovalFingerprint("f".repeat(64))
            val state = MemoryGeofenceStateStore().apply {
                prepare(GeofenceRegistration(firstId, firstFingerprint, 45.0, 9.0, 150f))
                activate(firstId, firstFingerprint)
                prepare(GeofenceRegistration(secondId, secondFingerprint, 46.0, 10.0, 150f))
                activate(secondId, secondFingerprint)
            }
            val alwaysFail = GeofenceEventIngress(state, GeofenceEventDispatcher { error("process dies") })
            assertFailsWith<IllegalStateException> {
                alwaysFail.onTransition(firstId, firstFingerprint, Transition.EXIT)
            }
            assertFailsWith<IllegalStateException> {
                alwaysFail.onTransition(secondId, secondFingerprint, Transition.ENTER)
            }
            val attempted = mutableListOf<AutomationId>()
            val recovering = GeofenceEventIngress(
                state,
                GeofenceEventDispatcher { envelope ->
                    val id = (envelope.event as TriggerEvent.GeofenceTransitioned).automationId
                    attempted += id
                    if (id == firstId) error("first registration unavailable")
                },
            )

            assertFailsWith<IllegalStateException> {
                recovering.recoverPending(setOf(firstId, secondId))
            }

            assertEquals(listOf(firstId, secondId), attempted)
            assertEquals(Transition.EXIT, state.pending(firstId, firstFingerprint)?.transition)
            assertNull(state.pending(secondId, secondFingerprint))
        }

    @Test
    fun `a dispatch in flight for one rule does not block another rule or recovery`() = runTest {
        val slowId = AutomationId("a-slow")
        val otherId = AutomationId("b-other")
        val slowFingerprint = ApprovalFingerprint("1".repeat(64))
        val otherFingerprint = ApprovalFingerprint("2".repeat(64))
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration(slowId, slowFingerprint, 45.0, 9.0, 150f))
            activate(slowId, slowFingerprint)
            prepare(GeofenceRegistration(otherId, otherFingerprint, 46.0, 10.0, 150f))
            activate(otherId, otherFingerprint)
        }
        val delivered = mutableListOf<TriggerEnvelope>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ingress = GeofenceEventIngress(
            state,
            GeofenceEventDispatcher { envelope ->
                delivered += envelope
                if ((envelope.event as TriggerEvent.GeofenceTransitioned).automationId == slowId) {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )

        val slow = async { ingress.onTransition(slowId, slowFingerprint, Transition.EXIT) }
        entered.await()

        assertTrue(
            withTimeout(1_000) { ingress.onTransition(otherId, otherFingerprint, Transition.ENTER) },
        )
        val recovered = withTimeout(1_000) { ingress.recoverPending(setOf(slowId, otherId)) }

        // Il bordo della regola lenta resta dovuto e il recovery non lo riconsegna.
        assertEquals(emptyList(), recovered)
        assertEquals(Transition.EXIT, state.pending(slowId, slowFingerprint)?.transition)
        assertEquals(2, delivered.size)

        release.complete(Unit)
        assertTrue(slow.await())
        assertNull(state.pending(slowId, slowFingerprint))
        assertEquals(Transition.EXIT, state.get(slowId)?.lastTransition)
        assertEquals(2, delivered.size)
    }

    @Test
    fun `edges of the same rule queue behind an in-flight dispatch in order`() = runTest {
        val id = AutomationId("queue")
        val fingerprint = ApprovalFingerprint("3".repeat(64))
        val state = MemoryGeofenceStateStore().apply {
            prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
            activate(id, fingerprint)
        }
        val delivered = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        val ingress = GeofenceEventIngress(
            state,
            GeofenceEventDispatcher { envelope ->
                delivered += envelope.id.value
                if (first) {
                    first = false
                    entered.complete(Unit)
                    release.await()
                }
            },
        )

        val exit = async { ingress.onTransition(id, fingerprint, Transition.EXIT) }
        entered.await()
        val enter = async { ingress.onTransition(id, fingerprint, Transition.ENTER) }
        runCurrent()
        assertEquals(1, delivered.size)

        release.complete(Unit)
        assertTrue(exit.await())
        assertTrue(enter.await())
        assertEquals(
            listOf("geofence:${fingerprint.value}:1", "geofence:${fingerprint.value}:2"),
            delivered,
        )
        assertNull(state.pending(id, fingerprint))
    }
}
