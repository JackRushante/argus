package dev.argus.automation.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.RenderLanguageSource
import dev.argus.data.backup.BackupFailure
import dev.argus.data.backup.FieldToComplete
import dev.argus.data.backup.FieldsToComplete
import dev.argus.data.backup.ImportOutcome
import dev.argus.data.backup.RuleTemplateCatalog
import dev.argus.data.backup.RulesBackup
import dev.argus.data.backup.RulesBackupFormat
import dev.argus.data.backup.TemplateCategory
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.ui.model.TemplateBlankUi
import dev.argus.ui.model.TemplateCardUi
import dev.argus.ui.model.TemplateCategoryUi
import dev.argus.ui.model.TemplateGalleryState
import dev.argus.ui.model.TemplateNeedUi
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.RuleRenderMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Cosa chiede una regola per funzionare, dalle capacità che richiede (le stesse che il probe controlla all'arm):
 * mai dichiarato a mano, così la galleria non può promettere meno di quel che serve.
 */
internal object TemplateNeeds {
    fun of(capabilities: Set<String>): List<TemplateNeedUi> = buildList {
        val privileged = AndroidCapabilityProbe.PRIVILEGED_CAPABILITIES + AndroidCapabilityProbe.NFC_CAPABILITIES +
            AndroidCapabilityProbe.TOOL_STATE_READ
        if (capabilities.any { it in privileged }) add(TemplateNeedUi.PRIVILEGED)
        if (capabilities.any { it in AndroidCapabilityProbe.BASE_DND_CAPABILITIES }) add(TemplateNeedUi.DND_ACCESS)
        if (capabilities.any { it in AndroidCapabilityProbe.WRITE_SETTINGS_CAPABILITIES }) add(TemplateNeedUi.WRITE_SETTINGS)
        if (capabilities.any { it in LOCATION }) add(TemplateNeedUi.LOCATION)
        if (capabilities.any { it in NOTIFICATION_ACCESS }) add(TemplateNeedUi.NOTIFICATION_ACCESS)
        if (ActionCapabilities.SHOW_NOTIFICATION in capabilities) add(TemplateNeedUi.NOTIFICATIONS)
        if (CapabilityIds.TRIGGER_PHONE_SMS in capabilities) add(TemplateNeedUi.SMS)
        if (CapabilityIds.TRIGGER_PHONE_CALL in capabilities) add(TemplateNeedUi.CALLS)
        if (CapabilityIds.TRIGGER_CONNECTIVITY_BT in capabilities) add(TemplateNeedUi.BLUETOOTH)
        if (CapabilityIds.TRIGGER_APP_EVENT in capabilities) add(TemplateNeedUi.USAGE_ACCESS)
        if (ActionCapabilities.UI_STEPS in capabilities) add(TemplateNeedUi.ACCESSIBILITY)
    }

    private val LOCATION = setOf(
        CapabilityIds.TRIGGER_GEOFENCE,
        CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY,
        CapabilityIds.STATE_LOCATION,
    )
    private val NOTIFICATION_ACCESS = setOf(
        CapabilityIds.TRIGGER_NOTIFICATION,
        GenerativeContract.TOOL_WHATSAPP_REPLY,
        ActionCapabilities.WHATSAPP_REPLY,
    )
}

sealed interface TemplateGalleryEvent {
    /** La bozza appena importata: se ne apre l'approvazione. */
    data class OpenDraft(val draftId: String) : TemplateGalleryEvent

    data class Message(val text: String) : TemplateGalleryEvent

    /** Più bozze importate da un file o da un testo: si va all'elenco, dove aspettano l'approvazione. */
    data object OpenList : TemplateGalleryEvent
}

/**
 * Galleria dei modelli (0.7.2). «Usa questo modello» importa i byte del modello con [RulesBackup.importBytes]:
 * lo stesso percorso di un file importato, quindi una bozza `IMPORT` che si legge e si approva come le altre.
 * Dalla galleria si importa anche da file o da testo, con la stessa anteprima di Sistema.
 */
@HiltViewModel
class TemplateGalleryViewModel @Inject constructor(
    private val catalog: RuleTemplateCatalog,
    private val backup: RulesBackup,
    private val languageSource: RenderLanguageSource,
) : ViewModel() {
    /** Per i test: dove leggere gli asset e preparare le card. */
    internal var dispatcher: CoroutineDispatcher = Dispatchers.Default

    /** Per i test: il fuso del telefono in cui si mettono gli orari dei modelli. */
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    private val mutableState = MutableStateFlow(TemplateGalleryState())
    val state: StateFlow<TemplateGalleryState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<TemplateGalleryEvent>(extraBufferCapacity = 4)
    val events = mutableEvents.asSharedFlow()

    private val language: RenderLanguage get() = languageSource.current
    private val busy = AtomicBoolean(false)
    private val textImport = TextImportPreviewer(backup, viewModelScope, { language })

    init {
        viewModelScope.launch {
            languageSource.language.collectLatest { language ->
                val cards = try {
                    withContext(dispatcher) { cards(language) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                mutableState.update { it.copy(templates = cards, loading = false) }
            }
        }
        viewModelScope.launch {
            textImport.preview.collect { preview -> mutableState.update { it.copy(textPreview = preview) } }
        }
    }

    private fun cards(language: RenderLanguage): List<TemplateCardUi> {
        val italian = language == RenderLanguage.IT
        return catalog.templates().mapNotNull { template ->
            val draft = catalog.draft(template.id, italian) ?: return@mapNotNull null
            val render = RuleRenderMapper.mapDraft(draft, language = language)
            TemplateCardUi(
                id = template.id,
                category = template.category.toUi(),
                title = template.title.pick(italian),
                description = template.description.pick(italian),
                triggerIconKey = render.triggerIconKey,
                isGenerative = render.isGenerative,
                needs = TemplateNeeds.of(
                    CapabilityRequirements.derive(draft.trigger, draft.actions, draft.conditions, draft.vars),
                ),
                blanks = FieldsToComplete.of(draft).map { it.toUi() }.sortedBy { it.ordinal },
            )
        }
    }

    fun onFilter(category: TemplateCategoryUi?) {
        mutableState.update { it.copy(filter = category) }
    }

    /** «Usa questo modello»: una bozza importata, poi la sua approvazione. Un modello alla volta. */
    fun onUseTemplate(id: String) {
        if (!busy.compareAndSet(false, true)) return
        mutableState.update { it.copy(busyTemplateId = id) }
        viewModelScope.launch {
            try {
                val bytes = withContext(dispatcher) {
                    catalog.ruleBytes(id, italian = language == RenderLanguage.IT, zone = zone())
                }
                if (bytes == null) {
                    emit(TemplateGalleryEvent.Message(RulesBackupText.failure(BackupFailure.READ_FAILED, language)))
                } else {
                    afterImport(backup.importBytes(bytes), text = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emit(TemplateGalleryEvent.Message(RulesBackupText.failure(BackupFailure.SAVE_FAILED, language)))
            } finally {
                busy.set(false)
                mutableState.update { it.copy(busyTemplateId = null) }
            }
        }
    }

    fun onImportTextChange(text: String) = textImport.onTextChange(text)

    /** Import da testo dalla galleria: stesso import di Sistema. */
    fun onImportText(text: String) {
        textImport.clear()
        runImport(text = true) {
            val bytes = RulesBackupFormat.utf8WithinCap(text)
                ?: return@runImport ImportOutcome.Failed(BackupFailure.FILE_TOO_LARGE)
            backup.importBytes(bytes)
        }
    }

    /** Import da file dalla galleria. [source] null = selettore annullato. */
    fun onImportFile(source: Uri?) {
        if (source == null) return
        runImport(text = false) { backup.import(source) }
    }

    private fun runImport(text: Boolean, block: suspend () -> ImportOutcome) {
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                afterImport(block(), text)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emit(TemplateGalleryEvent.Message(RulesBackupText.failure(BackupFailure.READ_FAILED, language)))
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun afterImport(outcome: ImportOutcome, text: Boolean) {
        val (message, imported) = ImportPreviewText.outcome(outcome, language, text)
        val drafts = (outcome as? ImportOutcome.Done)?.draftIds.orEmpty()
        emit(TemplateGalleryEvent.Message(message))
        when {
            imported == 1 && drafts.size == 1 -> emit(TemplateGalleryEvent.OpenDraft(drafts.single()))
            imported > 1 -> emit(TemplateGalleryEvent.OpenList)
        }
    }

    private suspend fun emit(event: TemplateGalleryEvent) = mutableEvents.emit(event)

    private fun TemplateCategory.toUi(): TemplateCategoryUi = when (this) {
        TemplateCategory.HOME -> TemplateCategoryUi.HOME
        TemplateCategory.WORK -> TemplateCategoryUi.WORK
        TemplateCategory.BATTERY -> TemplateCategoryUi.BATTERY
        TemplateCategory.MESSAGES -> TemplateCategoryUi.MESSAGES
    }

    private fun FieldToComplete.toUi(): TemplateBlankUi = when (this) {
        FieldToComplete.CHAT -> TemplateBlankUi.CHAT
        FieldToComplete.SENDER -> TemplateBlankUi.SENDER
        FieldToComplete.PHONE_NUMBER -> TemplateBlankUi.PHONE_NUMBER
        FieldToComplete.NETWORK -> TemplateBlankUi.NETWORK
        FieldToComplete.PLACE -> TemplateBlankUi.PLACE
        FieldToComplete.SECRET -> TemplateBlankUi.SECRET
        FieldToComplete.CURRENT_LOCATION -> TemplateBlankUi.CURRENT_LOCATION
    }
}
