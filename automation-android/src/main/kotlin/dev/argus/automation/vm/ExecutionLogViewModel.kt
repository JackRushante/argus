package dev.argus.automation.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.data.ArgusDatabase
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.ui.model.ExecutionLogState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionLogViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    database: ArgusDatabase,
) : ViewModel() {
    private val audit = database.auditDao()
    private val journal = database.executionJournalDao()
    private val filterId = savedStateHandle.getStateFlow<String?>(FILTER_ID_KEY, null)
    private val filterName = savedStateHandle.getStateFlow<String?>(FILTER_NAME_KEY, null)
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages = mutableMessages.asSharedFlow()

    private val logSources = filterId.flatMapLatest { automationId ->
        val records = if (automationId == null) {
            audit.observeLog(LOG_LIMIT)
        } else {
            audit.observeLogForAutomation(automationId, LOG_LIMIT)
        }
        val actions = if (automationId == null) {
            journal.observeRecentActions(LOG_LIMIT)
        } else {
            journal.observeRecentActionsForAutomation(automationId, LOG_LIMIT)
        }
        combine(records, actions, ::LogSources)
    }

    val state: StateFlow<ExecutionLogState> = combine(
        logSources,
        filterId,
        filterName,
    ) { sources, automationId, automationName ->
        val actionsByExecution = sources.actions.groupBy { it.executionId }
        val rows = sources.records.map { record ->
                val executionActions = record.executionId?.let(actionsByExecution::get).orEmpty()
                record.toLogRow(executionActions)
            }
        ExecutionLogState(
            entries = rows,
            filterAutomationName = automationName.takeIf { automationId != null },
            loading = false,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ExecutionLogState(emptyList(), filterName.value, loading = true),
    )

    fun onClearFilter() {
        savedStateHandle[FILTER_ID_KEY] = null
        savedStateHandle[FILTER_NAME_KEY] = null
    }

    fun setFilter(automationId: String, automationName: String) {
        savedStateHandle[FILTER_ID_KEY] = automationId
        savedStateHandle[FILTER_NAME_KEY] = automationName
    }

    fun onExpand(id: String) = Unit

    fun onSendNow(logId: String) {
        mutableMessages.tryEmit(
            "La consegna differita non è disponibile nella lane deterministica P0-B.",
        )
    }

    private companion object {
        const val FILTER_ID_KEY = "automationId"
        const val FILTER_NAME_KEY = "automationName"
        const val LOG_LIMIT = 500
    }

    private data class LogSources(
        val records: List<AuditLogRecord>,
        val actions: List<ActionResultEntity>,
    )
}
