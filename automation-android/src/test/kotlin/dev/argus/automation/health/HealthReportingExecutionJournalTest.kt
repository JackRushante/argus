package dev.argus.automation.health

import dev.argus.engine.runtime.ActionJournalEntry
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.SubmittedActionCompletion
import dev.argus.engine.runtime.SubmittedActionJournal
import dev.argus.engine.runtime.SubmittedActionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HealthReportingExecutionJournalTest {
    private val delegate = RecordingJournal()
    private val concluded = mutableListOf<ExecutionId>()
    private val journal = HealthReportingExecutionJournal(delegate, delegate) { concluded += it }

    @Test
    fun `outcomes are reported after the write, open and suppressed ones are not`() = runTest {
        ExecutionStatus.entries.filter { it != ExecutionStatus.RUNNING }.forEach { status ->
            journal.finish(ExecutionCompletion(ExecutionId(status.name), status, 1, 0, 0, 0))
        }
        assertEquals(ExecutionStatus.entries.size - 1, delegate.finished.size)
        assertEquals(
            setOf("SUCCEEDED", "FAILED", "PARTIAL", "INTERRUPTED").map(::ExecutionId).toSet(),
            concluded.toSet(),
        )
        assertEquals(4, concluded.size)
    }

    @Test
    fun `a failed write is not reported and reaches the caller`() = runTest {
        delegate.failNext = true
        assertFailsWith<IllegalStateException> {
            journal.finish(ExecutionCompletion(ExecutionId("x"), ExecutionStatus.FAILED, 1, 0, 1, 0))
        }
        assertTrue(concluded.isEmpty())
    }

    @Test
    fun `a resolved generative action is reported, an unresolved one is not`() = runTest {
        val completion = SubmittedActionCompletion(ExecutionId("gen"), 0, ActionJournalOutcome.SUCCEEDED, 1)
        delegate.resolves = false
        journal.resolveSubmitted(completion)
        assertTrue(concluded.isEmpty())

        delegate.resolves = true
        journal.resolveSubmitted(completion)
        assertEquals(listOf(ExecutionId("gen")), concluded)
    }

    private class RecordingJournal : ExecutionJournal, SubmittedActionJournal {
        val finished = mutableListOf<ExecutionCompletion>()
        var failNext = false
        var resolves = true

        override suspend fun recordAction(entry: ActionJournalEntry) = Unit

        override suspend fun finish(completion: ExecutionCompletion) {
            if (failNext) {
                failNext = false
                error("Esecuzione assente o già terminale")
            }
            finished += completion
        }

        override fun observeSubmission(executionId: ExecutionId, actionIndex: Int): Flow<SubmittedActionState?> = flowOf(null)

        override suspend fun resolveSubmitted(completion: SubmittedActionCompletion): Boolean = resolves
    }
}
