package dev.argus.ui.presentation

import dev.argus.ui.model.LogRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Un giorno del Log: [key] stabile per le liste, [title] già nella lingua dell'app. */
data class LogDayGroup(val key: String, val title: String, val rows: List<LogRow>)

/**
 * Raggruppa le righe del Log per GIORNO LOCALE a partire dal timestamp ([LogRow.atMillis]), nel fuso
 * del telefono. Prima si spezzava il testo dell'orario («oggi 23:00») sul primo spazio: bastava una
 * lingua o un formato diverso per rompere i giorni. Le righe senza timestamp (anteprime, fixture)
 * ricadono su quel vecchio criterio, che in produzione non si usa mai: il ViewModel imposta sempre
 * [LogRow.atMillis].
 *
 * Le righe arrivano già dalla più recente alla più vecchia e l'ordine si conserva.
 */
object LogDayGrouping {
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT)

    fun group(
        entries: List<LogRow>,
        nowMillis: Long,
        zone: ZoneId,
        language: RenderLanguage,
    ): List<LogDayGroup> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val groups = LinkedHashMap<String, MutableList<LogRow>>()
        val titles = HashMap<String, String>()
        for (row in entries) {
            val at = row.atMillis
            val key: String
            val title: String
            if (at != null) {
                val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                key = "day:$day"
                title = dayTitle(day, today, language)
            } else {
                val prefix = legacyDayKey(row.timeLabel)
                key = "text:$prefix"
                title = prefix
            }
            groups.getOrPut(key) { mutableListOf() }.add(row)
            titles.putIfAbsent(key, title)
        }
        return groups.map { (key, rows) -> LogDayGroup(key, titles.getValue(key), rows) }
    }

    /** Ora locale `HH:mm` della riga; senza timestamp, la parte oraria del testo. */
    fun timeLabel(row: LogRow, zone: ZoneId): String {
        val at = row.atMillis ?: return legacyTimePart(row.timeLabel)
        return Instant.ofEpochMilli(at).atZone(zone).format(TIME_FORMAT)
    }

    private fun dayTitle(day: LocalDate, today: LocalDate, language: RenderLanguage): String =
        when (ChronoUnit.DAYS.between(day, today)) {
            0L -> language.pick("Today", "Oggi")
            1L -> language.pick("Yesterday", "Ieri")
            else -> day.format(DAY_FORMAT)
        }

    private fun legacyDayKey(timeLabel: String): String = timeLabel.substringBefore(' ', timeLabel).trim()

    private fun legacyTimePart(timeLabel: String): String {
        val idx = timeLabel.indexOf(' ')
        return if (idx >= 0) timeLabel.substring(idx + 1).trim() else timeLabel
    }
}
