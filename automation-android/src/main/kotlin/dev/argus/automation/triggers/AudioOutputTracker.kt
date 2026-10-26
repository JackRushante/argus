package dev.argus.automation.triggers

import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState

/** Una porta audio come la vede il tracker: niente `AudioDeviceInfo`, così si prova in JVM. */
data class AudioPort(val id: Int, val type: Int, val isSink: Boolean)

/** Un cambio di uscita audio personale; [sourceIdentity] non contiene nomi né indirizzi. */
data class AudioOutputObservation(
    val kind: AudioOutputKind,
    val state: ConnState,
    val sourceIdentity: String,
)

/**
 * Classifica le uscite audio PERSONALI. Solo uscite (sink). Cuffie o auricolari a filo e USB
 * headset sono WIRED; A2DP e LE audio sono BLUETOOTH. L'altoparlante interno, HDMI, USB generico,
 * dock, SCO (profilo voce, doppione di A2DP sullo stesso dispositivo) non contano mai.
 */
object AudioOutputClassifier {
    // Valori di AudioDeviceInfo.TYPE_*; un test li confronta con le costanti della piattaforma.
    internal const val TYPE_WIRED_HEADSET = 3
    internal const val TYPE_WIRED_HEADPHONES = 4
    internal const val TYPE_BLUETOOTH_A2DP = 8
    internal const val TYPE_USB_HEADSET = 22
    internal const val TYPE_BLE_HEADSET = 26
    internal const val TYPE_BLE_SPEAKER = 27
    internal const val TYPE_BLE_BROADCAST = 30

    fun kindOf(port: AudioPort): AudioOutputKind? {
        if (!port.isSink) return null
        return when (port.type) {
            TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET -> AudioOutputKind.WIRED
            TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST ->
                AudioOutputKind.BLUETOOTH
            else -> null
        }
    }
}

/**
 * Trasforma le consegne di `AudioDeviceCallback` in collegamenti/scollegamenti.
 *
 * Alla registrazione Android riconsegna come «aggiunti» i dispositivi GIÀ presenti: il tracker parte
 * da un elenco iniziale ([initial], letto prima di registrare) e considera nuovo solo ciò che non
 * c'era, quindi quella prima consegna non fa scattare nulla. Un dispositivo è identificato dall'id
 * della porta; una porta tolta e poi riaggiunta con un id nuovo è un vero ricollegamento.
 */
class AudioOutputTracker(initial: Collection<AudioPort>) {
    private val known = HashMap<Int, AudioOutputKind>()

    init {
        initial.forEach { port -> AudioOutputClassifier.kindOf(port)?.let { known[port.id] = it } }
    }

    @Synchronized
    fun onAdded(ports: Collection<AudioPort>): List<AudioOutputObservation> = ports.mapNotNull { port ->
        val kind = AudioOutputClassifier.kindOf(port) ?: return@mapNotNull null
        if (known.put(port.id, kind) != null) return@mapNotNull null
        AudioOutputObservation(kind, ConnState.CONNECTED, identity(port))
    }

    @Synchronized
    fun onRemoved(ports: Collection<AudioPort>): List<AudioOutputObservation> = ports.mapNotNull { port ->
        val kind = known.remove(port.id) ?: return@mapNotNull null
        AudioOutputObservation(kind, ConnState.DISCONNECTED, identity(port))
    }

    private fun identity(port: AudioPort) = "audio-port-${port.id}-${port.type}"
}
