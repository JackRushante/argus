package dev.argus.engine.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** Alba o tramonto (0.4.1): il momento in cui il bordo superiore del Sole attraversa l'orizzonte. */
enum class SolarEvent { SUNRISE, SUNSET }

/**
 * Variante «solare» del trigger `time` (0.4.1): ogni giorno all'alba o al tramonto, spostati di
 * [offsetMinutes] (negativo = prima, positivo = dopo). Si calcola sul telefono, senza rete.
 *
 * La posizione NON la scrive mai il modello: [lat] e [lng] sono null in bozza e li riempie l'app
 * all'attivazione con la posizione del telefono (come `resolveCurrentLocation` della geofence), così
 * l'impronta approvata copre il luogo realmente usato. Sono omessi dal wire quando null.
 */
@Serializable
data class SolarSpec(
    val event: SolarEvent,
    val offsetMinutes: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lat: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lng: Double? = null,
) {
    /** true quando l'app ha già congelato la posizione del telefono. */
    val located: Boolean get() = lat != null && lng != null
}

object SolarTriggerPolicy {
    /** Spostamento massimo dall'alba o dal tramonto: 12 ore, prima o dopo. */
    const val MIN_OFFSET_MINUTES = -720
    const val MAX_OFFSET_MINUTES = 720

    /**
     * Giorni in avanti in cui si cerca un evento quando oggi non ne ha (notte o giorno polare);
     * passati senza trovarne, si ricontrolla dopo [RECHECK_HOURS].
     */
    const val LOOKAHEAD_DAYS = 2L
    const val RECHECK_HOURS = 24L

    /**
     * Precisione con cui l'app arrotonda la posizione congelata: un decimo di grado (circa 11 km). Il
     * Sole si sposta di 4 minuti per grado di longitudine, quindi l'errore resta sotto i 15 secondi,
     * e nella regola (che finisce in backup e in approvazione) resta una zona, non un indirizzo.
     */
    const val COORDINATE_STEPS_PER_DEGREE = 10

    fun validOffset(minutes: Int): Boolean = minutes in MIN_OFFSET_MINUTES..MAX_OFFSET_MINUTES

    fun validLatitude(value: Double): Boolean = value.isFinite() && value in -90.0..90.0
    fun validLongitude(value: Double): Boolean = value.isFinite() && value in -180.0..180.0

    /** Arrotonda una coordinata al decimo di grado ([COORDINATE_STEPS_PER_DEGREE]). */
    fun coarsen(degrees: Double): Double =
        Math.round(degrees * COORDINATE_STEPS_PER_DEGREE) / COORDINATE_STEPS_PER_DEGREE.toDouble()
}
