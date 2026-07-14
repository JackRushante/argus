package dev.argus.automation.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.AutomationEnablementCoordinator
import dev.argus.automation.EnablementResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.data.ArgusDatabase
import dev.argus.data.dao.AuditLogRecord
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.safety.DraftDeleteResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.PendingDraft
import dev.argus.engine.safety.Severity
import dev.argus.ui.model.AutomationDetailState
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.UiWarning
import dev.argus.ui.presentation.RuleRenderMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailViewState(
    val detail: AutomationDetailState? = null,
    val automationId: String? = null,
    val loading: Boolean = true,
    val missing: Boolean = false,
)

sealed interface DetailEvent {
    data class Close(val message: String) : DetailEvent
    data class Message(val text: String) : DetailEvent
    data class OpenChat(
        val prompt: String,
        val automationId: String?,
        val automationFingerprint: ApprovalFingerprint?,
        val draftId: String?,
        val draftRevision: Long?,
        val baseDraft: AutomationDraft?,
    ) : DetailEvent
}

private data class DetailSources(
    val draft: PendingDraft?,
    val automation: Automation?,
    val log: List<AuditLogRecord>,
    val actionsByExecution: Map<String, List<ActionResultEntity>>,
    val conversationLabels: Map<String, String>,
)

private data class DetailTarget(
    val draft: PendingDraft?,
    val automation: Automation?,
    val conversationLabels: Map<String, String>,
) {
    val automationId: String? = (automation?.id ?: draft?.automationId)?.value
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: AutomationStore,
    private val drafts: DraftRepository,
    private val approvals: ApprovalFlow,
    private val enablement: AutomationEnablementCoordinator,
    private val scheduler: TimeAlarmRuntime,
    private val database: ArgusDatabase,
    private val whitelist: ContactWhitelistStore,
) : ViewModel() {
    private val routeId = requireNotNull(savedStateHandle.get<String>("id")) {
        "Dettaglio senza id"
    }
    private val mutableState = MutableStateFlow(DetailViewState())
    val state: StateFlow<DetailViewState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 4)
    val events = mutableEvents.asSharedFlow()

    @Volatile
    private var currentDraft: PendingDraft? = null
    @Volatile
    private var currentAutomation: Automation? = null

    init {
        viewModelScope.launch {
            combine(
                drafts.observeAll(),
                store.observeAll(),
                whitelist.observeAll(),
            ) { pending, automations, contacts ->
                val draft = pending.firstOrNull { it.id.value == routeId }
                    ?: pending.firstOrNull { it.automationId.value == routeId }
                val automation = automations.firstOrNull { it.id.value == routeId }
                    ?: draft?.let { snapshot ->
                        automations.firstOrNull { it.id == snapshot.automationId }
                    }
                DetailTarget(draft, automation, contacts.associate { it.id to it.displayName })
            }.flatMapLatest { target ->
                val automationId = target.automationId
                    ?: return@flatMapLatest flowOf(
                        DetailSources(
                            target.draft,
                            target.automation,
                            emptyList(),
                            emptyMap(),
                            target.conversationLabels,
                        ),
                    )
                combine(
                    database.auditDao().observeLogForAutomation(
                        automationId,
                        RECENT_LOG_SCAN_LIMIT,
                    ),
                    database.executionJournalDao().observeRecentActionsForAutomation(
                        automationId,
                        RECENT_LOG_SCAN_LIMIT,
                    ),
                ) { log, actions ->
                    DetailSources(
                        target.draft,
                        target.automation,
                        log,
                        actions.groupBy { it.executionId },
                        target.conversationLabels,
                    )
                }
            }.collectLatest { sources ->
                currentDraft = sources.draft
                currentAutomation = sources.automation
                mutableState.value = when {
                    sources.draft != null -> draftState(
                        sources.draft,
                        sources.log,
                        sources.actionsByExecution,
                        sources.conversationLabels,
                    )
                    sources.automation != null -> automationState(
                        sources.automation,
                        sources.log,
                        sources.actionsByExecution,
                        sources.conversationLabels,
                    )
                    else -> DetailViewState(loading = false, missing = true)
                }
            }
        }
    }

    fun onArm() {
        val snapshot = currentDraft ?: return
        viewModelScope.launch {
            try {
                when (
                    val result = approvals.arm(snapshot.id, snapshot.revision, snapshot.fingerprint)
                ) {
                    is FlowArmResult.Armed -> mutableEvents.emit(
                        DetailEvent.Close("Regola armata: ${result.automation.name}"),
                    )
                    FlowArmResult.Missing -> mutableEvents.emit(DetailEvent.Close("Bozza non più disponibile."))
                    is FlowArmResult.Stale -> message("La bozza è cambiata: controlla la nuova revisione.")
                    is FlowArmResult.ValidationFailed -> message(
                        result.issues.firstOrNull { it.severity == Severity.ERROR }?.message
                            ?: "La regola non supera i controlli di sicurezza.",
                    )
                    FlowArmResult.PolicyUnavailable -> message("Policy del dispositivo non disponibile.")
                    FlowArmResult.LocationUnavailable -> message("Posizione attuale non disponibile.")
                    FlowArmResult.IntegrityFailure -> message("Integrità della bozza non valida.")
                    FlowArmResult.AutomationConflict -> message("La regola è cambiata durante l'approvazione.")
                    is FlowArmResult.RegistrationFailed -> mutableEvents.emit(
                        DetailEvent.Close(
                            "Regola salvata ma disattivata: pianificazione non riuscita.",
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile armare la regola.")
            }
        }
    }

    fun onReject() {
        val snapshot = currentDraft ?: return
        viewModelScope.launch {
            try {
                when (val result = drafts.delete(snapshot.id, snapshot.revision)) {
                    DraftDeleteResult.Deleted -> mutableEvents.emit(DetailEvent.Close("Bozza rifiutata."))
                    DraftDeleteResult.Missing -> mutableEvents.emit(DetailEvent.Close("Bozza non più disponibile."))
                    is DraftDeleteResult.Stale -> message("La bozza è cambiata: riaprila prima di rifiutarla.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile rifiutare la bozza.")
            }
        }
    }

    fun onSetEnabled(enabled: Boolean) {
        val automation = currentAutomation ?: return
        viewModelScope.launch {
            try {
                when (enablement.setEnabled(automation.id, enabled)) {
                    EnablementResult.Updated -> Unit
                    EnablementResult.ReviewRequired ->
                        message("La regola deve essere rivista prima di essere riattivata.")
                    EnablementResult.SchedulingFailed ->
                        message("Pianificazione non riuscita: la regola è rimasta disattivata.")
                    EnablementResult.DisableCleanupDeferred ->
                        message("Regola disattivata; riconciliazione rinviata.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile aggiornare la regola.")
            }
        }
    }

    fun onDelete() {
        val automation = currentAutomation ?: return
        viewModelScope.launch {
            try {
                store.delete(automation.id)
                val reconciled = cancellationSafeOrNull {
                    scheduler.reconcile(ReconcileReason.CAPABILITY_CHANGED)
                } != null
                mutableEvents.emit(
                    DetailEvent.Close(
                        if (reconciled) "Regola eliminata."
                        else "Regola eliminata; pulizia scheduler rinviata.",
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile eliminare la regola.")
            }
        }
    }

    fun onRunNow() {
        mutableEvents.tryEmit(
            DetailEvent.Message("Esegui ora richiede conferma live ed è disabilitato in P0-B."),
        )
    }

    fun onAskEdit() {
        val automation = currentAutomation
        val draft = currentDraft
        val name = draft?.draft?.name ?: automation?.name ?: return
        val recreating = draft == null && automation?.status == AutomationStatus.NEEDS_REVIEW
        mutableEvents.tryEmit(
            DetailEvent.OpenChat(
                prompt = if (recreating) {
                    "Ricrea come nuova regola «$name»: "
                } else {
                    "Modifica la regola «$name»: "
                },
                automationId = automation?.takeIf {
                    draft == null && it.status in setOf(
                        AutomationStatus.ARMED,
                        AutomationStatus.DISABLED,
                    )
                }?.id?.value,
                automationFingerprint = automation?.takeIf {
                    draft == null && it.status in setOf(
                        AutomationStatus.ARMED,
                        AutomationStatus.DISABLED,
                    )
                }?.approvalFingerprint,
                draftId = draft?.id?.value,
                draftRevision = draft?.revision,
                baseDraft = draft?.draft ?: automation?.takeIf { !recreating }?.toDraft(),
            ),
        )
    }

    private suspend fun draftState(
        snapshot: PendingDraft,
        records: List<AuditLogRecord>,
        actionsByExecution: Map<String, List<ActionResultEntity>>,
        conversationLabels: Map<String, String>,
    ): DetailViewState {
        val review = cancellationSafeOrNull { approvals.review(snapshot.id) }
        val rule = RuleRenderMapper.mapDraft(snapshot.draft, conversationLabels)
        val warnings = if (review == null) {
            listOf(
                UiWarning(
                    Severity.ERROR,
                    "draft_review_unavailable",
                    "Impossibile verificare questa revisione. Riapri il dettaglio e riprova.",
                ),
            )
        } else {
            reviewWarnings(review)
        }
        val canArm = review?.canArm == true && warnings.none { it.severity == Severity.ERROR }
        val recent = recentRuns(snapshot.automationId.value, records, actionsByExecution)
        return DetailViewState(
            detail = AutomationDetailState(
                id = snapshot.id.value,
                name = snapshot.draft.name,
                status = StatusBadge.PENDING_APPROVAL,
                rule = rule,
                rationale = snapshot.draft.rationale.takeIf(String::isNotBlank),
                warnings = warnings,
                canArm = canArm,
                armBlockedReason = warnings.firstOrNull { it.severity == Severity.ERROR }?.text,
                estimatedLlmCallsPerDay = generativeEstimate(snapshot.draft.actions.any {
                    it.tier == ActionTier.GENERATIVE
                }),
                recentRuns = recent,
                geofencePreviewLabel = (snapshot.draft.trigger as? Trigger.Geofence)
                    ?.takeIf { it.resolveCurrentLocation }
                    ?.let { "Posizione: quella attuale al momento dell'attivazione" },
            ),
            automationId = snapshot.automationId.value,
            loading = false,
        )
    }

    private fun automationState(
        automation: Automation,
        records: List<AuditLogRecord>,
        actionsByExecution: Map<String, List<ActionResultEntity>>,
        conversationLabels: Map<String, String>,
    ): DetailViewState {
        val rule = RuleRenderMapper.map(automation, conversationLabels)
        val warnings = buildList {
            rule.privacyNote?.let {
                add(UiWarning(Severity.WARNING, "privacy_generative", it))
            }
            if (automation.status == AutomationStatus.NEEDS_REVIEW) {
                add(
                    UiWarning(
                        Severity.ERROR,
                        "schema_or_integrity_review",
                        "La regola non è più verificabile e deve essere ricreata.",
                    ),
                )
            }
        }
        return DetailViewState(
            detail = AutomationDetailState(
                id = automation.id.value,
                name = automation.name,
                status = automation.status.toStatusBadge(),
                rule = rule,
                rationale = null,
                warnings = warnings,
                canArm = false,
                armBlockedReason = warnings.firstOrNull { it.severity == Severity.ERROR }?.text,
                estimatedLlmCallsPerDay = generativeEstimate(rule.isGenerative),
                recentRuns = recentRuns(automation.id.value, records, actionsByExecution),
                geofencePreviewLabel = null,
            ),
            automationId = automation.id.value,
            loading = false,
        )
    }

    private fun recentRuns(
        automationId: String,
        records: List<AuditLogRecord>,
        actionsByExecution: Map<String, List<ActionResultEntity>>,
    ) = records
        .filter { it.automationId == automationId }
        .take(RECENT_RUN_LIMIT)
        .map { record ->
            val actions = record.executionId?.let(actionsByExecution::get).orEmpty()
            record.toLogRow(actions)
        }
        .toList()

    private fun generativeEstimate(generative: Boolean): String? = if (generative) {
        "Disponibile da P1 · cooldown minimo 60 s"
    } else {
        null
    }

    private suspend fun message(text: String) {
        mutableEvents.emit(DetailEvent.Message(text))
    }

    private fun Automation.toDraft() = AutomationDraft(
        name = name,
        trigger = trigger,
        actions = actions,
        conditions = conditions,
        cooldownMs = cooldownMs,
    )

    private companion object {
        const val RECENT_RUN_LIMIT = 5
        const val RECENT_LOG_SCAN_LIMIT = 200
    }
}
