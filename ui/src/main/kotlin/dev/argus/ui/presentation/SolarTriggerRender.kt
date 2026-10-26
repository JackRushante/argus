package dev.argus.ui.presentation

import dev.argus.engine.model.SolarEvent
import dev.argus.engine.model.SolarSpec
import java.util.Locale
import kotlin.math.abs

/**
 * Testo di approvazione della variante solare del trigger `time` (0.4.1): «Ogni giorno, 30 min prima
 * del tramonto». Generato dai tipi (invariante 4), EN/IT. Mostra sempre dove si calcola: in bozza la
 * posizione non c'è ancora e dice che si legge dal telefono all'attivazione; a regola armata mostra
 * le coordinate congelate (arrotondate), così chi approva vede il luogo realmente usato.
 */
internal object SolarTriggerRender {
    fun line(spec: SolarSpec, l: RenderLanguage): String {
        val sunrise = spec.event == SolarEvent.SUNRISE
        val offset = spec.offsetMinutes
        val timing = when {
            offset == 0 -> if (sunrise) {
                l.pick("at sunrise", "all'alba")
            } else {
                l.pick("at sunset", "al tramonto")
            }
            offset < 0 -> if (sunrise) {
                l.pick("${span(offset)} before sunrise", "${span(offset)} prima dell'alba")
            } else {
                l.pick("${span(offset)} before sunset", "${span(offset)} prima del tramonto")
            }
            else -> if (sunrise) {
                l.pick("${span(offset)} after sunrise", "${span(offset)} dopo l'alba")
            } else {
                l.pick("${span(offset)} after sunset", "${span(offset)} dopo il tramonto")
            }
        }
        val lat = spec.lat
        val lng = spec.lng
        val place = if (lat != null && lng != null) {
            l.pick(
                "near ${coordinate(lat)}, ${coordinate(lng)}",
                "vicino a ${coordinate(lat)}, ${coordinate(lng)}",
            )
        } else {
            l.pick(
                "at the phone's position, read when you activate the rule",
                "alla posizione del telefono, letta quando attivi la regola",
            )
        }
        val separator = if (offset == 0) " " else ", "
        return l.pick("Every day$separator$timing", "Ogni giorno$separator$timing") + " · $place"
    }

    /** «30 min», «2 h», «1 h 30 min»: uguale nelle due lingue. */
    private fun span(minutes: Int): String {
        val total = abs(minutes)
        val hours = total / 60
        val rest = total % 60
        return when {
            hours > 0 && rest > 0 -> "$hours h $rest min"
            hours > 0 -> "$hours h"
            else -> "$rest min"
        }
    }

    private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
