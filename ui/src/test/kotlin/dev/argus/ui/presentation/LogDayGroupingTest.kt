package dev.argus.ui.presentation

import dev.argus.engine.runtime.AuditKind
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRow
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B49 (0.4.0): il Log raggruppava i giorni spezzando il testo dell'orario («oggi 23:00») sul primo
 * spazio. Ora si raggruppa dal timestamp, nel giorno locale del fuso del telefono.
 */
class LogDayGroupingTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val utc = ZoneId.of("UTC")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** Il testo di [timeLabel] e' volutamente insensato: il raggruppamento non deve leggerlo. */
    private fun row(id: String, atMillis: Long?, timeLabel: String = "???") = LogRow(
        id = id,
        timeLabel = timeLabel,
        automationName = "regola",
        kind = AuditKind.FIRED,
        outcome = LogOutcome.SUCCESS,
        summary = "ok",
        expandedDetail = null,
        atMillis = atMillis,
    )

    @Test
    fun `days are the local days of the phone time zone, not UTC days`() {
        // 21:30Z e 22:30Z del 14 luglio: a Roma (UTC+2) sono le 23:30 del 14 e le 00:30 del 15.
        val late = row("late", at("2026-07-14T21:30:00Z"))
        val after = row("after", at("2026-07-14T22:30:00Z"))
        val now = at("2026-07-15T08:00:00Z")

        val inRome = LogDayGrouping.group(listOf(after, late), now, rome, RenderLanguage.IT)
        val inUtc = LogDayGrouping.group(listOf(after, late), now, utc, RenderLanguage.IT)

        assertEquals(listOf(listOf("after"), listOf("late")), inRome.map { g -> g.rows.map { it.id } })
        assertEquals(listOf(listOf("after", "late")), inUtc.map { g -> g.rows.map { it.id } })
    }

    @Test
    fun `grouping ignores the time label text entirely`() {
        val a = row("a", at("2026-07-14T10:00:00Z"), timeLabel = "oggi 12:00")
        val b = row("b", at("2026-07-14T11:00:00Z"), timeLabel = "ieri 13:00")
        val c = row("c", at("2026-07-13T11:00:00Z"), timeLabel = "oggi 13:00")
        val now = at("2026-07-14T20:00:00Z")

        val groups = LogDayGrouping.group(listOf(a, b, c), now, utc, RenderLanguage.IT)

        assertEquals(listOf(listOf("a", "b"), listOf("c")), groups.map { g -> g.rows.map { it.id } })
    }

    @Test
    fun `titles say today and yesterday in the app language and the date after that`() {
        val today = row("t", at("2026-07-14T09:00:00Z"))
        val yesterday = row("y", at("2026-07-13T09:00:00Z"))
        val older = row("o", at("2026-07-09T09:00:00Z"))
        val now = at("2026-07-14T20:00:00Z")
        val rows = listOf(today, yesterday, older)

        assertEquals(
            listOf("Oggi", "Ieri", "09/07"),
            LogDayGrouping.group(rows, now, utc, RenderLanguage.IT).map { it.title },
        )
        assertEquals(
            listOf("Today", "Yesterday", "09/07"),
            LogDayGrouping.group(rows, now, utc, RenderLanguage.EN).map { it.title },
        )
    }

    @Test
    fun `today and yesterday follow the phone time zone too`() {
        // A Roma sono gia' le 00:30 del 15 luglio: "oggi" e' il 15, la riga delle 23:30 e' di ieri.
        val now = at("2026-07-14T22:30:00Z")
        val lateEvening = row("e", at("2026-07-14T21:30:00Z"))

        val groups = LogDayGrouping.group(listOf(lateEvening), now, rome, RenderLanguage.IT)

        assertEquals("Ieri", groups.single().title)
    }

    @Test
    fun `order of the host is preserved and group keys are unique`() {
        val now = at("2026-07-14T20:00:00Z")
        val rows = listOf(
            row("1", at("2026-07-14T19:00:00Z")),
            row("2", at("2026-07-14T08:00:00Z")),
            row("3", at("2026-07-13T19:00:00Z")),
            row("4", at("2026-07-13T08:00:00Z")),
        )

        val groups = LogDayGrouping.group(rows, now, utc, RenderLanguage.EN)

        assertEquals(listOf("1", "2", "3", "4"), groups.flatMap { g -> g.rows.map { it.id } })
        assertEquals(groups.size, groups.map { it.key }.toSet().size)
    }

    @Test
    fun `the time shown is the local hour and minute of the timestamp`() {
        val event = row("h", at("2026-07-14T21:05:00Z"), timeLabel = "garbled")

        assertEquals("23:05", LogDayGrouping.timeLabel(event, rome))
        assertEquals("21:05", LogDayGrouping.timeLabel(event, utc))
    }

    @Test
    fun `rows built by hand without a timestamp fall back to the text prefix`() {
        val manual = row("m", atMillis = null, timeLabel = "oggi 23:00")

        val groups = LogDayGrouping.group(listOf(manual), 0, utc, RenderLanguage.IT)

        assertEquals("oggi", groups.single().title)
        assertEquals("23:00", LogDayGrouping.timeLabel(manual, utc))
    }

    @Test
    fun `an empty log has no groups`() {
        assertEquals(emptyList(), LogDayGrouping.group(emptyList(), 0, utc, RenderLanguage.EN))
    }
}
