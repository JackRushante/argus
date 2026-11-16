package dev.argus.automation.health

import dev.argus.engine.runtime.ActionJournalEntry
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.SubmittedActionCompletion
import dev.argus.engine.runtime.SubmittedActionJournal
import dev.argus.engine.runtime.SubmittedActionState
import kotlinx.coroutines.flow.Flow

/**
 * Il journal di sempre, che in più segnala a [onConcluded] ogni esecuzione appena chiusa con un esito
 * (riuscita, fallita, parziale, interrotta) o risolta dal percorso generativo. [onConcluded] non deve
 * bloccare né lanciare: chi la fornisce lancia il controllo in background (avvisi di salute, 0.7.2).
 * Le scritture restano identiche: se il journal fallisce, l'errore arriva al chiamante come prima e
 * [onConcluded] non viene chiamata.
 */
class HealthReportingExecutionJournal(
    private val journal: ExecutionJournal,
    private val submitted: SubmittedActionJournal,
    private val onConcluded: (ExecutionId) -> Unit,
) : ExecutionJournal, SubmittedActionJournal {
    override suspend fun recordAction(entry: ActionJournalEntry) = journal.recordAction(entry)

    override suspend fun finish(completion: ExecutionCompletion) {
        journal.finish(completion)
        if (completion.status in OUTCOMES) onConcluded(completion.executionId)
    }

    override fun observeSubmission(executionId: ExecutionId, actionIndex: Int): Flow<SubmittedActionState?> =
        submitted.observeSubmission(executionId, actionIndex)

    override suspend fun resolveSubmitted(completion: SubmittedActionCompletion): Boolean =
        submitted.resolveSubmitted(completion).also { resolved ->
            if (resolved) onConcluded(completion.executionId)
        }

    private companion object {
        val OUTCOMES = setOf(
            ExecutionStatus.SUCCEEDED,
            ExecutionStatus.FAILED,
            ExecutionStatus.PARTIAL,
            ExecutionStatus.INTERRUPTED,
        )
    }
}
