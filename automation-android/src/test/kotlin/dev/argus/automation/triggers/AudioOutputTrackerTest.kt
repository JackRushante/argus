package dev.argus.automation.triggers

import android.media.AudioDeviceInfo
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioOutputTrackerTest {
    private val speaker = AudioPort(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, isSink = true)
    private val wiredHeadset = AudioPort(id = 10, type = AudioDeviceInfo.TYPE_WIRED_HEADSET, isSink = true)
    private val wiredMic = AudioPort(id = 11, type = AudioDeviceInfo.TYPE_WIRED_HEADSET, isSink = false)
    private val usbHeadset = AudioPort(id = 12, type = AudioDeviceInfo.TYPE_USB_HEADSET, isSink = true)
    private val a2dp = AudioPort(id = 20, type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, isSink = true)
    private val sco = AudioPort(id = 21, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, isSink = true)
    private val bleHeadset = AudioPort(id = 22, type = AudioDeviceInfo.TYPE_BLE_HEADSET, isSink = true)
    private val hdmi = AudioPort(id = 30, type = AudioDeviceInfo.TYPE_HDMI, isSink = true)

    @Test
    fun `the literal type codes are the ones of the platform`() {
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioOutputClassifier.TYPE_WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioOutputClassifier.TYPE_WIRED_HEADPHONES)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioOutputClassifier.TYPE_BLUETOOTH_A2DP)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, AudioOutputClassifier.TYPE_USB_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioOutputClassifier.TYPE_BLE_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioOutputClassifier.TYPE_BLE_SPEAKER)
        assertEquals(AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioOutputClassifier.TYPE_BLE_BROADCAST)
    }

    @Test
    fun `only personal outputs are classified and the built-in speaker never counts`() {
        assertEquals(AudioOutputKind.WIRED, AudioOutputClassifier.kindOf(wiredHeadset))
        assertEquals(AudioOutputKind.WIRED, AudioOutputClassifier.kindOf(usbHeadset))
        assertEquals(
            AudioOutputKind.WIRED,
            AudioOutputClassifier.kindOf(AudioPort(5, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, true)),
        )
        assertEquals(AudioOutputKind.BLUETOOTH, AudioOutputClassifier.kindOf(a2dp))
        assertEquals(AudioOutputKind.BLUETOOTH, AudioOutputClassifier.kindOf(bleHeadset))
        assertNull(AudioOutputClassifier.kindOf(speaker))
        assertNull(AudioOutputClassifier.kindOf(hdmi))
        // Il profilo voce (SCO) è un doppione di A2DP sullo stesso dispositivo.
        assertNull(AudioOutputClassifier.kindOf(sco))
        // Un ingresso (microfono delle cuffie) non è un'uscita.
        assertNull(AudioOutputClassifier.kindOf(wiredMic))
    }

    @Test
    fun `the first delivery of the devices already present fires nothing`() {
        val tracker = AudioOutputTracker(listOf(speaker, wiredHeadset, a2dp))
        // Alla registrazione Android riconsegna come «aggiunti» i dispositivi già presenti.
        assertTrue(tracker.onAdded(listOf(speaker, wiredHeadset, a2dp)).isEmpty())
    }

    @Test
    fun `a new device fires connected once and its removal fires disconnected once`() {
        val tracker = AudioOutputTracker(listOf(speaker))

        val connected = tracker.onAdded(listOf(wiredHeadset, wiredMic))
        assertEquals(
            listOf(AudioOutputObservation(AudioOutputKind.WIRED, ConnState.CONNECTED, "audio-port-10-3")),
            connected,
        )
        // Lo stesso id riconsegnato non è un nuovo collegamento.
        assertTrue(tracker.onAdded(listOf(wiredHeadset)).isEmpty())

        val removed = tracker.onRemoved(listOf(wiredHeadset, wiredMic))
        assertEquals(
            listOf(AudioOutputObservation(AudioOutputKind.WIRED, ConnState.DISCONNECTED, "audio-port-10-3")),
            removed,
        )
        assertTrue(tracker.onRemoved(listOf(wiredHeadset)).isEmpty())
    }

    @Test
    fun `a headset that exposes a2dp and sco counts once as bluetooth`() {
        val tracker = AudioOutputTracker(emptyList())
        val connected = tracker.onAdded(listOf(a2dp, sco))
        assertEquals(1, connected.size)
        assertEquals(AudioOutputKind.BLUETOOTH, connected.single().kind)
        assertEquals(1, tracker.onRemoved(listOf(a2dp, sco)).size)
    }

    @Test
    fun `a device that was removed and plugged again with a new port id is a real reconnection`() {
        val tracker = AudioOutputTracker(listOf(usbHeadset))
        assertEquals(ConnState.DISCONNECTED, tracker.onRemoved(listOf(usbHeadset)).single().state)
        val again = usbHeadset.copy(id = 99)
        assertEquals(ConnState.CONNECTED, tracker.onAdded(listOf(again)).single().state)
    }

    @Test
    fun `outputs that are not personal never produce an observation`() {
        val tracker = AudioOutputTracker(emptyList())
        assertTrue(tracker.onAdded(listOf(speaker, hdmi)).isEmpty())
        assertTrue(tracker.onRemoved(listOf(speaker, hdmi)).isEmpty())
    }
}
