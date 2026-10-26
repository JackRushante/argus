package dev.argus.shizuku

/**
 * Interruttore per il bind del servizio privilegiato. Se il servizio non si collega, ogni richiesta
 * aspetterebbe il timeout del bind (15 s) e le regole si accoderebbero per minuti. Dopo un bind
 * fallito o scaduto ([trip]) le richieste successive falliscono subito per [openMillis]; scaduta la
 * finestra si ritenta un bind. Un cambio dell'epoca di stato di Shizuku (vedi
 * [ShizukuGateway.statusEpoch]) azzera il blocco: è cambiato qualcosa, vale la pena ritentare.
 *
 * [clock] deve essere monotono (millisecondi); nei test è un orologio finto.
 */
internal class BindCircuitBreaker(
    private val clock: () -> Long,
    private val openMillis: Long = DEFAULT_OPEN_MILLIS,
) {
    private var openedAt = NOT_OPEN
    private var openedEpoch = 0L

    @Synchronized
    fun isOpen(epoch: Long): Boolean {
        if (openedAt == NOT_OPEN) return false
        if (epoch != openedEpoch || clock() - openedAt >= openMillis) {
            openedAt = NOT_OPEN
            return false
        }
        return true
    }

    @Synchronized
    fun trip(epoch: Long) {
        openedAt = clock()
        openedEpoch = epoch
    }

    @Synchronized
    fun reset() {
        openedAt = NOT_OPEN
    }

    companion object {
        const val DEFAULT_OPEN_MILLIS = 60_000L
        private const val NOT_OPEN = Long.MIN_VALUE
    }
}
