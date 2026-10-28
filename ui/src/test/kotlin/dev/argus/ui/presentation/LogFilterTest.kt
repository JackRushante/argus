package dev.argus.ui.presentation

import dev.argus.engine.runtime.AuditKind
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Filtro del Log per esito (0.7.0): locale, sulle righe gia' caricate, senza dati nuovi dal ViewModel. */
class LogFilterTest {
    private fun row(
        id: String,
        kind: AuditKind,
        outcome: LogOutcome = LogOutcome.SUCCESS,
        generative: Boolean = false,
    ) = LogRow(
        id = id,
        timeLabel = "oggi 10:00",
        automationName = "Regola",
        kind = kind,
        outcome = outcome,
        summary = "riepilogo",
        expandedDetail = null,
        isGenerative = generative,
    )

    private val ok = row("ok", AuditKind.FIRED)
    private val partial = row("partial", AuditKind.FIRED, LogOutcome.PARTIAL)
    private val failed = row("failed", AuditKind.FIRED, LogOutcome.FAILED)
    private val inProgress = row("progress", AuditKind.FIRED, LogOutcome.SUBMITTED, generative = true)
    private val deferred = row("deferred", AuditKind.FIRED, LogOutcome.DEFERRED, generative = true)
    private val cooldown = row("cooldown", AuditKind.SUPPRESSED_COOLDOWN)
    private val notMet = row("notmet", AuditKind.CONDITIONS_NOT_MET)
    private val blocked = row("blocked", AuditKind.BLOCKED_POLICY, LogOutcome.FAILED)
    private val error = row("error", AuditKind.ERROR, LogOutcome.FAILED)
    private val budget = row("budget", AuditKind.SUPPRESSED_BUDGET, LogOutcome.PARTIAL)
    private val armed = row("armed", AuditKind.RULE_ARMED)
    private val disabled = row("disabled", AuditKind.RULE_DISABLED)
    private val review = row("review", AuditKind.RULE_NEEDS_REVIEW, LogOutcome.FAILED)
    private val everything = listOf(
        ok, partial, failed, inProgress, deferred, cooldown, notMet, blocked, error, budget, armed, disabled, review,
    )

    private fun ids(filter: LogFilter) = LogFilter.apply(filter, everything).map { it.id }

    @Test
    fun `all keeps every row in order`() {
        assertEquals(everything, LogFilter.apply(LogFilter.ALL, everything))
    }

    @Test
    fun `problems are failures, blocks, partial runs and rules to review`() {
        assertEquals(listOf("partial", "failed", "blocked", "error", "budget", "review"), ids(LogFilter.PROBLEMS))
    }

    @Test
    fun `quiet noise and successes are not problems`() {
        listOf(ok, cooldown, notMet, armed, disabled, inProgress, deferred).forEach {
            assertFalse(LogFilter.PROBLEMS.matches(it), it.id)
        }
    }

    @Test
    fun `generative keeps the rows of executions with a generative action`() {
        assertEquals(listOf("progress", "deferred"), ids(LogFilter.GENERATIVE))
    }

    @Test
    fun `lifecycle keeps the transitions of a rule, not its executions`() {
        assertEquals(listOf("armed", "disabled", "review"), ids(LogFilter.LIFECYCLE))
        assertFalse(LogFilter.LIFECYCLE.matches(ok))
    }

    @Test
    fun `a rule put under review is both a problem and a lifecycle event`() {
        assertTrue(LogFilter.PROBLEMS.matches(review))
        assertTrue(LogFilter.LIFECYCLE.matches(review))
    }

    @Test
    fun `filtering an empty log stays empty for every filter`() {
        LogFilter.entries.forEach { assertEquals(emptyList(), LogFilter.apply(it, emptyList()), it.name) }
    }

    @Test
    fun `a missing value is written n-a, never zero or blank`() {
        assertEquals("n/d", (null as String?).orNotAvailable("n/d"))
        assertEquals("n/d", "  ".orNotAvailable("n/d"))
        assertEquals("2/2", "2/2".orNotAvailable("n/d"))
    }
}
