package dev.argus.automation.foreground

import dev.argus.automation.connectivity.ConnectivitySentinelBackend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Motivo per cui il foreground service unico deve restare vivo. Il decision record P3 §6.2 impone
 * un solo runtime con demand reasons condivisi (Wi-Fi/power, sensore), non un FGS per dominio.
 */
sealed interface SentinelDemand {
    data object Connectivity : SentinelDemand
    data object Sensor : SentinelDemand
}

/**
 * Possiede l'unico FGS ([backend], oggi `ConnectivitySentinelService`) e lo accende finché almeno
 * un dominio lo richiede. Il servizio si ferma SOLO quando l'unione dei demand è vuota: togliere
 * il Wi-Fi non spegne il sensore e viceversa (handoff §7.7 test 11-12).
 *
 * `backend.start()` è idempotente lato Android, ma qui lo chiamiamo solo sulla transizione
 * vuoto→non-vuoto e `stop()` solo su non-vuoto→vuoto: nessun avvio/arresto ridondante del FGS.
 */
class SharedForegroundSentinel(
    private val backend: ConnectivitySentinelBackend,
) {
    private val mutex = Mutex()
    private val active = mutableSetOf<SentinelDemand>()

    suspend fun setDemand(demand: SentinelDemand, required: Boolean): Boolean = mutex.withLock {
        if (required) {
            if (demand in active) return@withLock true
            if (active.isEmpty() && !backend.start()) {
                // Non memorizzare una transizione OS fallita: il prossimo reconcile deve
                // ritentare davvero start(), non credere il servizio già vivo.
                return@withLock false
            }
            active.add(demand)
            true
        } else {
            if (demand !in active) return@withLock true
            if (active.size == 1 && !backend.stop()) {
                // Mantieni il demand finché lo stop non riesce, così la cleanup è ritentabile.
                return@withLock false
            }
            active.remove(demand)
            true
        }
    }

    /**
     * Vista del sentinel come `ConnectivitySentinelBackend` per un singolo demand: `start()` =
     * "questo dominio richiede il FGS", `stop()` = "non lo richiede più". Permette a coordinator
     * scritti contro l'interfaccia backend (connectivity) di usare il sentinel condiviso senza
     * modifiche: cambia solo il wiring.
     */
    fun demandBackend(demand: SentinelDemand): ConnectivitySentinelBackend =
        object : ConnectivitySentinelBackend {
            override suspend fun start(): Boolean = setDemand(demand, required = true)
            override suspend fun stop(): Boolean = setDemand(demand, required = false)
        }
}
