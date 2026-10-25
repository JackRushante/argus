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
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.geofence.GeofenceTriggerRuntime
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
import dev.argus.engine.model.StateQueryFamily
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.toDraft
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
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
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.ValidationMessages
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
    private val connectivity: ConnectivityTriggerRuntime,
    private val geofence: GeofenceTriggerRuntime,
    private val audit: AuditSink,
    private val language: RenderLanguage = RenderLanguage.system(),
) : ViewModel() {
    private val routeId = requireNotNull(savedStateHandle.get<String>("id")) {
        "Dettaglio senza id"
    }
    private val mutableState = MutableStateFlow(DetailViewState())
    val state: StateFlow<DetailViewState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 4)
    val events = mutableEvents.asSharedFlow()

    /**
     * Bozza e regola che l'utente STA VEDENDO: si aggiornano solo insieme a [mutableState], dopo che
     * lo stato è stato davvero costruito. Una revisione N+1 che arriva durante una review lenta non
     * è ancora sullo schermo: arma/rifiuta/modifica devono agire sulla N mostrata (che risulterà
     * Stale), mai su una revisione che l'utente non ha visto (S3).
     */
    @Volatile
    private var displayedDraft: PendingDraft? = null
    @Volatile
    private var displayedAutomation: Automation? = null

    /** B26: vero finché arma/rifiuta/elimina è in corso (e dopo un Close, fino alla chiusura). */
    private val actionInFlight = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            combine(
                drafts.observeAll(),
                store.observeAll(),
                whitelist.observeAll(),
            ) { pending, automations, contacts ->
                val automationByRoute = automations.firstOrNull { it.id.value == routeId }
                // D5: una regola armata/disattivata resta la verità del proprio dettaglio anche con una
                // modifica in attesa (la regola continua a scattare); la bozza si apre dal suo id.
                // Solo una regola in revisione "legacy" (PENDING_APPROVAL) apre direttamente la bozza.
                val draft = pending.firstOrNull { it.id.value == routeId }
                    ?: pending.firstOrNull { snapshot ->
                        snapshot.automationId.value == routeId &&
                            automationByRoute?.status.let {
                                it != AutomationStatus.ARMED && it != AutomationStatus.DISABLED
                            }
                    }
                val automation = automationByRoute
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
                val next = when {
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
                // S3: se questo blocco viene annullato dentro draftState (nuova emissione) non si
                // arriva qui, quindi displayedDraft resta quello che lo schermo mostra davvero.
                displayedDraft = sources.draft
                displayedAutomation = sources.automation
                mutableState.value = next.withActionInProgress(actionInFlight.get())
            }
        }
    }

    /**
     * Esegue un'azione del footer in modo esclusivo (B26). Un secondo tocco mentre la prima è in corso
     * è ignorato; [block] restituisce true se ha emesso un Close: in quel caso il flag resta vero
     * (la schermata si sta chiudendo e la regola/bozza non esiste più come prima).
     */
    private fun runExclusive(block: suspend () -> Boolean) {
        if (!actionInFlight.compareAndSet(false, true)) return
        publishActionInProgress()
        viewModelScope.launch {
            var closing = false
            try {
                closing = block()
            } finally {
                if (!closing) {
                    actionInFlight.set(false)
                    publishActionInProgress()
                }
            }
        }
    }

    private fun publishActionInProgress() {
        mutableState.update { it.withActionInProgress(actionInFlight.get()) }
    }

    fun onArm() {
        val snapshot = displayedDraft ?: return
        runExclusive {
            try {
                when (
                    val result = approvals.arm(snapshot.id, snapshot.revision, snapshot.fingerprint)
                ) {
                    is FlowArmResult.Armed -> closeWith(
                        language.pick(
                            "Rule armed: ${result.automation.name}",
                            "Regola armata: ${result.automation.name}",
                        ),
                    )
                    FlowArmResult.Missing -> closeWith(
                        language.pick("Draft no longer available.", "Bozza non più disponibile."),
                    )
                    is FlowArmResult.Stale -> stayWith(
                        language.pick(
                            "The draft changed; review the new revision.",
                            "La bozza è cambiata: controlla la nuova revisione.",
                        ),
                    )
                    is FlowArmResult.ValidationFailed -> stayWith(
                        result.issues.firstOrNull { it.severity == Severity.ERROR }?.let { issue ->
                            val reason = ValidationMessages.text(issue, language)
                            language.pick(
                                "The rule failed local validation: $reason",
                                "La regola non supera i controlli di sicurezza: $reason",
                            )
                        } ?: language.pick(
                            "The rule failed local validation.",
                            "La regola non supera i controlli di sicurezza.",
                        ),
                    )
                    FlowArmResult.PolicyUnavailable -> stayWith(
                        language.pick(
                            "Device policy is unavailable.",
                            "Policy del dispositivo non disponibile.",
                        ),
                    )
                    FlowArmResult.LocationUnavailable -> stayWith(
                        language.pick(
                            "Current location is unavailable.",
                            "Posizione attuale non disponibile.",
                        ),
                    )
                    FlowArmResult.IntegrityFailure -> stayWith(
                        language.pick("Invalid draft integrity.", "Integrità della bozza non valida."),
                    )
                    FlowArmResult.AutomationConflict -> stayWith(
                        language.pick(
                            "The rule changed during approval.",
                            "La regola è cambiata durante l'approvazione.",
                        ),
                    )
                    is FlowArmResult.RegistrationFailed -> closeWith(
                        language.pick(
                            "Rule saved but disabled: scheduling failed.",
                            "Regola salvata ma disattivata: pianificazione non riuscita.",
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                stayWith(language.pick("Unable to arm the rule.", "Impossibile armare la regola."))
            }
        }
    }

    fun onReject() {
        val snapshot = displayedDraft ?: return
        runExclusive {
            try {
                when (drafts.delete(snapshot.id, snapshot.revision)) {
                    DraftDeleteResult.Deleted -> closeWith(
                        language.pick("Draft rejected.", "Bozza rifiutata."),
                    )
                    DraftDeleteResult.Missing -> closeWith(
                        language.pick("Draft no longer available.", "Bozza non più disponibile."),
                    )
                    is DraftDeleteResult.Stale -> stayWith(
                        language.pick(
                            "The draft changed; reopen it before rejecting it.",
                            "La bozza è cambiata: riaprila prima di rifiutarla.",
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                stayWith(language.pick("Unable to reject the draft.", "Impossibile rifiutare la bozza."))
            }
        }
    }

    /** Emette l'unico Close dell'azione; true = la schermata si sta chiudendo. */
    private suspend fun closeWith(message: String): Boolean {
        mutableEvents.emit(DetailEvent.Close(message))
        return true
    }

    /** Messaggio senza chiudere: l'utente può correggere e riprovare; false = footer di nuovo attivo. */
    private suspend fun stayWith(text: String): Boolean {
        message(text)
        return false
    }

    fun onSetEnabled(enabled: Boolean) {
        val automation = displayedAutomation ?: return
        viewModelScope.launch {
            try {
                when (enablement.setEnabled(automation.id, enabled)) {
                    EnablementResult.Updated -> Unit
                    EnablementResult.ReviewRequired ->
                        message(
                            language.pick(
                                "The rule must be reviewed before it can be re-enabled.",
                                "La regola deve essere rivista prima di essere riattivata.",
                            ),
                        )
                    EnablementResult.SchedulingFailed ->
                        message(
                            language.pick(
                                "Scheduling failed: the rule remains disabled.",
                                "Pianificazione non riuscita: la regola è rimasta disattivata.",
                            ),
                        )
                    EnablementResult.DisableCleanupDeferred ->
                        message(
                            language.pick(
                                "Rule disabled; runtime reconciliation deferred.",
                                "Regola disattivata; riconciliazione rinviata.",
                            ),
                        )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(language.pick("Unable to update the rule.", "Impossibile aggiornare la regola."))
            }
        }
    }

    fun onDelete() {
        val automation = displayedAutomation ?: return
        runExclusive {
            try {
                store.delete(automation.id)
                // Lifecycle (task #31-B): la cancellazione è sempre un'azione manuale dell'utente;
                // il logging non deve mai cambiare l'esito del delete (pattern non-fatale standard).
                try {
                    audit.record(
                        AuditEvent(
                            automation.id,
                            AuditKind.RULE_DELETED,
                            System.currentTimeMillis(),
                            detail = "user",
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Regola già rimossa: il fallimento del log non riapre l'operazione.
                }
                // Tenta tutte le pulizie: un AlarmManager guasto non deve impedire lo stop
                // della sentinella né l'unregister del PendingIntent geofence.
                var reconciled = true
                if (cancellationSafeOrNull {
                    scheduler.reconcile(ReconcileReason.CAPABILITY_CHANGED)
                } == null) reconciled = false
                if (cancellationSafeOrNull { connectivity.reconcile() } == null) reconciled = false
                if (cancellationSafeOrNull {
                    geofence.reconcile(recreateOsRegistrations = false)
                } == null) reconciled = false
                closeWith(
                    if (reconciled) {
                        language.pick("Rule deleted.", "Regola eliminata.")
                    } else {
                        language.pick(
                            "Rule deleted; runtime cleanup deferred.",
                            "Regola eliminata; pulizia runtime rinviata.",
                        )
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                stayWith(language.pick("Unable to delete the rule.", "Impossibile eliminare la regola."))
            }
        }
    }

    fun onRunNow() {
        mutableEvents.tryEmit(
            DetailEvent.Message(
                language.pick(
                    "Run now requires live confirmation and is disabled in P0-B.",
                    "Esegui ora richiede conferma live ed è disabilitato in P0-B.",
                ),
            ),
        )
    }

    fun onAskEdit() {
        val automation = displayedAutomation
        val draft = displayedDraft
        val name = draft?.draft?.name ?: automation?.name ?: return
        val recreating = draft == null && automation?.status == AutomationStatus.NEEDS_REVIEW
        mutableEvents.tryEmit(
            DetailEvent.OpenChat(
                prompt = if (recreating) {
                    language.pick("Recreate as a new rule “$name”: ", "Ricrea come nuova regola «$name»: ")
                } else {
                    language.pick("Edit the rule “$name”: ", "Modifica la regola «$name»: ")
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
        val rule = RuleRenderMapper.mapDraft(snapshot.draft, conversationLabels, language)
        val warnings = if (review == null) {
            listOf(
                UiWarning(
                    Severity.ERROR,
                    "draft_review_unavailable",
                    ValidationMessages.text("draft_review_unavailable", language),
                ),
            )
        } else {
            reviewWarnings(review, language)
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
                    ?.let {
                        language.pick(
                            "Location: current position at activation time",
                            "Posizione: quella attuale al momento dell'attivazione",
                        )
                    },
                runNowBlockedReason = runNowBlockedReason(),
                verifiedStateReaders = review?.verifiedStateQueries.orEmpty().map { evidence ->
                    readerProbeLabel(evidence.family, evidence.valueType)
                },
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
        val rule = RuleRenderMapper.map(automation, conversationLabels, language)
        val warnings = buildList {
            rule.privacyNote?.let {
                add(UiWarning(Severity.WARNING, "privacy_generative", it))
            }
            if (automation.status == AutomationStatus.NEEDS_REVIEW) {
                add(
                    UiWarning(
                        Severity.ERROR,
                        "schema_or_integrity_review",
                        ValidationMessages.text("schema_or_integrity_review", language),
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
                runNowBlockedReason = runNowBlockedReason(),
            ),
            automationId = automation.id.value,
            loading = false,
        )
    }

    private fun runNowBlockedReason(): String = language.pick(
        "Manual execution is not available at this stage.",
        "Esecuzione manuale non disponibile in questa fase.",
    )

    private fun recentRuns(
        automationId: String,
        records: List<AuditLogRecord>,
        actionsByExecution: Map<String, List<ActionResultEntity>>,
    ) = records
        .filter { it.automationId == automationId }
        .take(RECENT_RUN_LIMIT)
        .map { record ->
            val actions = record.executionId?.let(actionsByExecution::get).orEmpty()
            record.toLogRow(actions, language)
        }
        .toList()

    private fun generativeEstimate(generative: Boolean): String? = if (generative) {
        language.pick(
            "Available since P1 · minimum cooldown 60 s",
            "Disponibile da P1 · cooldown minimo 60 s",
        )
    } else {
        null
    }

    private fun readerProbeLabel(family: StateQueryFamily, type: StateValueType): String {
        val reader = when (family) {
            StateQueryFamily.BUILTIN -> language.pick("built-in state", "stato integrato")
            StateQueryFamily.SETTING -> language.pick("Android setting", "impostazione Android")
            StateQueryFamily.SYSTEM_PROPERTY -> language.pick("system property", "proprietà di sistema")
            StateQueryFamily.SYSFS -> "sysfs"
            StateQueryFamily.DUMPSYS_FIELD -> "dumpsys"
        }
        val valueType = when (type) {
            StateValueType.TEXT -> language.pick("text", "testo")
            StateValueType.NUMBER -> language.pick("number", "numero")
            StateValueType.BOOLEAN -> language.pick("boolean", "booleano")
        }
        return "$reader · $valueType · probe OK"
    }

    private suspend fun message(text: String) {
        mutableEvents.emit(DetailEvent.Message(text))
    }

    private fun DetailViewState.withActionInProgress(value: Boolean): DetailViewState =
        if (detail == null || detail.actionInProgress == value) {
            this
        } else {
            copy(detail = detail.copy(actionInProgress = value))
        }

    private companion object {
        const val RECENT_RUN_LIMIT = 5
        const val RECENT_LOG_SCAN_LIMIT = 200
    }
}
