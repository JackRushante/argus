package dev.argus.engine.device

import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.runtime.TriggerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceEventParserTest {
    private val parser = DeviceEventParser()

    @Test
    fun `ids are opaque digests that differ by content and time`() {
        val on = parser.screen(ScreenEvent.ON, 1_000L)
        val off = parser.screen(ScreenEvent.OFF, 1_000L)
        val later = parser.screen(ScreenEvent.ON, 2_000L)

        assertTrue(Regex("^device:[0-9a-f]{64}$").matches(on.id.value), on.id.value)
        assertNotEquals(on.id, off.id)
        assertNotEquals(on.id, later.id)
        assertEquals(on.id, parser.screen(ScreenEvent.ON, 1_000L).id)
        assertEquals(TriggerEvent.ScreenChanged(ScreenEvent.ON), on.event)
    }

    @Test
    fun `battery refuses incoherent levels and unchanged levels`() {
        assertNull(parser.battery(50, 50, 1L))
        assertNull(parser.battery(-1, 50, 1L))
        assertNull(parser.battery(50, 101, 1L))
        val crossing = assertNotNull(parser.battery(21, 19, 1L))
        assertEquals(TriggerEvent.BatteryLevelChanged(21, 19), crossing.event)
        assertNotEquals(crossing.id, parser.battery(21, 19, 2L)!!.id)
    }

    @Test
    fun `audio output keeps the device identity out of the id and refuses ANY`() {
        val envelope = assertNotNull(
            parser.audioOutput(AudioOutputKind.BLUETOOTH, ConnState.CONNECTED, "AA:BB:CC:DD:EE:FF", 1_000L),
        )
        assertEquals(
            TriggerEvent.AudioOutputChanged(AudioOutputKind.BLUETOOTH, ConnState.CONNECTED),
            envelope.event,
        )
        assertTrue("AA:BB" !in envelope.id.value)
        assertNull(parser.audioOutput(AudioOutputKind.ANY, ConnState.CONNECTED, "x", 1L))
        assertNull(parser.audioOutput(AudioOutputKind.WIRED, ConnState.CONNECTED, " ", 1L))
        assertNull(parser.audioOutput(AudioOutputKind.WIRED, ConnState.CONNECTED, "a\nb", 1L))
        // Due dispositivi collegati nello stesso istante sono due eventi.
        val a = parser.audioOutput(AudioOutputKind.WIRED, ConnState.CONNECTED, "port-1", 5L)!!
        val b = parser.audioOutput(AudioOutputKind.WIRED, ConnState.CONNECTED, "port-2", 5L)!!
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `mode accepts only values of its own vocabulary`() {
        val ok = assertNotNull(parser.mode(ModeKind.DND, "total", "off", 1L))
        assertEquals(TriggerEvent.DeviceModeChanged(ModeKind.DND, "total", "off"), ok.event)
        assertNotNull(parser.mode(ModeKind.RINGER, "vibrate", null, 1L))
        assertNull(parser.mode(ModeKind.DND, "silent", null, 1L))
        assertNull(parser.mode(ModeKind.RINGER, "normal", "alarms", 1L))
    }

    @Test
    fun `media carries only the playback state`() {
        val envelope = parser.media(MediaPlayState.STOPPED, 3L)
        assertEquals(TriggerEvent.MediaPlaybackChanged(MediaPlayState.STOPPED), envelope.event)
    }
}
