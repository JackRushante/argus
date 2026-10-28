package dev.argus.automation.triggers

import dev.argus.device.UsageActivityEvent
import dev.argus.device.UsageEventsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Legge a intervalli brevi gli eventi di utilizzo e ne ricava le transizioni «app aperta/chiusa»
 * (0.5.1). Gira SOLO a schermo acceso: allo spegnimento si ferma (dopo un'ultima lettura che raccoglie
 * la chiusura dell'app che era in primo piano) e alla riaccensione riparte. Nessun wakelock, nessun
 * allarme: è un ciclo di coroutine che vive finché vive la sentinella.
 *
 * Niente perso e niente doppio: ogni lettura parte da poco prima della fine della precedente
 * ([LOOKBACK_MILLIS], per gli eventi che il sistema rende visibili con un po' di ritardo) e scarta gli eventi
 * già visti; dopo una pausa a schermo spento la prima lettura riprende dall'ultimo istante letto. Se la
 * pausa supera [MAX_GAP_MILLIS] si riparte da zero (lo stato noto non è più affidabile).
 *
 * Le transizioni arrivano a [emit] una alla volta, nell'ordine in cui sono avvenute, da un'unica coda: un
 * dispatch lento (una regola con un'attesa) non blocca la lettura e non scambia l'ordine. Si consegnano
 * solo le app di cui [watched] dice che c'è una regola armata; le altre servono solo a tenere lo stato.
 *
 * Nessun contenuto utente nei log: questa classe non scrive nulla.
 */
class AppEventPoller(
    private val scope: CoroutineScope,
    private val source: UsageEventsSource,
    private val nowMillis: () -> Long,
    private val watched: () -> Set<String>,
    private val emit: suspend (AppTransition) -> Unit,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val tracker: AppTransitionTracker = AppTransitionTracker(),
) {
    private val readLock = Mutex()
    private val stateLock = Any()
    private val queue = Channel<AppTransition>(Channel.UNLIMITED)
    private var consumer: Job? = null
    private var loop: Job? = null
    private var finalRead: Job? = null
    private var closed = false

    // Stato della lettura: toccato solo con [readLock] preso.
    private var baselined = false
    private var cursorEnd = 0L
    private val seen = HashSet<EventKey>()

    private data class EventKey(val pkg: String, val cls: String?, val type: Int, val at: Long)

    /** Avvia la coda di consegna e, se lo schermo è acceso, la lettura. */
    fun start(screenOn: Boolean) {
        synchronized(stateLock) {
            if (closed || consumer != null) return
            consumer = scope.launch {
                for (transition in queue) {
                    try {
                        emit(transition)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Una consegna fallita non ferma le successive; il chiamante registra l'errore.
                    }
                }
            }
            if (screenOn) startLoopLocked()
        }
    }

    fun onScreenOn() {
        synchronized(stateLock) {
            if (closed || consumer == null) return
            finalRead?.cancel()
            finalRead = null
            startLoopLocked()
        }
    }

    /**
     * Ferma il ciclo. Una lettura finale, poco dopo, raccoglie la pausa dell'app che era in primo piano
     * (arriva qualche centinaio di millisecondi dopo lo spegnimento) e la fa maturare in chiusura.
     */
    fun onScreenOff() {
        synchronized(stateLock) {
            if (closed || consumer == null) return
            loop?.cancel()
            loop = null
            finalRead?.cancel()
            finalRead = scope.launch {
                delay(FINAL_READ_DELAY_MILLIS)
                safeReadOnce()
            }
        }
    }

    fun close() {
        synchronized(stateLock) {
            closed = true
            loop?.cancel()
            finalRead?.cancel()
            consumer?.cancel()
            loop = null
            finalRead = null
            consumer = null
            queue.close()
        }
    }

    private fun startLoopLocked() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                safeReadOnce()
                delay(intervalMillis)
            }
        }
    }

    /** Un errore imprevisto di una lettura non deve fermare il ciclo: si riprova alla successiva. */
    private suspend fun safeReadOnce() {
        try {
            readOnce()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // La sorgente non lancia per contratto; se lo fa, lo stato resta quello di prima.
        }
    }

    /** Una lettura: serializzata, perché la lettura finale e il ciclo non devono sovrapporsi. */
    private suspend fun readOnce() {
        val transitions = readLock.withLock { read() }
        val interesting = watched()
        transitions.filter { it.pkg in interesting }.forEach { queue.trySend(it) }
    }

    private fun read(): List<AppTransition> {
        val now = nowMillis()
        if (baselined && now - cursorEnd > MAX_GAP_MILLIS) {
            tracker.reset()
            seen.clear()
            baselined = false
        }
        if (!baselined) return baseline(now)

        val from = (cursorEnd - LOOKBACK_MILLIS).coerceAtLeast(0L)
        // Illeggibile (accesso revocato): il cursore resta dov'è, la prossima lettura riprende da lì.
        val events = source.read(from, now) ?: return emptyList()
        val fresh = events.filter { seen.add(it.key()) }
        val transitions = tracker.apply(fresh, now)
        cursorEnd = now
        seen.removeAll { it.at < now - LOOKBACK_MILLIS }
        return transitions
    }

    /**
     * Punto di partenza: legge gli ultimi minuti (e, se nessuna app risulta in primo piano, qualche ora)
     * per sapere cosa è già aperto, SENZA consegnare nulla. Da qui in poi contano solo le novità.
     */
    private fun baseline(now: Long): List<AppTransition> {
        for (window in BASELINE_WINDOWS_MILLIS) {
            val events = source.read((now - window).coerceAtLeast(0L), now) ?: return emptyList()
            tracker.reset()
            seen.clear()
            events.forEach { seen.add(it.key()) }
            tracker.apply(events, now)
            if (tracker.hasForeground()) break
        }
        baselined = true
        cursorEnd = now
        seen.removeAll { it.at < now - LOOKBACK_MILLIS }
        return emptyList()
    }

    private fun UsageActivityEvent.key() = EventKey(packageName, className, type, timeMillis)

    companion object {
        /** Ogni quanto si legge a schermo acceso. */
        const val DEFAULT_INTERVAL_MILLIS = 2_500L

        /** Quanto prima della fine dell'ultima lettura si riparte: copre gli eventi resi visibili in ritardo. */
        const val LOOKBACK_MILLIS = 3_000L

        /** Attesa fra lo spegnimento dello schermo e l'ultima lettura: la pausa arriva subito dopo e la chiusura matura. */
        const val FINAL_READ_DELAY_MILLIS = 2_000L

        /** Oltre questo tempo senza letture (schermo spento a lungo) lo stato noto si butta e si riparte. */
        const val MAX_GAP_MILLIS = 12L * 60 * 60 * 1_000

        private val BASELINE_WINDOWS_MILLIS = longArrayOf(10 * 60_000L, 3 * 60 * 60_000L)
    }
}
