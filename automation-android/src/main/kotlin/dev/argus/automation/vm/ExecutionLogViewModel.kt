package dev.argus.automation.vm

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.argus.automation.ClipboardCopier
import dev.argus.automation.DeferredReplyManager
import dev.argus.automation.DeferredReplyResolution
import dev.argus.automation.DeliverableReply
import dev.argus.automation.LogRetentionStore
import dev.argus.automation.RenderLanguageSource
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomJournalMaintenance
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.runtime.ActionResult
import dev.argus.ui.model.ExecutionLogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionLogViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val database: ArgusDatabase,
    private val deferredReplies: DeferredReplyManager,
    private val clipboard: ClipboardCopier,
    private val languageSource: RenderLanguageSource,
    private val retention: LogRetentionStore,
    private val maintenance: RoomJournalMaintenance,
) : ViewModel() {
    private val audit = database.auditDao()
    private val journal = database.executionJournalDao()
    private val filterId = savedStateHandle.getStateFlow<String?>(FILTER_ID_KEY, null)
    private val filterName = savedStateHandle.getStateFlow<String?>(FILTER_NAME_KEY, null)
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages = mutableMessages.asSharedFlow()

    /** Testo pronto per il foglio di condivisione: lo apre la destinazione (UI), non il ViewModel. */
    private val mutableExports = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val exports = mutableExports.asSharedFlow()

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
        // La lingua e' parte del flow: al cambio di lingua del telefono le righe si rigenerano.
        languageSource.language,
        retention.days,
    ) { sources, automationId, automationName, language, retentionDays ->
        executionIdsByLogId = sources.records
            .mapNotNull { record -> record.executionId?.let { record.id.toString() to it } }
            .toMap()
        val actionsByExecution = sources.actions.groupBy { it.executionId }
        val rows = sources.records.map { record ->
                val executionActions = record.executionId?.let(actionsByExecution::get).orEmpty()
                record.toLogRow(executionActions, language)
            }
        ExecutionLogState(
            entries = rows,
            filterAutomationName = automationName.takeIf { automationId != null },
            loading = false,
            retentionDays = retentionDays,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ExecutionLogState(
            emptyList(),
            filterName.value,
            loading = true,
            retentionDays = retention.currentDays(),
        ),
    )

    fun onClearFilter() {
        savedStateHandle[FILTER_ID_KEY] = null
        savedStateHandle[FILTER_NAME_KEY] = null
    }

    fun setFilter(automationId: String, automationName: String) {
        savedStateHandle[FILTER_ID_KEY] = automationId
        savedStateHandle[FILTER_NAME_KEY] = automationName
    }

    /**
     * Allinea il filtro agli argomenti della destinazione. Si filtra solo quando si arriva dal
     * Dettaglio di una regola; riaprire il Log da altrove (tab, stessa destinazione riusata) porta
     * argomenti vuoti e quindi il Log completo. Gli stessi argomenti non si riapplicano due volte,
     * cosi' un filtro tolto con la "x" non ricompare alla rotazione dello schermo.
     */
    fun onRouteArguments(automationId: String?, automationName: String?) {
        val key = "${automationId.orEmpty()}|${automationName.orEmpty()}"
        if (savedStateHandle.get<String>(ROUTE_KEY) == key) return
        savedStateHandle[ROUTE_KEY] = key
        savedStateHandle[FILTER_ID_KEY] = automationId
        savedStateHandle[FILTER_NAME_KEY] = automationName
    }

    fun onExpand(id: String) = Unit

    /** Conservazione 7, 30 o 90 giorni: la applica subito con una passata di manutenzione. */
    fun onSetRetention(days: Int) {
        if (!retention.setDays(days)) return
        viewModelScope.launch {
            try {
                // Qui possono esserci dispatch in corso: la conservazione si applica subito, senza
                // marcare come interrotto un programma vivo in un'attesa lunga.
                maintenance.run(System.currentTimeMillis(), interruptStale = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Il valore e' salvato: la manutenzione periodica lo applichera' comunque.
            }
        }
    }

    /** La conferma sta nella schermata: arriva qui solo dopo «Svuota». Non si puo' annullare. */
    fun onClearLog() {
        viewModelScope.launch {
            val language = languageSource.current
            try {
                maintenance.clearLog()
                message(language.pick("Log cleared.", "Log svuotato."))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(language.pick("Unable to clear the log.", "Impossibile svuotare il log."))
            }
        }
    }

    /** Esporta le ultime [LogExport.MAX_ENTRIES] voci come testo semplice, senza dati personali. */
    fun onExport() {
        viewModelScope.launch {
            val language = languageSource.current
            try {
                val records = audit.observeLog(LogExport.MAX_ENTRIES).first()
                val actions = journal.observeRecentActions(LogExport.MAX_ENTRIES).first()
                val text = LogExport.build(exportHeader(), records, actions, ZoneId.systemDefault())
                mutableExports.emit(text)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(language.pick("Unable to export the log.", "Impossibile esportare il log."))
            }
        }
    }

    private fun exportHeader(): LogExportHeader {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return LogExportHeader(
            appVersion = version ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
        )
    }

    fun onSendNow(logId: String) {
        viewModelScope.launch {
            val executionId = executionIdsByLogId[logId]
            if (executionId == null) {
                message(
                    languageSource.current.pick(
                        "No deferred reply is associated with this entry.",
                        "Nessuna risposta differita per questa voce.",
                    ),
                )
                return@launch
            }
            when (val resolution = deferredReplies.resolve(executionId)) {
                is DeferredReplyResolution.Ready -> deliver(resolution.reply)
                DeferredReplyResolution.Unavailable -> message(unavailableMessage())
                DeferredReplyResolution.Failed -> message(unableToRetrieveMessage())
            }
        }
    }

    /**
     * «Segna come inviata»: unico punto che consuma la reply differita. Il copia-e-apri non segna
     * niente in anticipo, perché Argus non può sapere se il messaggio è davvero partito.
     */
    fun onMarkSent(logId: String) {
        viewModelScope.launch {
            val executionId = executionIdsByLogId[logId]
            if (executionId == null) {
                message(
                    languageSource.current.pick(
                        "No deferred reply is associated with this entry.",
                        "Nessuna risposta differita per questa voce.",
                    ),
                )
                return@launch
            }
            when (val resolution = deferredReplies.resolve(executionId)) {
                is DeferredReplyResolution.Ready ->
                    if (deferredReplies.markDelivered(resolution.reply)) {
                        message(languageSource.current.pick("Marked as sent.", "Segnata come inviata."))
                    } else {
                        message(unavailableMessage())
                    }
                DeferredReplyResolution.Unavailable -> message(unavailableMessage())
                DeferredReplyResolution.Failed -> message(unableToRetrieveMessage())
            }
        }
    }

    /**
     * E13: nessun invio automatico dopo che il canale originario è scaduto. Il testo viene
     * copiato negli appunti con [ClipboardCopier] (marcati sensibili, svuotati dopo il TTL solo se
     * ancora nostri) e WhatsApp viene soltanto aperto: incollare e premere invio resta un gesto
     * esplicito dell'utente. La reply NON viene consumata qui: finché non scade si può ricopiare;
     * la consuma «Segna come inviata».
     */
    private suspend fun deliver(reply: DeliverableReply) {
        val copied = try {
            clipboard.copyLiteral(reply.text) == ActionResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!copied) {
            message(
                languageSource.current.pick(
                    "Unable to copy the reply to the clipboard.",
                    "Impossibile copiare la risposta negli appunti.",
                ),
            )
            return
        }
        val opened = try {
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(reply.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        val language = languageSource.current
        message(
            if (opened) {
                language.pick(
                    "Reply copied: paste it into the chat and tap send, then mark it as sent.",
                    "Risposta copiata: incollala nella chat, premi invia e poi segnala come inviata.",
                )
            } else {
                language.pick(
                    "Reply copied to the clipboard.",
                    "Risposta copiata negli appunti.",
                )
            },
        )
    }

    private fun unavailableMessage(): String = languageSource.current.pick(
        "The reply is no longer available: it expired or was already delivered.",
        "La risposta non è più disponibile: scaduta o già consegnata.",
    )

    private fun unableToRetrieveMessage(): String = languageSource.current.pick(
        "Unable to retrieve the deferred reply.",
        "Impossibile recuperare la risposta differita.",
    )

    private suspend fun message(text: String) {
        mutableMessages.emit(text)
    }

    private companion object {
        const val FILTER_ID_KEY = "automationId"
        const val FILTER_NAME_KEY = "automationName"
        const val ROUTE_KEY = "routeFilterKey"
        const val LOG_LIMIT = 500
    }

    private data class LogSources(
        val records: List<AuditLogRecord>,
        val actions: List<ActionResultEntity>,
    )
}
