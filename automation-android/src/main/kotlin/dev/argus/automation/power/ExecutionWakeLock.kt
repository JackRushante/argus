package dev.argus.automation.power

/**
 * Primitiva del wakelock di sistema: `PowerManager.WakeLock` in produzione, un fake nei test.
 * L'acquisizione ha sempre un timeout di sicurezza; il rilascio è idempotente.
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
 */
class ExecutionWakeLock(
    private val lock: PowerLock,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val monitor = Any()
    private var holders = 0

    /** Quote attive; visibile per diagnostica e test. */
    val activeHolders: Int
        get() = synchronized(monitor) { holders }

    fun acquire() {
        synchronized(monitor) {
            holders += 1
            if (holders == 1) lock.acquire(timeoutMillis)
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

    companion object {
        /** Sopra l'attesa massima di un'ora più il lavoro che la segue. */
        const val DEFAULT_TIMEOUT_MILLIS = 70L * 60 * 1_000

        /** Senza wakelock: i dispatcher costruiti a mano (test) non ne richiedono uno. */
        val NONE = ExecutionWakeLock(NoopPowerLock)
    }
}
