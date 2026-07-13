package dev.argus.data

import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.runtime.ActionJournalEntry
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionJournal

class RoomExecutionJournal(private val dao: dev.argus.data.dao.ExecutionJournalDao) : ExecutionJournal {
    override suspend fun recordAction(entry: ActionJournalEntry) {
        check(dao.upsertActionIfActive(
            ActionResultEntity(
                executionId = entry.executionId.value,
                actionIndex = entry.actionIndex,
                actionType = entry.actionType,
                outcome = entry.outcome,
                atMillis = entry.atMillis,
                errorCode = entry.errorCode,
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
        ) == 1) { "Esecuzione assente o già terminale" }
    }
}
