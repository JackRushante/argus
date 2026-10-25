package dev.argus.automation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Coda sequenziale per sorgente (B3). Gli ingress persistono il pending sotto un lock di stato
 * breve, ma la CONSEGNA (che esegue l'intero programma della regola, anche per ore) non deve
 * tenere quel lock: altrimenti una regola lenta blocca ogni altra sorgente e ogni recovery.
 *
 * Ogni chiave ha la sua corsia: i lavori della stessa chiave vanno in coda FIFO (il [Mutex] di
 * kotlinx è fair), quindi l'ordine di arrivo è l'ordine di consegna e uno stesso pending non viene
 * mai consegnato due volte in parallelo; chiavi diverse non si aspettano.
 *
 * Il numero di chiavi è limitato dalle sorgenti reali (medium/dispositivi, regole, kind sensore).
 */
internal class SequentialLanes<K : Any> {
    private val lanes = ConcurrentHashMap<K, Mutex>()

    private fun lane(key: K): Mutex = lanes.getOrPut(key) { Mutex() }

    /** Accoda [block] dietro i lavori già in corso o in attesa sulla stessa chiave. */
    suspend fun <T> run(key: K, block: suspend () -> T): T = lane(key).withLock { block() }

    /**
     * Come [run] ma senza attendere: `null` se la corsia è occupata. Serve ai recovery, che non
     * devono né bloccarsi dietro a un dispatch in corso né riconsegnarlo: chi occupa la corsia
     * recapita già (prima il pending lasciato indietro, poi l'eventuale nuovo evento).
     */
    suspend fun <T : Any> runIfIdle(key: K, block: suspend () -> T): T? {
        val lane = lane(key)
        if (!lane.tryLock()) return null
        try {
            return block()
        } finally {
            lane.unlock()
        }
    }
}
