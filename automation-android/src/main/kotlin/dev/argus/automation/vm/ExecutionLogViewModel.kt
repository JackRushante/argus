package dev.argus.automation.vm

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.argus.automation.DeferredReplyManager
import dev.argus.automation.DeferredReplyResolution
import dev.argus.automation.DeliverableReply
import dev.argus.data.ArgusDatabase
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.ui.model.ExecutionLogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionLogViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    database: ArgusDatabase,
    private val deferredReplies: DeferredReplyManager,
) : ViewModel() {
    private val audit = database.auditDao()
    private val journal = database.executionJournalDao()
    private val filterId = savedStateHandle.getStateFlow<String?>(FILTER_ID_KEY, null)
    private val filterName = savedStateHandle.getStateFlow<String?>(FILTER_NAME_KEY, null)
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages = mutableMessages.asSharedFlow()

    /** Correlazione riga audit → esecuzione per la CTA E13; mai esposta alla UI. */
    @Volatile
    private var executionIdsByLogId: Map<String, String> = emptyMap()

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
        executionIdsByLogId = sources.records
            .mapNotNull { record -> record.executionId?.let { record.id.toString() to it } }
            .toMap()
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
        viewModelScope.launch {
            val executionId = executionIdsByLogId[logId]
            if (executionId == null) {
                message("Nessuna risposta differita per questa voce.")
                return@launch
            }
            when (val resolution = deferredReplies.resolve(executionId)) {
                is DeferredReplyResolution.Ready -> deliver(resolution.reply)
                DeferredReplyResolution.Unavailable ->
                    message("La risposta non è più disponibile: scaduta o già consegnata.")
                DeferredReplyResolution.Failed ->
                    message("Impossibile recuperare la risposta differita.")
            }
        }
    }

    /**
     * E13: nessun invio automatico dopo che il canale originario è scaduto. Il testo viene
     * copiato negli appunti (marcati sensibili) e WhatsApp viene soltanto aperto: incollare
     * e premere invio resta un gesto esplicito dell'utente.
     */
    private suspend fun deliver(reply: DeliverableReply) {
        val copied = try {
            val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Risposta Argus", reply.text).apply {
                    description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                },
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!copied) {
            message("Impossibile copiare la risposta negli appunti.")
            return
        }
        deferredReplies.markDelivered(reply)
        val opened = try {
            context.packageManager.getLaunchIntentForPackage(reply.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.also(context::startActivity) != null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        message(
            if (opened) {
                "Risposta copiata: incollala nella chat e premi invia."
            } else {
                "Risposta copiata negli appunti."
            },
        )
    }

    private suspend fun message(text: String) {
        mutableMessages.emit(text)
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
