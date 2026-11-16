package dev.argus.automation.power

import dev.argus.engine.runtime.ProgramInterpreter

/**
 * Primitiva del wakelock di sistema: `PowerManager.WakeLock` in produzione, un fake nei test.
 * L'acquisizione ha sempre un timeout di sicurezza; il rilascio è idempotente. Una nuova
 * acquisizione mentre il lock è tenuto ne rinnova il timeout (lock non contato).
 */
interface PowerLock {
    fun acquire(timeoutMillis: Long)
    fun release()
}

/** Nessun wakelock: default per i test e per i caller non Android. */
object NoopPowerLock : PowerLock {
    override fun acquire(timeoutMillis: Long) = Unit
    override fun release() = Unit
}

/**
 * Wakelock parziale con contatore interno (B19). Argus non tiene sveglio il processore: con lo
 * schermo spento il `delay` delle coroutine (uptime) si ferma in deep sleep, e letture di posizione
 * e chiamate AI possono restare sospese. Ogni lavoro che esegue regole gira in [holding]:
 * il lock di sistema si acquisisce al passaggio 0→1, con un timeout di sicurezza, e si rilascia
 * all'1→0. Più lavori in parallelo condividono lo stesso lock, che si rilascia solo quando
 * finiscono tutti.
 *
 * Il timeout copre l'esecuzione massima di un programma più un margine, e si RINNOVA (0.7.2, B6):
 * un nuovo lavoro che si sovrappone a uno già in corso e ogni [refresh] (lo chiama ogni attesa)
 * lo fanno ripartire, al più una volta ogni [refreshEveryMillis]. Prima si impostava solo al primo
 * acquisitore: dopo 70 minuti un programma lungo, o il secondo di due sovrapposti, si fermava a
 * schermo spento.
 */
class ExecutionWakeLock(
    private val lock: PowerLock,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** Orologio monotono: basta che avanzi mentre il lock è tenuto (niente deep sleep). */
    private val uptimeMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val refreshEveryMillis: Long = REFRESH_EVERY_MILLIS,
) {
    private val monitor = Any()
    private var holders = 0
    private var renewedAt = 0L

    /** Quote attive; visibile per diagnostica e test. */
    val activeHolders: Int
        get() = synchronized(monitor) { holders }

    fun acquire() {
        synchronized(monitor) {
            holders += 1
            if (holders == 1) renewLocked() else renewIfDueLocked()
        }
    }

    /** Rinnova il timeout se il lock è tenuto e l'ultimo rinnovo non è recente; altrimenti nulla. */
    fun refresh() {
        synchronized(monitor) {
            if (holders > 0) renewIfDueLocked()
        }
    }

    /** `false` se non c'era nessuna quota da rilasciare (rilascio sbilanciato: ignorato). */
    fun release(): Boolean = synchronized(monitor) {
        if (holders == 0) return@synchronized false
        holders -= 1
        if (holders == 0) lock.release()
        true
    }

    suspend fun <T> holding(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private fun renewIfDueLocked() {
        if (uptimeMillis() - renewedAt >= refreshEveryMillis) renewLocked()
    }

    private fun renewLocked() {
        lock.acquire(timeoutMillis)
        renewedAt = uptimeMillis()
    }

    companion object {
        /** Oltre l'esecuzione massima di un programma (6 ore) più il lavoro che la circonda. */
        const val DEFAULT_TIMEOUT_MILLIS = ProgramInterpreter.MAX_EXECUTION_MILLIS + 15L * 60 * 1_000

        /** Un rinnovo lascia sempre più di 6 ore: rinnovare più spesso costerebbe solo binder. */
        const val REFRESH_EVERY_MILLIS = 10L * 60 * 1_000

        /** Senza wakelock: i dispatcher costruiti a mano (test) non ne richiedono uno. */
        val NONE = ExecutionWakeLock(NoopPowerLock)
    }
}
