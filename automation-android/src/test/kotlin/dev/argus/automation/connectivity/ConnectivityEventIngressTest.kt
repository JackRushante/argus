package dev.argus.automation.connectivity

import dev.argus.engine.connectivity.ConnectivityEventParser
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectivityEventIngressTest {
    @Test
    fun `initial observation seeds state without firing then a real transition dispatches once`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(
            ConnMedium.POWER,
            ConnState.DISCONNECTED,
            name = null,
            sourceIdentity = "device-power",
            atMillis = 10,
            initial = true,
        )
        assertTrue(events.isEmpty())

        ingress.observe(
            ConnMedium.POWER,
            ConnState.CONNECTED,
            name = null,
            sourceIdentity = "device-power",
            atMillis = 20,
        )
        ingress.observe(
            ConnMedium.POWER,
            ConnState.CONNECTED,
            name = null,
            sourceIdentity = "device-power",
            atMillis = 21,
        )

        assertEquals(1, events.size)
        assertEquals(
            TriggerEvent.ConnectivityChanged(ConnMedium.POWER, ConnState.CONNECTED, null),
            events.single().event,
        )
        assertTrue(store.keys.none { "device-power" in it })
    }

    @Test
    fun `late name enrichment reuses transition identity for engine dedupe`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(ConnMedium.BT, ConnState.CONNECTED, null, "AA:BB", 100)
        ingress.observe(ConnMedium.BT, ConnState.CONNECTED, "Auto", "AA:BB", 110)

        assertEquals(2, events.size)
        assertEquals(events[0].id, events[1].id)
        assertEquals(null, (events[0].event as TriggerEvent.ConnectivityChanged).name)
        assertEquals("Auto", (events[1].event as TriggerEvent.ConnectivityChanged).name)
    }

    // --- B4 (0.7.2): un'altra rete con nome è una transizione, non un arricchimento ------------

    @Test
    fun `a different wifi name at unchanged state is a disconnect then a connect with fresh ids`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "device-wifi", 100)
        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Ufficio", "device-wifi", 500)

        assertEquals(
            listOf(
                TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"),
                TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa"),
                TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Ufficio"),
            ),
            events.map { it.event },
        )
        assertEquals(3, events.map { it.id }.toSet().size, "id freschi, niente deduplica")
        assertTrue(store.pending().isEmpty())

        // Tornare a «Casa» è di nuovo una transizione vera, con un id diverso dal primo.
        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "device-wifi", 900)
        assertEquals(5, events.size)
        assertEquals(5, events.map { it.id }.toSet().size)
    }

    @Test
    fun `a renamed bluetooth device keeps its transition identity`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(ConnMedium.BT, ConnState.CONNECTED, "Auto", "AA:BB", 100)
        ingress.observe(ConnMedium.BT, ConnState.CONNECTED, "Auto di Lia", "AA:BB", 500)

        assertEquals(listOf(ConnState.CONNECTED, ConnState.CONNECTED), events.map {
            (it.event as TriggerEvent.ConnectivityChanged).state
        })
        assertEquals(events[0].id, events[1].id)
    }

    @Test
    fun `a failed disconnect of the old network stays pending and the new network follows`() = runTest {
        val store = MemoryConnectivityStateStore()
        val delivered = mutableListOf<TriggerEvent.ConnectivityChanged>()
        var failDisconnect = true
        val ingress = ConnectivityEventIngress(ConnectivityEventParser(), store) { envelope ->
            val event = envelope.event as TriggerEvent.ConnectivityChanged
            if (event.state == ConnState.DISCONNECTED && failDisconnect) error("process dies")
            delivered += event
        }

        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "device-wifi", 100)
        assertFailsWith<IllegalStateException> {
            ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Ufficio", "device-wifi", 500)
        }
        assertEquals(ConnState.DISCONNECTED, store.pending().single().second.event.let {
            (it as TriggerEvent.ConnectivityChanged).state
        })

        failDisconnect = false
        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Ufficio", "device-wifi", 600)

        assertEquals(
            listOf(
                TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"),
                TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa"),
                TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Ufficio"),
            ),
            delivered,
        )
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a network change while the monitor was off only moves the reference`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "device-wifi", 100, initial = true)
        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Ufficio", "device-wifi", 500, initial = true)
        assertTrue(events.isEmpty())

        ingress.observe(ConnMedium.WIFI, ConnState.DISCONNECTED, null, "device-wifi", 900)
        assertEquals(
            TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Ufficio"),
            events.single().event,
        )
    }

    @Test
    fun `android 12 late ssid after an anonymous initial snapshot does not fire a connect rule`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)
        val reference = WifiInitialReference()

        suspend fun observe(state: ConnState, name: String?, at: Long, initial: Boolean = false) =
            ingress.observe(
                ConnMedium.WIFI, state, name, "device-wifi", at,
                initial = reference.classify(state, name, initial, recoverMissed = false),
            )

        // Già a casa: lo snapshot iniziale è anonimo, il nome arriva dalla prima callback.
        observe(ConnState.CONNECTED, null, 100, initial = true)
        observe(ConnState.CONNECTED, "Casa", 110)
        assertTrue(events.isEmpty())

        // Le transizioni successive sono vere.
        observe(ConnState.DISCONNECTED, null, 200)
        observe(ConnState.CONNECTED, "Casa", 300)
        assertEquals(
            listOf(
                TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa"),
                TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"),
            ),
            events.map { it.event },
        )
    }

    @Test
    fun `the initial wifi reference stops waiting for a name after a disconnect or with recovery`() {
        val reference = WifiInitialReference()
        assertTrue(reference.classify(ConnState.CONNECTED, null, initial = true, recoverMissed = false))
        assertEquals(false, reference.classify(ConnState.DISCONNECTED, null, initial = false, recoverMissed = false))
        assertEquals(false, reference.classify(ConnState.CONNECTED, "Casa", initial = false, recoverMissed = false))

        val recovering = WifiInitialReference()
        assertTrue(recovering.classify(ConnState.CONNECTED, null, initial = true, recoverMissed = true))
        assertEquals(false, recovering.classify(ConnState.CONNECTED, "Casa", initial = false, recoverMissed = true))

        val named = WifiInitialReference()
        assertTrue(named.classify(ConnState.CONNECTED, "Casa", initial = true, recoverMissed = false))
        assertEquals(false, named.classify(ConnState.CONNECTED, "Ufficio", initial = false, recoverMissed = false))
    }

    @Test
    fun `different bluetooth devices keep independent transition state`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(ConnMedium.BT, ConnState.CONNECTED, "Auto", "AA:BB", 100)
        ingress.observe(ConnMedium.BT, ConnState.CONNECTED, "Cuffie", "CC:DD", 101)

        assertEquals(2, events.size)
        assertNotEquals(events[0].id, events[1].id)
    }

    @Test
    fun `initial observation is only a reference when the monitor was not already active`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        // D11: il monitor parte da spento (prima regola armata, o riavvio dopo uno stop). Lo stato
        // salvato può essere vecchio di giorni: la prima osservazione aggiorna il riferimento e
        // basta, anche se differisce da quello salvato.
        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "wifi-default", 100, initial = true)
        ingress.observe(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa", "wifi-default", 200, initial = true)

        assertTrue(events.isEmpty())
        assertTrue(store.pending().isEmpty())

        // Il riferimento è stato davvero aggiornato: la transizione reale successiva scatta una volta.
        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "wifi-default", 300)
        assertEquals(1, events.size)
        assertEquals(
            TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"),
            events.single().event,
        )
    }

    @Test
    fun `initial recovery dispatches only a state missed while the monitor was active`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(
            ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "wifi-default", 100,
            initial = true, recoverMissed = true,
        )
        ingress.observe(
            ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa", "wifi-default", 200,
            initial = true, recoverMissed = true,
        )

        assertEquals(1, events.size)
        assertEquals(
            TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa"),
            events.single().event,
        )
    }

    @Test
    fun `a dispatch in flight does not block other sources or recovery and is never delivered twice`() =
        runTest {
            val store = MemoryConnectivityStateStore()
            val delivered = mutableListOf<TriggerEnvelope>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val ingress = ConnectivityEventIngress(ConnectivityEventParser(), store) { envelope ->
                delivered += envelope
                if ((envelope.event as TriggerEvent.ConnectivityChanged).medium == ConnMedium.BT) {
                    entered.complete(Unit)
                    release.await()
                }
            }

            val slow = async {
                ingress.observe(ConnMedium.BT, ConnState.CONNECTED, "Auto", "AA:BB", 100)
            }
            entered.await()

            withTimeout(1_000) {
                ingress.observe(ConnMedium.POWER, ConnState.CONNECTED, null, "device-power", 101)
            }
            val recovered = withTimeout(1_000) { ingress.recoverPending() }

            // Il record della sorgente lenta resta dovuto e il recovery non lo riconsegna.
            assertEquals(emptyList(), recovered)
            assertEquals(1, store.pending().size)
            assertEquals(2, delivered.size)

            release.complete(Unit)
            slow.await()
            assertTrue(store.pending().isEmpty())
            assertEquals(2, delivered.size)
        }

    @Test
    fun `transitions of the same source queue behind an in-flight dispatch in order`() = runTest {
        val store = MemoryConnectivityStateStore()
        val delivered = mutableListOf<ConnState>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        val ingress = ConnectivityEventIngress(ConnectivityEventParser(), store) { envelope ->
            delivered += (envelope.event as TriggerEvent.ConnectivityChanged).state
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
        }

        val connect = async {
            ingress.observe(ConnMedium.BT, ConnState.CONNECTED, "Auto", "AA:BB", 100)
        }
        entered.await()
        val disconnect = async {
            ingress.observe(ConnMedium.BT, ConnState.DISCONNECTED, "Auto", "AA:BB", 200)
        }
        runCurrent()

        // La seconda transizione aspetta in coda: non è consegnata prima che la prima finisca.
        assertEquals(listOf(ConnState.CONNECTED), delivered)

        release.complete(Unit)
        connect.await()
        disconnect.await()
        assertEquals(listOf(ConnState.CONNECTED, ConnState.DISCONNECTED), delivered)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `disconnect retains the last trusted name for filtered rules`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "wifi-default", 100, initial = true)
        ingress.observe(ConnMedium.WIFI, ConnState.DISCONNECTED, null, "wifi-default", 200)

        assertEquals(
            TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa"),
            events.single().event,
        )
    }

    @Test
    fun `failed dispatch stays pending and process recovery reuses the exact event id`() = runTest {
        val store = MemoryConnectivityStateStore()
        var failedId: String? = null
        val failing = ConnectivityEventIngress(
            ConnectivityEventParser(),
            store,
        ) { envelope ->
            failedId = envelope.id.value
            error("process dies")
        }

        assertFailsWith<IllegalStateException> {
            failing.observe(ConnMedium.BT, ConnState.CONNECTED, "Auto", "AA:BB", 100)
        }
        assertEquals(failedId, store.pending().single().second.id.value)

        val recovered = mutableListOf<TriggerEnvelope>()
        val restarted = ingress(store, recovered)
        assertEquals(1, restarted.recoverPending().size)
        assertEquals(failedId, recovered.single().id.value)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `recovery attempts every source even when the first pending dispatch fails`() = runTest {
        val store = MemoryConnectivityStateStore()
        val parser = ConnectivityEventParser()
        val firstKey = "a".repeat(64)
        val secondKey = "b".repeat(64)
        val first = requireNotNull(
            parser.parse(ConnMedium.BT, ConnState.CONNECTED, "Auto", "AA:BB", 100),
        )
        val second = requireNotNull(
            parser.parse(ConnMedium.POWER, ConnState.CONNECTED, null, "power", 101),
        )
        store.record(
            firstKey,
            ConnectivityStateSnapshot(ConnState.CONNECTED, "Auto", 100),
            first,
        )
        store.record(
            secondKey,
            ConnectivityStateSnapshot(ConnState.CONNECTED, null, 101),
            second,
        )
        val attempted = mutableListOf<String>()
        val ingress = ConnectivityEventIngress(parser, store) { envelope ->
            attempted += envelope.id.value
            if (envelope.id == first.id) error("first source unavailable")
        }

        assertFailsWith<IllegalStateException> { ingress.recoverPending() }

        assertEquals(listOf(first.id.value, second.id.value), attempted)
        assertEquals(first.id, store.pending(firstKey)?.id)
        assertNull(store.pending(secondKey))
    }

    private fun ingress(
        store: ConnectivityStateStore,
        events: MutableList<TriggerEnvelope>,
    ) = ConnectivityEventIngress(
        parser = ConnectivityEventParser(),
        state = store,
        dispatcher = ConnectivityEventDispatcher { envelope -> events.add(envelope) },
    )
}

private class MemoryConnectivityStateStore : ConnectivityStateStore {
    private val values = mutableMapOf<String, ConnectivityStateSnapshot>()
    private val pending = mutableMapOf<String, TriggerEnvelope>()
    val keys: Set<String> get() = values.keys

    override fun last(sourceKey: String): ConnectivityStateSnapshot? = values[sourceKey]
    override fun pending(sourceKey: String): TriggerEnvelope? = pending[sourceKey]
    override fun pending(): List<Pair<String, TriggerEnvelope>> = pending.toList()

    override fun record(
        sourceKey: String,
        snapshot: ConnectivityStateSnapshot,
        pending: TriggerEnvelope?,
    ) {
        values[sourceKey] = snapshot
        if (pending == null) this.pending.remove(sourceKey) else this.pending[sourceKey] = pending
    }

    override fun complete(sourceKey: String, eventId: String) {
        require(pending[sourceKey]?.id?.value == eventId)
        pending.remove(sourceKey)
    }
}
