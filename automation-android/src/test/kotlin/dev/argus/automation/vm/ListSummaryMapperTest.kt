package dev.argus.automation.vm

import dev.argus.data.dao.AuditLogRecord
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.PauseCodes
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.TodayErrorsSummary
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Riepilogo in testa alla lista (0.7.0, nota n1): conteggi sull'elenco completo, prossimo scatto, errori di oggi. */
class ListSummaryMapperTest {
    private fun row(
        id: String,
        status: StatusBadge,
        pendingEdit: String? = null,
        nextLabel: String? = null,
        nextAt: Long? = null,
    ) = AutomationRow(
        id = id, name = "regola $id", triggerIconKey = "time", triggerSummary = "s", status = status,
        enabled = status == StatusBadge.ARMED, isGenerative = false, hasWarnings = false,
        lastFiredLabel = null, nextFireLabel = nextLabel, pendingEditDraftId = pendingEdit, nextFireAtMillis = nextAt,
    )

    private fun record(
        id: Long,
        kind: AuditKind,
        at: Long,
        name: String? = "Backup foto notturno",
        detail: String = "",
        status: ExecutionStatus? = null,
        failed: Int? = null,
    ) = AuditLogRecord(
        id = id, automationId = "auto-$id", automationName = name, kind = kind, atMillis = at, detail = detail,
        executionId = null, executionStatus = status, succeededCount = null, failedCount = failed, submittedCount = null,
    )

    // --- listSummaryOf ----------------------------------------------------------------------------

    @Test
    fun `no rules, no summary`() {
        assertNull(listSummaryOf(emptyList(), todayErrors = null))
    }

    @Test
    fun `the counters count the complete list with the same meaning as the filters`() {
        val rows = listOf(
            row("a1", StatusBadge.ARMED),
            row("a2", StatusBadge.ARMED, pendingEdit = "draft-9"),
            row("p1", StatusBadge.PENDING_APPROVAL),
            row("n1", StatusBadge.NEEDS_REVIEW),
            row("d1", StatusBadge.DISABLED),
        )

        val summary = assertNotNull(listSummaryOf(rows, todayErrors = null))

        assertEquals(2, summary.armedCount)
        assertEquals(2, summary.pendingCount, "una bozza nuova e una regola attiva con una modifica in attesa")
        assertEquals(1, summary.needsReviewCount)
    }

    @Test
    fun `the next firing is the earliest one and rows without a label are ignored`() {
        val rows = listOf(
            row("late", StatusBadge.ARMED, nextLabel = "domani 09:00", nextAt = 9_000L),
            row("soon", StatusBadge.ARMED, nextLabel = "oggi 23:00", nextAt = 2_000L),
            row("none", StatusBadge.ARMED),
            row("half", StatusBadge.ARMED, nextAt = 1_000L),
        )

        val next = assertNotNull(assertNotNull(listSummaryOf(rows, null)).nextFire)

        assertEquals("soon", next.ruleId)
        assertEquals("regola soon", next.ruleName)
        assertEquals("oggi 23:00", next.label)
        assertEquals(2_000L, next.atMillis)
    }

    @Test
    fun `no time rule armed means no next firing, not an invented one`() {
        val summary = assertNotNull(listSummaryOf(listOf(row("a1", StatusBadge.ARMED)), null))
        assertNull(summary.nextFire)
    }

    @Test
    fun `the today errors are carried as they are, unknown stays unknown`() {
        val rows = listOf(row("a1", StatusBadge.ARMED))
        assertNull(assertNotNull(listSummaryOf(rows, todayErrors = null)).todayErrors)

        val errors = TodayErrorsSummary(2, "Backup", "12:05")
        assertEquals(errors, assertNotNull(listSummaryOf(rows, errors)).todayErrors)
    }

    // --- todayErrorsOf ------------------------------------------------------------------------------

    @Test
    fun `only the red rows of the log are errors and the last one names the rule and the time`() {
        val at = Instant.parse("2026-10-28T10:05:00Z").toEpochMilli()
        val records = listOf(
            record(1, AuditKind.ERROR, at + 60_000, name = "Rispondi a Moglie"),
            record(2, AuditKind.FIRED, at + 30_000, status = ExecutionStatus.SUCCEEDED),
            record(3, AuditKind.FIRED, at, status = ExecutionStatus.FAILED, failed = 1),
            record(4, AuditKind.ARM_FAILED, at - 60_000, name = "Backup foto notturno"),
            record(5, AuditKind.RULE_ARMED, at + 90_000),
            record(6, AuditKind.CONDITIONS_NOT_MET, at + 95_000),
            // Uno scatto saltato per la pausa non e' un guasto.
            record(7, AuditKind.BLOCKED_POLICY, at + 99_000, detail = PauseCodes.PAUSED),
        )

        val errors = todayErrorsOf(records, RenderLanguage.IT)

        assertEquals(3, errors.count)
        assertEquals("Rispondi a Moglie", errors.lastRuleName)
        assertEquals(clockLabel(at + 60_000), errors.lastTimeLabel)
    }

    @Test
    fun `a day without errors is zero errors with nothing to name`() {
        val errors = todayErrorsOf(listOf(record(1, AuditKind.RULE_ARMED, 1_000L)), RenderLanguage.EN)

        assertEquals(0, errors.count)
        assertNull(errors.lastRuleName)
        assertNull(errors.lastTimeLabel)
        assertEquals(0, todayErrorsOf(emptyList(), RenderLanguage.EN).count)
    }

    @Test
    fun `an error of a deleted rule names it in the language of the phone`() {
        val records = listOf(record(1, AuditKind.ERROR, 1_000L, name = null))

        assertEquals("Deleted automation", todayErrorsOf(records, RenderLanguage.EN).lastRuleName)
        assertEquals("Automazione rimossa", todayErrorsOf(records, RenderLanguage.IT).lastRuleName)
    }

    // --- orologio -------------------------------------------------------------------------------------

    @Test
    fun `the clock label is hours and minutes in the given zone`() {
        val at = Instant.parse("2026-10-28T10:05:00Z").toEpochMilli()
        assertEquals("10:05", clockLabel(at, ZoneId.of("UTC")))
        assertEquals("11:05", clockLabel(at, ZoneId.of("Europe/Rome")))
    }

    @Test
    fun `the day starts at the local midnight, also across a daylight saving change`() {
        val rome = ZoneId.of("Europe/Rome")
        // 28 ottobre 2026: l'ora legale e' finita il 25, mezzanotte locale = 23:00 UTC del giorno prima.
        val afterChange = startOfDayMillis(ZonedDateTime.of(2026, 10, 28, 15, 30, 0, 0, rome))
        assertEquals(Instant.parse("2026-10-27T23:00:00Z").toEpochMilli(), afterChange)
        // 25 ottobre 2026 (giorno di 25 ore): mezzanotte e' ancora in ora legale = 22:00 UTC del 24.
        val changeDay = startOfDayMillis(ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, rome))
        assertEquals(Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), changeDay)
    }

    @Test
    fun `the start of today flow begins with today and not with tomorrow`() = runBlocking {
        val before = System.currentTimeMillis()
        val start = startOfTodayFlow().first()

        assertTrue(start <= before, "l'inizio del giorno non e' nel futuro")
        assertTrue(before - start < 25 * 3_600_000L, "l'inizio del giorno e' di oggi")
    }
}
