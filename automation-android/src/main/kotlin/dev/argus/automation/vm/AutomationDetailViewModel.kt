package dev.argus.automation.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.AutomationEnablementCoordinator
import dev.argus.automation.BlockCause
import dev.argus.automation.EnablementResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.di.ApplicationScope
import dev.argus.automation.geofence.GeofenceStateStore
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import dev.argus.automation.geofence.PersistedGeofenceRegistration
import dev.argus.automation.sensor.SensorTriggerRuntime
import dev.argus.data.ArgusDatabase
import dev.argus.data.dao.AuditLogRecord
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.data.entities.ActionResultEntity
import dev.argus.data.entities.ScheduledTimeAlarmEntity
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
import dev.argus.ui.model.RuleDiagnosisUi
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.UiWarning
import dev.argus.ui.presentation.RuleRenderMapper
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.ValidationMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import kotlinx.coroutines.withContext
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
    val language: RenderLanguage,
    val diagnosis: DiagnosisSnapshot? = null,
)

/**
 * Ciò che «Perché non è scattata?» ha letto dal dispositivo, una volta, quando l'utente ha aperto la
 * sezione. Si tengono solo gli input: il testo si costruisce nel flow dello stato insieme alla
 * lingua, così segue il cambio di lingua. Vale per la revisione ([fingerprint]) che ha letto.
 */
private data class DiagnosisSnapshot(
    val automationId: AutomationId,
    val fingerprint: ApprovalFingerprint?,
    val blockCauses: Map<String, BlockCause>,
    val alarm: ScheduledTimeAlarmEntity?,
    val geofence: PersistedGeofenceRegistration?,
    val checkedAtMillis: Long,
)

