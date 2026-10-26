package dev.argus.automation.triggers

import dev.argus.engine.model.MediaPlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Antirimbalzo della riproduzione multimediale. Fra un brano e l'altro il lettore si ferma e riparte
 * in meno di un secondo: la fine si segnala solo se la riproduzione resta ferma per
 * [stopDelayMillis] (circa 2 s); se riparte prima, non succede nulla. L'inizio si segnala subito.
 *
 * [initiallyPlaying] è lo stato già in corso alla registrazione: è la base, non un evento.
 */
class MediaPlaybackTracker(
    private val scope: CoroutineScope,
    initiallyPlaying: Boolean,
    private val stopDelayMillis: Long = DEFAULT_STOP_DELAY_MILLIS,
    private val emit: suspend (MediaPlayState) -> Unit,
) {
    private val lock = Any()
    private var reported = initiallyPlaying
    private var pendingStop: Job? = null
    private var generation = 0L

    /** [active] = c'è almeno una riproduzione multimediale in corso adesso. */
    fun onPlayback(active: Boolean) {
        val startNow = synchronized(lock) {
            if (active) {
                generation++
                pendingStop?.cancel()
                pendingStop = null
                if (reported) {
                    false
                } else {
                    reported = true
                    true
                }
            } else {
                if (reported && pendingStop == null) {
                    val expected = ++generation
                    pendingStop = scope.launch {
                        delay(stopDelayMillis)
                        val stopped = synchronized(lock) {
                            if (generation == expected && reported) {
                                reported = false
                                pendingStop = null
                                true
                            } else {
                                false
                            }
                        }
                        if (stopped) emit(MediaPlayState.STOPPED)
                    }
                }
                false
            }
        }
        // Fuori dal lock: l'inizio parte subito, sul thread del callback.
        if (startNow) scope.launch(start = CoroutineStart.UNDISPATCHED) { emit(MediaPlayState.PLAYING) }
    }

    fun close() {
        synchronized(lock) {
            generation++
            pendingStop?.cancel()
            pendingStop = null
        }
    }

    companion object {
        const val DEFAULT_STOP_DELAY_MILLIS = 2_000L
    }
}
