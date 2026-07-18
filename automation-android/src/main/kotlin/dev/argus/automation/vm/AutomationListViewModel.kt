package dev.argus.automation.vm

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.AutomationEnablementCoordinator
import dev.argus.automation.EnablementResult
import dev.argus.data.ArgusDatabase
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.safety.DraftRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutomationListViewModel @Inject constructor(
    private val store: AutomationStore,
    drafts: DraftRepository,
    private val approvals: ApprovalFlow,
    private val enablement: AutomationEnablementCoordinator,
    shizuku: ShizukuGateway,
    database: ArgusDatabase,
    whitelist: ContactWhitelistStore,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val language: RenderLanguage = RenderLanguage.system(),
) : ViewModel() {
    private val filter = savedStateHandle.getStateFlow(FILTER_KEY, StatusFilter.ALL)
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = mutableMessages.asSharedFlow()

    val state: StateFlow<AutomationListState> = combine(
        // Il combine tipizzato arriva a 5 flow: automazioni e nomi whitelist viaggiano in coppia.
        store.observeAll().combine(whitelist.observeAll()) { automations, contacts ->
            automations to contacts.associate { it.id to it.displayName }
        },
        drafts.observeAll(),
        filter,
        shizuku.observeStatus(),
        database.automationDao().observeUiMetadata(),
    ) { (automations, conversationLabels), pending, activeFilter, shizukuStatus, metadata ->
        val now = System.currentTimeMillis()
        val lastFiredById = metadata.associate { it.id to it.lastFiredAt }
        val pendingAutomationIds = pending.mapTo(hashSetOf()) { it.automationId }
        val automationRows = automations
            .filterNot { it.id in pendingAutomationIds }
            .map { automation ->
                automation.toAutomationRow(
                    lastFiredById[automation.id.value],
                    now,
                    conversationLabels,
                    language,
                )
            }
        val pendingRows = pending.map { snapshot ->
            val review = cancellationSafeOrNull { approvals.review(snapshot.id) }
            snapshot.toAutomationRow(review, conversationLabels, language)
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
        AutomationListState(
            rows = visibleRows,
            filter = activeFilter,
            banner = engineBanner(shizukuStatus, usesShizuku),
            loading = false,
            pendingCount = allRows.count { it.status == StatusBadge.PENDING_APPROVAL },
            needsReviewCount = allRows.count { it.status == StatusBadge.NEEDS_REVIEW },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AutomationListState(
            rows = emptyList(),
            filter = StatusFilter.ALL,
            banner = EngineBanner.NONE,
            loading = true,
        ),
    )

    fun onFilter(value: StatusFilter) {
        savedStateHandle[FILTER_KEY] = value
    }

    fun onToggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val automationId = AutomationId(id)
                when (enablement.setEnabled(automationId, enabled)) {
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
    ): EngineBanner = when {
        shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING && usesShizuku ->
            EngineBanner.SHIZUKU_DEGRADED_AFTER_REBOOT
        usesShizuku && shizukuStatus != ShizukuGatewayStatus.AUTHORIZED ->
            EngineBanner.SHIZUKU_DOWN
        !readAndroidUiHealth(context).batteryExempt -> EngineBanner.BATTERY_NOT_EXEMPT
        else -> EngineBanner.NONE
    }

    private companion object {
        const val FILTER_KEY = "automation_filter"
    }
}

private fun AutomationRow.matches(filter: StatusFilter): Boolean = when (filter) {
    StatusFilter.ALL -> true
    StatusFilter.ARMED -> status == StatusBadge.ARMED
    StatusFilter.PENDING -> status == StatusBadge.PENDING_APPROVAL
    StatusFilter.DISABLED -> status == StatusBadge.DISABLED
    StatusFilter.NEEDS_REVIEW -> status == StatusBadge.NEEDS_REVIEW
}
