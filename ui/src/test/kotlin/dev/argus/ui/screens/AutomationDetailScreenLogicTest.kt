package dev.argus.ui.screens

import dev.argus.ui.model.AutomationDetailState
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.StatusBadge
import kotlin.test.Test
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
}
