package dev.argus.automation.power

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Sveglia di sistema per una ripresa dall'attesa; il [token] identifica l'attesa. */
interface WakeAlarmBackend {
    /** Programma una sveglia che scatta a [triggerAtElapsedMillis] (clock `elapsedRealtime`). */
    fun schedule(token: Long, triggerAtElapsedMillis: Long)

    /** Annulla la sveglia; idempotente. */
    fun cancel(token: Long)
}

/**
 * Registro in memoria token → attesa in corso. Lo usa il receiver della sveglia per riprendere
 * l'attesa giusta; dopo la morte del processo il registro è vuoto e un token sconosciuto non fa
 * nulla. Per tenere sveglio il telefono fra il segnale e la ripresa della coroutine, [wakeUp]
 * acquisisce un secondo wakelock BREVE (con timeout, si rilascia da solo).
 */
class WaitWakeSignals(
    private val resumeLock: PowerLock = NoopPowerLock,
    private val resumeHoldMillis: Long = RESUME_HOLD_MILLIS,
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    fun register(token: Long): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { pending[token] = it }

    fun unregister(token: Long) {
        pending.remove(token)
    }

    /** Chiamato dal receiver. `false` (e nessun effetto) se il token non è noto. */
    fun wakeUp(token: Long): Boolean {
        val signal = pending[token] ?: return false
        resumeLock.acquire(resumeHoldMillis)
        if (signal.complete(Unit)) return true
        resumeLock.release()
        return false
    }

    companion object {
        const val RESUME_HOLD_MILLIS = 10_000L
    }
}

/**
 * Attesa («attendi N») che sopravvive al deep sleep (B19). Iniettata come `Engine(pause = …)`.
 *
 * - Fino a [shortWaitMillis]: un semplice `delay`; il wakelock resta tenuto dal dispatch.
 * - Oltre: rilascia la propria quota del wakelock per tutta l'attesa, così il processore può
 *   dormire, e programma una sveglia `ELAPSED_REALTIME_WAKEUP` alla scadenza. Aspetta il primo fra
 *   il segnale della sveglia e un `delay` della stessa durata (che vale se il telefono resta
 *   sveglio). Al risveglio riacquisisce la quota e, se secondo `elapsedRealtime` mancano ancora
 *   millisecondi, aspetta il resto.
 *
 * Su cancellazione la sveglia viene annullata e la quota riacquisita, così il `holding` del
 * dispatch che la rilascia resta bilanciato. Se la quota non era tenuta (pausa fuori da un
 * dispatch) non si tocca il contatore.
 */
class WakeAwarePause(
    private val wakeLock: ExecutionWakeLock,
    private val alarms: WakeAlarmBackend,
    private val signals: WaitWakeSignals,
    private val elapsedRealtime: () -> Long,
    private val shortWaitMillis: Long = SHORT_WAIT_MILLIS,
    private val nextToken: () -> Long = defaultTokens(),
) {
    suspend fun pause(millis: Long) {
        if (millis <= 0) return
        if (millis <= shortWaitMillis) {
            delay(millis)
            return
        }
        val deadline = elapsedRealtime() + millis
        val token = nextToken()
        val signal = signals.register(token)
        val released = wakeLock.release()
        try {
            val scheduled = try {
                alarms.schedule(token, deadline)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Senza sveglia resta il solo `delay`: peggiore, ma l'attesa non fallisce.
                false
            }
            if (scheduled) withTimeoutOrNull(millis) { signal.await() } else delay(millis)
        } finally {
            withContext(NonCancellable) {
                runCatching { alarms.cancel(token) }
                signals.unregister(token)
                if (released) wakeLock.acquire()
            }
        }
        val remaining = deadline - elapsedRealtime()
        if (remaining > 0) delay(remaining)
    }

    companion object {
        const val SHORT_WAIT_MILLIS = 60_000L

        /**
         * Token univoci anche fra processi: una sveglia di un processo morto può scattare più
         * tardi e non deve poter svegliare un'attesa del processo nuovo.
         */
        private fun defaultTokens(): () -> Long {
            val counter = AtomicLong(java.security.SecureRandom().nextLong() ushr 1)
            return { counter.incrementAndGet() and Long.MAX_VALUE }
        }
    }
}
