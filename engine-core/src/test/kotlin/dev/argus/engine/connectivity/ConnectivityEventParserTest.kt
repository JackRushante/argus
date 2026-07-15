package dev.argus.engine.connectivity

import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.TriggerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectivityEventParserTest {
    private val parser = ConnectivityEventParser()

    @Test
    fun `parser bounds display name and never exposes source identity in event id`() {
        val source = "AA:BB:CC:DD:EE:FF"
        val envelope = parser.parse(
            medium = ConnMedium.BT,
            state = ConnState.CONNECTED,
            name = "  Auto\u0000 di Lorenzo  ",
            sourceIdentity = source,
            atMillis = 123L,
        )!!

        assertEquals(
            TriggerEvent.ConnectivityChanged(ConnMedium.BT, ConnState.CONNECTED, "Auto di Lorenzo"),
            envelope.event,
        )
        assertTrue(envelope.id.value.startsWith("connectivity:"))
        assertTrue(source !in envelope.id.value)
    }

    @Test
    fun `same transition identity is stable while state or time changes it`() {
        fun parse(state: ConnState, atMillis: Long) = parser.parse(
            ConnMedium.WIFI,
            state,
            name = "Casa",
            sourceIdentity = "wifi-default",
            atMillis = atMillis,
        )!!.id

        assertEquals(parse(ConnState.CONNECTED, 10), parse(ConnState.CONNECTED, 10))
        assertNotEquals(parse(ConnState.CONNECTED, 10), parse(ConnState.DISCONNECTED, 10))
        assertNotEquals(parse(ConnState.CONNECTED, 10), parse(ConnState.CONNECTED, 11))
    }

    @Test
    fun `invalid source identity fails closed`() {
        assertNull(parser.parse(ConnMedium.POWER, ConnState.CONNECTED, null, "", 1L))
        assertNull(parser.parse(ConnMedium.POWER, ConnState.CONNECTED, null, "x".repeat(513), 1L))
    }
}
