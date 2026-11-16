package dev.argus.engine.runtime

import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.Trigger

/**
 * Un momento in cui una regola `calendar_event` deve scattare: inizio o fine di un'istanza, spostati
 * dell'offset. [key] identifica l'istanza e la fase (id evento + inizio + fase) per la deduplica: ogni
 * istanza scatta una sola volta per fase, anche se l'orologio torna indietro o la sveglia si ripete.
 */
data class CalendarOccurrence(
    val eventId: Long,
    val beginMillis: Long,
    val phase: CalendarPhase,
    val fireAtMillis: Long,
    /** Il titolo dell'evento, per il payload `TITLE`. Dato esterno: mai nei log. */
    val title: String?,
) {
    val key: String get() = "$eventId:$beginMillis:${phase.name}"

    override fun toString(): String = "CalendarOccurrence(key=$key, fireAt=$fireAtMillis)"
}

/** Pianificazione deterministica del trigger `calendar_event` sulle istanze lette dal calendario. */
object CalendarTriggerPlanner {
    /** Quanto avanti si cerca la prossima istanza. */
    const val LOOKAHEAD_MILLIS: Long = 7L * 24 * 60 * 60 * 1_000

    /**
     * Ritardo massimo con cui una sveglia può ancora far scattare la sua istanza (le sveglie inesatte e
     * il risparmio energetico la spostano). Oltre, l'istanza si salta.
     */
    const val LATE_GRACE_MILLIS: Long = 30L * 60 * 1_000

    /** Una sveglia arrivata appena prima del suo istante conta come puntuale. */
    const val EARLY_TOLERANCE_MILLIS: Long = 2_000

    /** Limite dell'offset, uguale a quello del validator. */
    const val MAX_OFFSET_MINUTES: Int = 120

    private const val MINUTE_MILLIS = 60_000L

    /** Intervallo di istanze da leggere: ogni istanza il cui inizio o fine spostati possono servire. */
    fun queryWindow(nowMillis: Long): Pair<Long, Long> {
        val margin = MAX_OFFSET_MINUTES * MINUTE_MILLIS
        return (nowMillis - LATE_GRACE_MILLIS - margin) to (nowMillis + LOOKAHEAD_MILLIS + margin)
    }

    /** Ogni istanza che conta e combacia coi filtri, con il suo istante di scatto, in ordine. */
    fun occurrences(trigger: Trigger.CalendarEvent, instances: List<CalendarInstance>): List<CalendarOccurrence> =
        instances.asSequence()
            .filter { CalendarMatching.matches(it, trigger.titleMatch, trigger.calendar) }
            .map { instance ->
                val anchor = when (trigger.phase) {
                    CalendarPhase.START -> instance.beginMillis
                    CalendarPhase.END -> instance.endMillis
                }
                CalendarOccurrence(
                    eventId = instance.eventId,
                    beginMillis = instance.beginMillis,
                    phase = trigger.phase,
                    fireAtMillis = anchor + trigger.offsetMinutes * MINUTE_MILLIS,
                    title = instance.title,
                )
            }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.fireAtMillis }, { it.key }))
            .toList()

    /** Le istanze il cui momento è arrivato (entro [LATE_GRACE_MILLIS]) e non ancora scattate. */
    fun due(
        trigger: Trigger.CalendarEvent,
        instances: List<CalendarInstance>,
        nowMillis: Long,
        fired: Set<String>,
    ): List<CalendarOccurrence> = occurrences(trigger, instances).filter { occurrence ->
        occurrence.key !in fired &&
            occurrence.fireAtMillis >= nowMillis - LATE_GRACE_MILLIS &&
            occurrence.fireAtMillis <= nowMillis + EARLY_TOLERANCE_MILLIS
    }

    /** La prossima istanza futura entro [LOOKAHEAD_MILLIS], non ancora scattata; null = nessuna. */
    fun next(
        trigger: Trigger.CalendarEvent,
        instances: List<CalendarInstance>,
        nowMillis: Long,
        fired: Set<String>,
    ): CalendarOccurrence? = occurrences(trigger, instances).firstOrNull { occurrence ->
        occurrence.key !in fired &&
            occurrence.fireAtMillis > nowMillis + EARLY_TOLERANCE_MILLIS &&
            occurrence.fireAtMillis <= nowMillis + LOOKAHEAD_MILLIS
    }
}
