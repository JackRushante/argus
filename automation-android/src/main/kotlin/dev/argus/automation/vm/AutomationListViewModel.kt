package dev.argus.automation.vm

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.ApprovalFlowReview
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.AutomationEnablementCoordinator
import dev.argus.automation.EnablementResult
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.manual.AutomationPauseStore
import dev.argus.data.ArgusDatabase
import dev.argus.data.dao.AutomationUiMetadata
import dev.argus.data.entities.ScheduledTimeAlarmEntity
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.PendingDraft
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.AutomationListState
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.EngineBanner
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class ListInputs(
    val automations: List<Automation>,
    val conversationLabels: Map<String, String>,
    val language: RenderLanguage,
    /** Sveglie persistite per regola: dai ritardi relativi («tra 2 ore») il prossimo scatto si legge da qui. */
    val alarms: Map<String, ScheduledTimeAlarmEntity>,
)

/** Ciò che la review di una bozza legge oltre alla bozza stessa: capacità del dispositivo e regole attive. */
private data class ReviewInputs(
    val shizuku: ShizukuGatewayStatus,
    val activeRules: List<Automation>,
)

@HiltViewModel
class AutomationListViewModel @Inject constructor(
    private val store: AutomationStore,
    drafts: DraftRepository,
    private val approvals: ApprovalFlow,
    private val enablement: AutomationEnablementCoordinator,
    shizuku: ShizukuGateway,
    private val database: ArgusDatabase,
    whitelist: ContactWhitelistStore,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val languageSource: RenderLanguageSource,
    /** F21: "Pausa automazioni". Il banner della lista lo mostra e "Riprendi" lo spegne. */
    private val pause: AutomationPauseStore,
) : ViewModel() {
    private val filter = savedStateHandle.getStateFlow(FILTER_KEY, StatusFilter.ALL)
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = mutableMessages.asSharedFlow()

    /** Lingua al momento (messaggi); le righe la ricevono dal flow e si rigenerano al cambio lingua. */
    private val language: RenderLanguage get() = languageSource.current

    val state: StateFlow<AutomationListState> = flow {
        // B32: una cache per raccolta del flow. Le letture costose (review delle bozze, batteria)
        // si rifanno solo quando cambia ciò che le determina, non a ogni scatto che riemette la lista.
        val reads = ListReadCache(
            review = { id -> approvals.review(id) },
            readBatteryExempt = { readAndroidUiHealth(context).batteryExempt },
        )
        emitAll(
            combine(
                // Il combine tipizzato arriva a 5 flow: automazioni, nomi whitelist, lingua e sveglie
                // persistite viaggiano insieme. La sveglia e' un input in piu' della sola riga: non
                // entra nel contesto delle letture costose (review delle bozze, batteria).
                combine(
                    store.observeAll(),
                    whitelist.observeAll(),
                    languageSource.language,
                    database.scheduledTimeAlarmDao().observeAll(),
                ) { automations, contacts, renderLanguage, alarms ->
                    ListInputs(
                        automations,
                        contacts.associate { it.id to it.displayName },
                        renderLanguage,
                        alarms.associateBy { it.automationId },
                    )
                },
                drafts.observeAll(),
                filter,
                shizuku.observeStatus(),
                database.automationDao().observeUiMetadata(),
            ) { (automations, conversationLabels, language, alarms), pending, activeFilter, shizukuStatus, metadata ->
                listState(
                    automations, conversationLabels, language, alarms, pending, activeFilter, shizukuStatus,
                    metadata, reads,
                )
            },
        )
    }.combine(pause.paused) { current, paused -> current.copy(paused = paused) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AutomationListState(
            rows = emptyList(),
            filter = StatusFilter.ALL,
            banner = EngineBanner.NONE,
            loading = true,
        ),
    )

    private suspend fun listState(
        automations: List<Automation>,
        conversationLabels: Map<String, String>,
        language: RenderLanguage,
        alarms: Map<String, ScheduledTimeAlarmEntity>,
        pending: List<PendingDraft>,
        activeFilter: StatusFilter,
        shizukuStatus: ShizukuGatewayStatus,
        metadata: List<AutomationUiMetadata>,
        reads: ListReadCache,
    ): AutomationListState {
        val now = System.currentTimeMillis()
        val lastFiredById = metadata.associate { it.id to it.lastFiredAt }
        // D5: una bozza di modifica su una regola armata/disattivata NON sostituisce la riga della
        // regola (che continua a scattare): diventa il badge «modifica in attesa» sulla sua riga.
        // Solo la bozza di una regola "legacy" in PENDING_APPROVAL, o di una regola assente, resta
        // una riga a sé; per la legacy nasconde la riga della regola in pausa, come prima.
        val statusById = automations.associate { it.id to it.status }
        val editDrafts = pending.filter { snapshot ->
            statusById[snapshot.automationId].let {
                it == AutomationStatus.ARMED || it == AutomationStatus.DISABLED
            }
        }
        val editDraftIdByAutomation = editDrafts.associate { it.automationId to it.id.value }
        val replacedByDraftRow = pending.filterNot { it in editDrafts }
            .mapTo(hashSetOf()) { it.automationId }
        val automationRows = automations
            .filterNot { it.id in replacedByDraftRow }
            .map { automation ->
                automation.toAutomationRow(
                    lastFiredById[automation.id.value],
                    now,
                    conversationLabels,
                    language,
                    pendingEditDraftId = editDraftIdByAutomation[automation.id],
                    scheduledAlarm = alarms[automation.id.value],
                )
            }
        val standaloneDrafts = pending.filterNot { it in editDrafts }
        // La review legge, oltre alla bozza, lo stato di Shizuku/capacità e le regole attive (conflitti):
        // sono questi gli input che la fanno rifare.
        val reviews = reads.reviews(
            standaloneDrafts,
            context = ReviewInputs(
                shizukuStatus,
                automations.filter { it.status == AutomationStatus.ARMED && it.enabled },
            ),
        )
        val pendingRows = standaloneDrafts.map { snapshot ->
            snapshot.toAutomationRow(reviews[snapshot.id], conversationLabels, language)
        }
        val allRows = pendingRows + automationRows
        val visibleRows = allRows.filter { it.matches(activeFilter) }
        val usesShizuku = automations.any { automation ->
            automation.enabled &&
                automation.status == AutomationStatus.ARMED &&
                automation.requiredCapabilities.any(
                    AndroidCapabilityProbe.SHIZUKU_CAPABILITIES::contains,
                )
        } || pending.any { snapshot ->
            CapabilityRequirements.derive(
                snapshot.draft.trigger,
                snapshot.draft.actions,
                snapshot.draft.conditions,
            ).any(AndroidCapabilityProbe.SHIZUKU_CAPABILITIES::contains)
        }
        return AutomationListState(
            rows = visibleRows,
            filter = activeFilter,
            banner = engineBanner(shizukuStatus, usesShizuku) {
                reads.batteryExempt(key = shizukuStatus to usesShizuku)
            },
            loading = false,
            pendingCount = allRows.count { it.isPendingApproval() },
            needsReviewCount = allRows.count { it.status == StatusBadge.NEEDS_REVIEW },
        )
    }

    /** F21: "Riprendi" sul banner della pausa. */
    fun onResumeAutomations() = pause.setPaused(false)

    fun onFilter(value: StatusFilter) {
        savedStateHandle[FILTER_KEY] = value
    }

    fun onToggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                when (enablement.setEnabled(AutomationId(id), enabled)) {
                    EnablementResult.Updated -> Unit
                    EnablementResult.ReviewRequired -> mutableMessages.emit(
                        language.pick(
                            "The rule changed and must be reviewed before re-enabling it.",
                            "La regola è cambiata e va rivista prima di riattivarla.",
                        ),
                    )
                    EnablementResult.SchedulingFailed -> mutableMessages.emit(
                        language.pick(
                            "Scheduling failed: the rule remains disabled.",
                            "Pianificazione non riuscita: la regola è rimasta disattivata.",
                        ),
                    )
                    // B31: l'orario e' passato, non e' un guasto: dice cosa fare.
                    EnablementResult.OneShotInPast -> mutableMessages.emit(oneShotInPastText(language))
                    EnablementResult.DisableCleanupDeferred -> mutableMessages.emit(
                        language.pick(
                            "Rule disabled; runtime reconciliation deferred.",
                            "Regola disattivata; riconciliazione rinviata.",
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableMessages.emit(
                    language.pick("Unable to update the rule.", "Impossibile aggiornare la regola."),
                )
            }
        }
    }

    private fun engineBanner(
        shizukuStatus: ShizukuGatewayStatus,
        usesShizuku: Boolean,
        batteryExempt: () -> Boolean,
    ): EngineBanner = when {
        shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING && usesShizuku ->
            EngineBanner.SHIZUKU_DEGRADED_AFTER_REBOOT
        usesShizuku && shizukuStatus != ShizukuGatewayStatus.AUTHORIZED ->
            EngineBanner.SHIZUKU_DOWN
        !batteryExempt() -> EngineBanner.BATTERY_NOT_EXEMPT
        else -> EngineBanner.NONE
    }

    private companion object {
        const val FILTER_KEY = "automation_filter"
    }
}

