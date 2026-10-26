package dev.argus.ui.screens

import dev.argus.ui.model.AutomationDetailState
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.RuleStatsUi
import dev.argus.ui.model.RuleStatsWindow
import dev.argus.ui.model.StatusBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationDetailScreenLogicTest {
    private fun detail(status: StatusBadge, canRunNow: Boolean) = AutomationDetailState(
        id = "a1",
        name = "regola",
        status = status,
        rule = RuleRender(
            triggerLine = "Quando: ora",
            triggerIconKey = "time",
            conditionLines = emptyList(),
            actions = emptyList(),
            isGenerative = false,
            privacyNote = null,
        ),
        rationale = null,
        warnings = emptyList(),
        canArm = false,
        armBlockedReason = null,
        estimatedLlmCallsPerDay = null,
        recentRuns = emptyList(),
        geofencePreviewLabel = null,
        canRunNow = canRunNow,
        runNowBlockedReason = "non disponibile",
    )

    @Test
    fun `run now is not offered while it cannot run`() {
        assertFalse(showRunNowAction(detail(StatusBadge.ARMED, canRunNow = false)))
        assertFalse(showRunNowAction(detail(StatusBadge.DISABLED, canRunNow = false)))
    }

    @Test
    fun `run now is offered as a secondary action once it can run`() {
        assertTrue(showRunNowAction(detail(StatusBadge.ARMED, canRunNow = true)))
    }

    @Test
    fun `run now is never offered outside the armed and disabled footer`() {
        assertFalse(showRunNowAction(detail(StatusBadge.PENDING_APPROVAL, canRunNow = true)))
        assertFalse(showRunNowAction(detail(StatusBadge.NEEDS_REVIEW, canRunNow = true)))
    }

    // ---- F10: statistiche della regola -----------------------------------------------------------

    private val week = RuleStatsWindow(fires = 3, succeeded = 2, failed = 1)
    private val month = RuleStatsWindow(fires = 12, succeeded = 10, failed = 2)

    @Test
    fun `stats show a 7 and a 30 day column`() {
        val columns = statsColumns(RuleStatsUi(week, month))

        assertEquals(listOf(7 to week, 30 to month), columns)
    }

    @Test
    fun `a log kept for 7 days shows one column only`() {
        val columns = statsColumns(RuleStatsUi(week, week, longWindowDays = 7))

        assertEquals(listOf(7 to week), columns)
    }

    @Test
    fun `no firing in the long window is the empty sentence, not a table of zeros`() {
        val none = RuleStatsWindow(0, 0, 0)

        assertTrue(RuleStatsUi(none, none).isEmpty)
        assertFalse(RuleStatsUi(none, month).isEmpty)
        assertFalse(RuleStatsUi(week, month).isEmpty)
    }

    @Test
    fun `a rule that fired only before the last week is not empty`() {
        val none = RuleStatsWindow(0, 0, 0)

        assertFalse(RuleStatsUi(last7Days = none, last30Days = month).isEmpty)
    }

    @Test
    fun `the detail state carries stats only when the host provides them`() {
        val base = AutomationDetailState(
            id = "a1",
            name = "regola",
            status = StatusBadge.ARMED,
            rule = RuleRender("Quando: ora", "time", emptyList(), emptyList(), false, null),
            rationale = null,
            warnings = emptyList(),
            canArm = false,
            armBlockedReason = null,
            estimatedLlmCallsPerDay = null,
            recentRuns = emptyList(),
            geofencePreviewLabel = null,
        )

        assertEquals(null, base.stats)
        assertEquals(RuleStatsUi(week, month), base.copy(stats = RuleStatsUi(week, month)).stats)
    }
}
