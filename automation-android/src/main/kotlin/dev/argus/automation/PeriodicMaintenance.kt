package dev.argus.automation

import java.util.concurrent.atomic.AtomicLong

/**
 * Pulizia dei dati vecchi al massimo una volta ogni [intervalMillis] di orologio reale (B28).
 * Con il servizio sempre attivo il processo vive per settimane: la pulizia solo all'avvio non
 * basterebbe. L'ultimo istante si tiene in memoria: dopo un riavvio la pulizia riparte comunque
 * dallo `start()` del runtime.
 *
 * [canRun] è un'ulteriore condizione (per esempio «nessun dispatch in corso»): se è falsa il
 * tentativo salta SENZA consumare l'intervallo, e riprova al prossimo.
 */
internal class PeriodicMaintenance(
    private val run: suspend (nowMillis: Long) -> Unit,
    private val nowMillis: () -> Long,
    private val canRun: () -> Boolean = { true },
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    private val lastRunAtMillis = AtomicLong(NEVER)

    /** `true` se la pulizia sarebbe dovuta ora; non consuma l'intervallo. */
    fun isDue(): Boolean = canRun() && due(nowMillis(), lastRunAtMillis.get())

    /** Esegue la pulizia se dovuta; `true` solo se è davvero partita. */
    suspend fun runIfDue(): Boolean {
        if (!canRun()) return false
        val now = nowMillis()
        var previous: Long
        do {
            previous = lastRunAtMillis.get()
            if (!due(now, previous)) return false
        } while (!lastRunAtMillis.compareAndSet(previous, now))
        try {
            run(now)
        } catch (error: Throwable) {
            // Una pulizia fallita o interrotta non consuma le 24 h: la prossima occasione riprova.
            lastRunAtMillis.compareAndSet(now, previous)
            throw error
        }
        return true
    }

    // Un orologio portato indietro (elapsed negativo) conta come «dovuto»: l'ultimo istante non è
    // più confrontabile e non deve bloccare la pulizia fino a che l'ora lo raggiunge.
    private fun due(now: Long, previous: Long): Boolean =
        previous == NEVER || now - previous !in 0L until intervalMillis

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 24L * 60 * 60 * 1_000
        private const val NEVER = Long.MIN_VALUE
    }
}