private fun AutomationRow.matches(filter: StatusFilter): Boolean = when (filter) {
    StatusFilter.ALL -> true
    StatusFilter.ARMED -> status == StatusBadge.ARMED
    StatusFilter.PENDING -> isPendingApproval()
    StatusFilter.DISABLED -> status == StatusBadge.DISABLED
    StatusFilter.NEEDS_REVIEW -> status == StatusBadge.NEEDS_REVIEW
}

/** In attesa di approvazione: una bozza (nuova o legacy) oppure una regola attiva con modifica in attesa. */
private fun AutomationRow.isPendingApproval(): Boolean =
    status == StatusBadge.PENDING_APPROVAL || pendingEditDraftId != null

/**
 * B32: le letture costose della lista (la review di ogni bozza passa dalle capacità del
 * dispositivo e da Shizuku, la batteria dal sistema) si rifanno solo quando cambia l'input che le
 * determina. Ogni scatto aggiorna le regole e fa riemettere la lista: senza cache ogni emissione
 * rileggeva tutto. Vive per la durata di una raccolta del flow (niente stato dopo l'unsubscribe),
 * quindi tornare sulla lista dopo una pausa rilegge sempre. Le emissioni sono sequenziali.
 */
internal class ListReadCache(
    private val review: suspend (DraftId) -> ApprovalFlowReview?,
    private val readBatteryExempt: () -> Boolean,
) {
    private var reviewContext: Any? = null
    private val reviews = HashMap<PendingDraft, ApprovalFlowReview>()

    private var batteryKey: Any? = null
    private var batteryValue: Boolean? = null

    /**
     * Review delle [pending] nel [context] dato (stato Shizuku, regole attive: quel che la review
     * legge oltre alla bozza). Con lo stesso contesto una bozza già riletta e invariata riusa la
     * review; cambia il contesto o la bozza, si rilegge. Una review fallita (null) non si
     * memorizza: la prossima emissione ritenta. Le bozze uscite dalla lista escono dalla cache.
     */
    suspend fun reviews(
        pending: List<PendingDraft>,
        context: Any?,
    ): Map<DraftId, ApprovalFlowReview?> {
        if (context != reviewContext) {
            reviews.clear()
            reviewContext = context
        }
        reviews.keys.retainAll(pending.toSet())
        val result = LinkedHashMap<DraftId, ApprovalFlowReview?>(pending.size)
        for (snapshot in pending) {
            val known = reviews[snapshot]
            val current = known ?: cancellationSafeOrNull { review(snapshot.id) }
                ?.also { reviews[snapshot] = it }
            result[snapshot.id] = current
        }
        return result
    }

    /** Esenzione dall'ottimizzazione batteria: riletta solo quando cambia la [key] che la rende rilevante. */
    fun batteryExempt(key: Any?): Boolean {
        val known = batteryValue
        if (known != null && key == batteryKey) return known
        return readBatteryExempt().also {
            batteryKey = key
            batteryValue = it
        }
    }
}
