package dev.argus.automation.triggers

import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.runtime.TriggerEnvelope
import java.util.concurrent.ConcurrentHashMap

fun interface DeviceEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

/** Ultimo livello batteria noto e quando è stato osservato (sopravvive ai riavvii del processo). */
data class BatteryReference(val level: Int, val atMillis: Long)

interface BatteryLevelStore {
    fun last(): BatteryReference?
    fun record(level: Int, atMillis: Long)
    fun clear()
}

/**
 * Riduce le osservazioni della sentinella (schermo, batteria, uscita audio, modalità, riproduzione)
 * a eventi del dominio e li consegna al motore. Nessun contenuto utente: solo valori di sistema.
 *
 * Regole:
 *  - **Batteria**: scatta all'attraversamento di una soglia (decide il matcher da livello
 *    precedente e attuale). L'ultimo livello si conserva fra i riavvii e si aggiorna PRIMA della
 *    consegna: un riavvio con lo stesso livello non riscatta mai. La prima osservazione di un
 *    monitor appena acceso è solo un riferimento, tranne dopo un kill (`recoverMissed`) quando il
 *    riferimento salvato è recente: allora un attraversamento avvenuto nel frattempo viene recapitato.
 *  - **Modalità**: un cambio verso lo stesso valore non scatta; il valore di partenza si imposta
 *    con [setModeBaseline] prima di registrare il receiver.
 *  - Gli altri eventi sono consegnati tali e quali.
 *
 * La consegna (che può eseguire l'intero programma della regola) avviene FUORI dai lock.
 */
class DeviceEventIngress(
    private val parser: DeviceEventParser,
    private val battery: BatteryLevelStore,
    private val dispatcher: DeviceEventDispatcher,
) {
    // Sezione breve e senza sospensioni: la decisione resta ordinata come i broadcast, che arrivano
    // in sequenza dal thread principale. La consegna è fuori dal lock.
    private val batteryLock = Any()
    private var batteryObserved = false
    private var batteryRecoverMissed = false
    private val modes = ConcurrentHashMap<ModeKind, String>()

    suspend fun onScreen(event: ScreenEvent, atMillis: Long) {
        dispatcher.dispatch(parser.screen(event, atMillis))
    }

    suspend fun onAudioOutput(
        kind: AudioOutputKind,
        state: ConnState,
        sourceIdentity: String,
        atMillis: Long,
    ) {
        parser.audioOutput(kind, state, sourceIdentity, atMillis)?.let { dispatcher.dispatch(it) }
    }

    suspend fun onMedia(state: MediaPlayState, atMillis: Long) {
        dispatcher.dispatch(parser.media(state, atMillis))
    }

    /** Il valore di partenza di [what]: nessun evento. Da chiamare prima di registrare il receiver. */
    fun setModeBaseline(what: ModeKind, value: String) {
        if (value in what.allowedValues) modes[what] = value else modes.remove(what)
    }

    /** Dimentica il valore di [what]: alla prossima registrazione si riparte da una nuova base. */
    fun forgetMode(what: ModeKind) {
        modes.remove(what)
    }

    /** Un valore fuori dal vocabolario (es. «solo sveglie») è ignorato e non cambia lo stato noto. */
    suspend fun onMode(what: ModeKind, value: String, atMillis: Long) {
        if (value !in what.allowedValues) return
        val previous = modes.put(what, value)
        if (previous == value) return
        parser.mode(what, value, previous, atMillis)?.let { dispatcher.dispatch(it) }
    }

    /**
     * Da chiamare quando la sorgente batteria viene (ri)registrata: la prima osservazione successiva
     * è la base. [recoverMissed] = il monitor precedente è morto senza uno stop esplicito.
     */
    fun startBatteryObservation(recoverMissed: Boolean) {
        synchronized(batteryLock) {
            batteryObserved = false
            batteryRecoverMissed = recoverMissed
        }
    }

    /** La sorgente batteria è stata tolta: il riferimento salvato non vale più. */
    fun stopBatteryObservation() {
        synchronized(batteryLock) {
            batteryObserved = false
            battery.clear()
        }
    }

    /**
     * Un nuovo livello (0..100). Va chiamato nell'ordine di arrivo dei broadcast: il lock serializza
     * la decisione, la consegna dell'eventuale attraversamento avviene dopo.
     */
    suspend fun onBatteryLevel(level: Int, atMillis: Long) {
        if (level !in 0..100) return
        val at = atMillis.coerceAtLeast(0)
        val envelope = synchronized(batteryLock) {
            val first = !batteryObserved
            batteryObserved = true
            val saved = battery.last()
            // Un riferimento troppo vecchio (processo fermo a lungo) non vale per recuperare.
            val reference = saved?.takeUnless { first && at - it.atMillis !in 0..MAX_RECOVERY_AGE_MILLIS }
            if (saved == null || saved.level != level || at - saved.atMillis >= HEARTBEAT_MILLIS) {
                // Il livello nuovo si salva PRIMA di consegnare: mai due scatti per lo stesso salto.
                battery.record(level, at)
            }
            when {
                reference == null || reference.level == level -> null
                first && !batteryRecoverMissed -> null
                else -> parser.battery(reference.level, level, at)
            }
        }
        envelope?.let { dispatcher.dispatch(it) }
    }

    companion object {
        /** Oltre questo tempo dall'ultima osservazione un livello salvato è solo un riferimento. */
        const val MAX_RECOVERY_AGE_MILLIS = 2L * 60 * 60 * 1_000

        /** A livello invariato il riferimento si rinfresca al più ogni 5 minuti. */
        const val HEARTBEAT_MILLIS = 5L * 60 * 1_000
    }
}
