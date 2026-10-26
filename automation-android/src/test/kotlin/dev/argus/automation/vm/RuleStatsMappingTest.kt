package dev.argus.automation.vm

import dev.argus.data.dao.RuleFireStats
import dev.argus.ui.model.RuleStatsWindow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F10: dal conteggio del journal alle statistiche mostrate nel Dettaglio. */
class RuleStatsMappingTest {
    private val counts = RuleFireStats(
        fires7 = 3, succeeded7 = 2, failed7 = 1,
        fires30 = 12, succeeded30 = 10, failed30 = 2,
    )

    @Test
    fun `the two windows map one to one`() {
        val stats = counts.toRuleStatsUi(retentionDays = 30)

        assertEquals(RuleStatsWindow(fires = 3, succeeded = 2, failed = 1), stats.last7Days)
        assertEquals(RuleStatsWindow(fires = 12, succeeded = 10, failed = 2), stats.last30Days)
        assertEquals(30, stats.longWindowDays)
        assertFalse(stats.isEmpty)
    }

    @Test
    fun `a log kept for 90 days still reports a 30 day window`() {
        assertEquals(30, counts.toRuleStatsUi(retentionDays = 90).longWindowDays)
    }

    @Test
    fun `a log kept for 7 days cannot answer for 30`() {
        val stats = counts.toRuleStatsUi(retentionDays = 7)

        assertEquals(7, stats.longWindowDays)
        // Righe vecchie rimaste prima della prossima manutenzione non entrano nei conteggi.
        assertEquals(stats.last7Days, stats.last30Days)
    }

    @Test
    fun `nonsense retention values fall inside the supported range`() {
        assertEquals(7, counts.toRuleStatsUi(retentionDays = 0).longWindowDays)
        assertEquals(30, counts.toRuleStatsUi(retentionDays = 365).longWindowDays)
    }

    @Test
    fun `no firing in the long window is an empty state`() {
        val stats = RuleFireStats(0, 0, 0, 0, 0, 0).toRuleStatsUi(retentionDays = 30)

        assertTrue(stats.isEmpty)
        assertEquals(RuleStatsWindow(0, 0, 0), stats.last7Days)
    }

    @Test
    fun `window starts are rolling days back from now`() {
        val day = 24L * 60 * 60 * 1_000
        val now = 100 * day

        val (since7, since30) = statsWindowStarts(now)

        assertEquals(now - 7 * day, since7)
        assertEquals(now - 30 * day, since30)
    }

    @Test
    fun `window starts never go negative on a fresh clock`() {
        val (since7, since30) = statsWindowStarts(nowMillis = 1_000)

        assertEquals(0L, since7)
        assertEquals(0L, since30)
    }
}
