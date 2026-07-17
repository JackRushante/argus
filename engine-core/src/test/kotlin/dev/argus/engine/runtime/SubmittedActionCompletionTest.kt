package dev.argus.engine.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SubmittedActionCompletionTest {
    private val executionId = ExecutionId("exec-budget")

    @Test
    fun `suppressedStatus budget accettato con outcome failed`() {
        val completion = SubmittedActionCompletion(
            executionId = executionId,
            actionIndex = 0,
            outcome = ActionJournalOutcome.FAILED,
            atMillis = 100,
            errorCode = "budget_exceeded",
            suppressedStatus = ExecutionStatus.SUPPRESSED_BUDGET,
        )
        assertEquals(ExecutionStatus.SUPPRESSED_BUDGET, completion.suppressedStatus)
    }

    @Test
    fun `suppressedStatus assente resta null di default`() {
        val completion = SubmittedActionCompletion(
            executionId = executionId,
            actionIndex = 0,
            outcome = ActionJournalOutcome.SUCCEEDED,
            atMillis = 100,
        )
        assertNull(completion.suppressedStatus)
    }

    @Test
    fun `suppressedStatus diverso da budget rifiutato`() {
        assertFailsWith<IllegalArgumentException> {
            SubmittedActionCompletion(
                executionId = executionId,
                actionIndex = 0,
                outcome = ActionJournalOutcome.FAILED,
                atMillis = 100,
                suppressedStatus = ExecutionStatus.SUPPRESSED_COOLDOWN,
            )
        }
    }

    @Test
    fun `suppressedStatus con outcome non failed rifiutato`() {
        assertFailsWith<IllegalArgumentException> {
            SubmittedActionCompletion(
                executionId = executionId,
                actionIndex = 0,
                outcome = ActionJournalOutcome.SUCCEEDED,
                atMillis = 100,
                suppressedStatus = ExecutionStatus.SUPPRESSED_BUDGET,
            )
        }
    }
}