private data class DetailTarget(
    val draft: PendingDraft?,
    val automation: Automation?,
    val conversationLabels: Map<String, String>,
    val language: RenderLanguage,
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
    private val sensor: SensorTriggerRuntime,
    private val probe: AndroidCapabilityProbe,
    private val geofenceState: GeofenceStateStore,
    private val audit: AuditSink,
    private val languageSource: RenderLanguageSource,
    /**
     * B31: scope dei lavori che devono finire anche se l'utente esce dalla schermata (l'arm lo
     * usa); nel ViewModel resta solo l'attesa del risultato.
     */
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    private val routeId = requireNotNull(savedStateHandle.get<String>("id")) {
        "Dettaglio senza id"
    }
    private val mutableState = MutableStateFlow(DetailViewState())
    val state: StateFlow<DetailViewState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 4)
    val events = mutableEvents.asSharedFlow()

    /**
     * Lingua al momento (messaggi ed errori prodotti dalle azioni). Lo stato dello schermo non la
     * legge da qui: la riceve dal flow ([RenderLanguageSource.language]) così si rigenera al
     * cambio di lingua del telefono.
     */
    private val language: RenderLanguage get() = languageSource.current

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

    /** Ultima diagnosi chiesta («Perché non è scattata?»); null finché l'utente non la apre. */
    private val diagnosisInputs = MutableStateFlow<DiagnosisSnapshot?>(null)
    private var diagnosisJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                drafts.observeAll(),
                store.observeAll(),
                whitelist.observeAll(),
                languageSource.language,
            ) { pending, automations, contacts, renderLanguage ->
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
                DetailTarget(
                    draft,
                    automation,
                    contacts.associate { it.id to it.displayName },
                    renderLanguage,
                )
            }.flatMapLatest { target ->
                val automationId = target.automationId
                    ?: return@flatMapLatest flowOf(
                        DetailSources(
                            target.draft,
                            target.automation,
                            emptyList(),
                            emptyMap(),
                            target.conversationLabels,
                            target.language,
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
                    diagnosisInputs,
                ) { log, actions, diagnosis ->
                    DetailSources(
                        target.draft,
                        target.automation,
                        log,
                        actions.groupBy { it.executionId },
                        target.conversationLabels,
                        target.language,
                        diagnosis,
                    )
                }
            }.collectLatest { sources ->
                val next = when {
                    sources.draft != null -> draftState(
                        sources.draft,
                        sources.log,
                        sources.actionsByExecution,
                        sources.conversationLabels,
                        sources.language,
                    )
                    sources.automation != null -> automationState(
                        sources.automation,
                        sources.log,
                        sources.actionsByExecution,
                        sources.conversationLabels,
                        sources.language,
                        sources.diagnosis,
                    )
                    // B31: mentre arma/rifiuta/elimina è in corso la bozza o la regola scompare dalle
                    // tabelle PRIMA che l'azione chiuda la schermata. Quel vuoto non è «regola non
                    // disponibile»: si tiene l'ultimo stato valido (col footer disabilitato) fino al
                    // Close. Senza un'azione in corso il vuoto è reale (bozza sostituita o rimossa).
                    else -> if (actionInFlight.get() && mutableState.value.detail != null) {
                        return@collectLatest
                    } else {
                        DetailViewState(loading = false, missing = true)
                    }
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
                // B31: l'arm (commit + registrazione degli allarmi/geofence) non deve essere
                // interrotto dall'uscita dalla schermata, che cancella viewModelScope: girerebbe a
                // metà e la regola resterebbe armata ma non registrata. Lo esegue lo scope
                // applicativo; qui si aspetta soltanto il risultato.
                val outcome = applicationScope.async {
                    approvals.arm(snapshot.id, snapshot.revision, snapshot.fingerprint)
                }.await()
                when (val result = outcome) {
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
                    // B31: l'orario e' passato, non e' un guasto: dice cosa fare.
                    EnablementResult.OneShotInPast -> message(oneShotInPastText(language))
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
                // I sensori hanno la loro registrazione fisica: senza questo giro una regola
                // sensore eliminata terrebbe il sensore registrato fino al prossimo bootstrap.
                if (cancellationSafeOrNull { sensor.reconcile() } == null) reconciled = false
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

    /**
     * «Perché non è scattata?»: legge una volta capability, sveglia e geofence della regola
     * mostrata. Una nuova richiesta (Ricontrolla) sostituisce quella in corso. Mai per una bozza.
     */
    fun onDiagnose() {
        val automation = displayedAutomation ?: return
        if (displayedDraft != null) return
        diagnosisJob?.cancel()
        diagnosisJob = viewModelScope.launch {
            try {
                val (causes, registration) = withContext(Dispatchers.IO) {
                    probe.blockCauses(automation.requiredCapabilities) to
                        geofenceState.get(automation.id)
                }
                val alarm = database.scheduledTimeAlarmDao().get(automation.id.value)
                diagnosisInputs.value = DiagnosisSnapshot(
                    automationId = automation.id,
                    fingerprint = automation.approvalFingerprint,
                    blockCauses = causes,
                    alarm = alarm,
                    geofence = registration,
                    checkedAtMillis = System.currentTimeMillis(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(language.pick("Unable to run the check.", "Impossibile eseguire il controllo."))
            }
        }
    }

    fun onRunNow() {
        mutableEvents.tryEmit(DetailEvent.Message(runNowUnavailable(language)))
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
        language: RenderLanguage,
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
        val recent = recentRuns(snapshot.automationId.value, records, actionsByExecution, language)
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
                estimatedLlmCallsPerDay = generativeEstimate(
                    snapshot.draft.actions.any { it.tier == ActionTier.GENERATIVE },
                    language,
                ),
                recentRuns = recent,
                geofencePreviewLabel = (snapshot.draft.trigger as? Trigger.Geofence)
                    ?.takeIf { it.resolveCurrentLocation }
                    ?.let {
                        language.pick(
                            "Location: current position at activation time",
                            "Posizione: quella attuale al momento dell'attivazione",
                        )
                    },
                runNowBlockedReason = runNowBlockedReason(language),
                verifiedStateReaders = review?.verifiedStateQueries.orEmpty().map { evidence ->
                    readerProbeLabel(evidence.family, evidence.valueType, language)
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
        language: RenderLanguage,
        snapshot: DiagnosisSnapshot?,
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
                estimatedLlmCallsPerDay = generativeEstimate(rule.isGenerative, language),
                recentRuns = recentRuns(automation.id.value, records, actionsByExecution, language),
                geofencePreviewLabel = null,
                runNowBlockedReason = runNowBlockedReason(language),
                diagnosis = diagnosisOf(automation, records, snapshot, language),
                diagnosisAvailable = true,
            ),
            automationId = automation.id.value,
            loading = false,
        )
    }

    /**
     * La diagnosi si ricostruisce a ogni emissione dagli input letti una volta (lingua e log
     * compresi). Se la regola ha cambiato revisione da quando è stata chiesta, quegli input non
     * la riguardano più: si azzera e l'utente può ricontrollare.
     */
    private fun diagnosisOf(
        automation: Automation,
        records: List<AuditLogRecord>,
        snapshot: DiagnosisSnapshot?,
        language: RenderLanguage,
    ): RuleDiagnosisUi? {
        if (snapshot == null ||
            snapshot.automationId != automation.id ||
            snapshot.fingerprint != automation.approvalFingerprint
        ) {
            return null
        }
        return buildRuleDiagnosis(
            DiagnosisInputs(automation, snapshot.blockCauses, snapshot.alarm, snapshot.geofence, records),
            snapshot.checkedAtMillis,
            language,
        )
    }

    private fun runNowBlockedReason(language: RenderLanguage): String = runNowUnavailable(language)

    private fun runNowUnavailable(language: RenderLanguage): String = language.pick(
        "Run now is not available yet.",
        "Esegui ora non è ancora disponibile.",
    )

    private fun recentRuns(
        automationId: String,
        records: List<AuditLogRecord>,
        actionsByExecution: Map<String, List<ActionResultEntity>>,
        language: RenderLanguage,
    ) = records
        .filter { it.automationId == automationId }
        .take(RECENT_RUN_LIMIT)
        .map { record ->
            val actions = record.executionId?.let(actionsByExecution::get).orEmpty()
            val row = record.toLogRow(actions, language)
            // B31: nel Dettaglio il motivo dice cosa fare, non solo che è fallito.
            if (record.kind == AuditKind.ENABLE_FAILED && record.detail == ONE_SHOT_IN_PAST) {
                row.copy(summary = oneShotInPastText(language))
            } else {
                row
            }
        }
        .toList()

    private fun generativeEstimate(generative: Boolean, language: RenderLanguage): String? = if (generative) {
        language.pick(
            "Minimum cooldown 60 s between two AI replies",
            "Cooldown minimo 60 s tra due risposte AI",
        )
    } else {
        null
    }

    private fun readerProbeLabel(
        family: StateQueryFamily,
        type: StateValueType,
        language: RenderLanguage,
    ): String {
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
