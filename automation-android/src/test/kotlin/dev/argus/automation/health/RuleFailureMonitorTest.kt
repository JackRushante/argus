package dev.argus.automation.health

import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.ExecutionStatus.FAILED
import dev.argus.engine.runtime.ExecutionStatus.INTERRUPTED
import dev.argus.engine.runtime.ExecutionStatus.PARTIAL
import dev.argus.engine.runtime.ExecutionStatus.SUCCEEDED
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleFailureMonitorTest {
    private val id = AutomationId("rule-1")
    private val outcomes = mutableListOf<ExecutionStatus>()
    private var day = 100L
    private var name: String? = "Silenzioso di sera"
    private val store = FakeHealthAlertStore()
    private val notifier = RecordingHealthNotifier()
    private val monitor = RuleFailureMonitor(
        recentOutcomes = { _, limit -> outcomes.take(limit) },
        ruleName = { name },
        store = store,
        notifier = notifier,
        today = { day },
    )

    /** Una nuova esecuzione conclusa, la più recente in testa (come la query del journal). */
    private suspend fun conclude(status: ExecutionStatus) {
        outcomes.add(0, status)
        monitor.onExecutionConcluded(id)
    }

    @Test
    fun `policy alerts only on three failures in a row`() {
        assertEquals(RuleHealthDecision.ALERT, RuleHealthPolicy.decide(listOf(FAILED, PARTIAL, INTERRUPTED), false, null, 1))
        assertEquals(RuleHealthDecision.NONE, RuleHealthPolicy.decide(listOf(FAILED, FAILED), false, null, 1))
        assertEquals(RuleHealthDecision.NONE, RuleHealthPolicy.decide(listOf(FAILED, SUCCEEDED, FAILED), false, null, 1))
        assertEquals(RuleHealthDecision.NONE, RuleHealthPolicy.decide(emptyList(), false, null, 1))
    }

    @Test
    fun `policy never alerts twice for the same streak or twice on the same day`() {
        val streak = listOf(FAILED, FAILED, FAILED)
        assertEquals(RuleHealthDecision.NONE, RuleHealthPolicy.decide(streak, alreadyAlerted = true, lastAlertDay = 1, today = 2))
        assertEquals(RuleHealthDecision.NONE, RuleHealthPolicy.decide(streak, alreadyAlerted = false, lastAlertDay = 2, today = 2))
        assertEquals(RuleHealthDecision.ALERT, RuleHealthPolicy.decide(streak, alreadyAlerted = false, lastAlertDay = 1, today = 2))
    }

    @Test
    fun `a success after an alert is a recovery`() {
        assertEquals(RuleHealthDecision.RECOVERED, RuleHealthPolicy.decide(listOf(SUCCEEDED, FAILED, FAILED), true, 1, 1))
        assertEquals(RuleHealthDecision.NONE, RuleHealthPolicy.decide(listOf(SUCCEEDED, FAILED, FAILED), false, 1, 1))
    }

    @Test
    fun `third failure in a row notifies once with the rule name`() = runTest {
        conclude(FAILED)
        conclude(FAILED)
        assertTrue(notifier.failing.isEmpty())

        conclude(FAILED)
        assertEquals(listOf("rule-1" to "Silenzioso di sera"), notifier.failing)

        // Il quarto fallimento non ripete l'avviso.
        conclude(FAILED)
        assertEquals(1, notifier.failing.size)
    }

    @Test
    fun `after a success a new streak notifies again but not on the same day`() = runTest {
        repeat(3) { conclude(FAILED) }
        assertEquals(1, notifier.failing.size)

        conclude(SUCCEEDED)
        assertEquals(listOf("rule-1"), notifier.clearedRules)

        repeat(3) { conclude(FAILED) }
        assertEquals(1, notifier.failing.size, "stesso giorno: niente secondo avviso")

        day += 1
        conclude(FAILED)
        assertEquals(2, notifier.failing.size, "il giorno dopo la regola ancora in errore si segnala")
    }

    @Test
    fun `switched off means no notification and nothing is consumed`() = runTest {
        store.setEnabled(false)
        repeat(3) { conclude(FAILED) }
        assertTrue(notifier.failing.isEmpty())

        store.setEnabled(true)
        conclude(FAILED)
        assertEquals(1, notifier.failing.size)
    }

    @Test
    fun `a deleted rule is not notified`() = runTest {
        name = null
        repeat(3) { conclude(FAILED) }
        assertTrue(notifier.failing.isEmpty())
    }
}
