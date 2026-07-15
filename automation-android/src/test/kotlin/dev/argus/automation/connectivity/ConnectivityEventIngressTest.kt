package dev.argus.automation.connectivity

import dev.argus.engine.connectivity.ConnectivityEventParser
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun `initial recovery dispatches only a state missed while process was dead`() = runTest {
        val store = MemoryConnectivityStateStore()
        val events = mutableListOf<TriggerEnvelope>()
        val ingress = ingress(store, events)

        ingress.observe(ConnMedium.WIFI, ConnState.CONNECTED, "Casa", "wifi-default", 100, initial = true)
        ingress.observe(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa", "wifi-default", 200, initial = true)

        assertEquals(1, events.size)
        assertEquals(
            TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa"),
            events.single().event,
        )
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
    val keys: Set<String> get() = values.keys

    override fun last(sourceKey: String): ConnectivityStateSnapshot? = values[sourceKey]

    override fun record(sourceKey: String, snapshot: ConnectivityStateSnapshot) {
        values[sourceKey] = snapshot
    }
}
