package dev.argus.data

import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.runtime.ActionJournalEntry
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.SubmittedActionCompletion
import dev.argus.engine.runtime.SubmittedActionJournal
import dev.argus.engine.runtime.SubmittedActionState
import kotlinx.coroutines.flow.Flow

class RoomExecutionJournal(
    private val dao: dev.argus.data.dao.ExecutionJournalDao,
) : ExecutionJournal, SubmittedActionJournal {
    override suspend fun recordAction(entry: ActionJournalEntry) {
        check(dao.upsertActionIfActive(
            ActionResultEntity(
                executionId = entry.executionId.value,
                actionIndex = entry.actionIndex,
                actionType = entry.actionType,
                outcome = entry.outcome,
                atMillis = entry.atMillis,
                errorCode = entry.errorCode,
                actionPath = entry.actionPath,
            ),
        )) { "Esecuzione assente o già terminale" }
    }

    override suspend fun finish(completion: ExecutionCompletion) {
        check(dao.finish(
            executionId = completion.executionId.value,
            status = completion.status,
            completedAtMillis = completion.completedAtMillis,
            succeededCount = completion.succeededCount,
            failedCount = completion.failedCount,
            submittedCount = completion.submittedCount,
            deferredCount = completion.deferredCount,
        ) == 1) { "Esecuzione assente o già terminale" }
    }

    override fun observeSubmission(
        executionId: ExecutionId,
        actionIndex: Int,
    ): Flow<SubmittedActionState?> {
        require(actionIndex >= 0) { "actionIndex non può essere negativo" }
        return dao.observeSubmission(executionId.value, actionIndex)
    }

    override suspend fun resolveSubmitted(completion: SubmittedActionCompletion): Boolean =
        dao.resolveSubmitted(completion)
}
