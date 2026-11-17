package dev.argus.automation.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.modes.UserModeEditor
import dev.argus.data.ModeEditResult
import dev.argus.data.RoomStoredValueStore
import dev.argus.data.RoomUserModeStore
import dev.argus.data.StoredEditResult
import dev.argus.engine.model.Automation
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.UserModeListIssue
import dev.argus.engine.model.UserModePolicy
import dev.argus.engine.model.UserModeSnapshot
import dev.argus.engine.model.toDraft
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.UserModeSwitcher
import dev.argus.ui.model.ModeRowUi
import dev.argus.ui.model.ModesState
import dev.argus.ui.model.StoredValueRowUi
import dev.argus.ui.model.StoredValueTypeUi
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/**
 * Testi della schermata Modalità, ricavati dai dati: nessun accesso ad Android, così si prova su JVM.
 * Una regola che non c'è più si dice «una regola eliminata», mai un nome inventato.
 */
object ModesUiMapper {
    fun state(
        snapshot: UserModeSnapshot,
        values: List<StoredValue>,
        rules: List<Automation>,
        language: RenderLanguage,
        nowMillis: Long,
        zone: ZoneId,
    ): ModesState {
        val names = rules.associate { it.id to it.name }
        val drafts = rules.map { it.toDraft() }
        fun author(author: ChangeAuthor?): String = when (author) {
            null, ChangeAuthor.User -> language.pick("by you", "da te")
            is ChangeAuthor.Rule -> names[author.id]?.let { language.pick("by the rule «$it»", "dalla regola «$it»") }
                ?: language.pick("by a deleted rule", "da una regola eliminata")
        }
        return ModesState(
            loading = false,
            modes = snapshot.modes.map { mode ->
                ModeRowUi(
                    name = mode,
                    conditionRules = drafts.count { UserModePolicy.usesAsCondition(it, mode) },
                    usedByRules = drafts.count { UserModePolicy.uses(it, mode) },
                )
            },
            activeMode = snapshot.active,
            lastChange = snapshot.changedAtMillis?.let { at ->
                language.pick("Changed ", "Cambiata ") + whenText(at, nowMillis, zone, language, withTime = true) +
                    " " + author(snapshot.changedBy) + "."
            },
            values = values.sortedBy { it.key }.map { value ->
                val masked = value.confidentiality == ConfidentialityLabel.SECRET
                StoredValueRowUi(
                    key = value.key,
                    type = typeOf(value.type),
                    value = if (masked) "" else value.text,
                    masked = masked,
                    detail = typeLabel(value.type, language) + " · " + language.pick("updated ", "aggiornata ") +
                        whenText(value.updatedAtMillis, nowMillis, zone, language, withTime = false) + " " +
                        when (val writer = value.writer) {
                            ChangeAuthor.User -> language.pick("by you", "da te")
                            is ChangeAuthor.Rule -> names[writer.id]?.let { "${language.pick("by", "da")} «$it»" }
                                ?: language.pick("by a deleted rule", "da una regola eliminata")
                        },
                )
            },
            canAddMode = snapshot.modes.size < UserModePolicy.MAX_MODES,
        )
    }

    /** «oggi alle 18:42» / «oggi 08:10», «ieri alle …», il giorno della settimana, poi la data. */
    fun whenText(atMillis: Long, nowMillis: Long, zone: ZoneId, language: RenderLanguage, withTime: Boolean): String {
        val locale = if (language == RenderLanguage.IT) Locale.ITALIAN else Locale.ENGLISH
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = at.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        val date: LocalDate = at.toLocalDate()
        val days = today.toEpochDay() - date.toEpochDay()
        val day = when {
            days == 0L -> language.pick("today", "oggi")
            days == 1L -> language.pick("yesterday", "ieri")
            days in 2..6 -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).lowercase(locale)
                .let { if (language == RenderLanguage.EN) it.replaceFirstChar(Char::titlecase) else it }
            else -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
        }
        return when {
            withTime -> day + language.pick(" at ", " alle ") + time
            days == 0L -> "$day $time"
            else -> day
        }
    }

    private fun typeOf(type: StateValueType) = when (type) {
        StateValueType.TEXT -> StoredValueTypeUi.TEXT
        StateValueType.NUMBER -> StoredValueTypeUi.NUMBER
        StateValueType.BOOLEAN -> StoredValueTypeUi.BOOLEAN
    }

    private fun typeLabel(type: StateValueType, language: RenderLanguage) = when (type) {
        StateValueType.TEXT -> language.pick("Text", "Testo")
        StateValueType.NUMBER -> language.pick("Number", "Numero")
        StateValueType.BOOLEAN -> language.pick("Yes/no", "Sì/no")
    }

    fun issueText(issue: UserModeListIssue, language: RenderLanguage): String = when (issue) {
        UserModeListIssue.NAME_INVALID -> language.pick(
            "A mode name is 1 to ${UserModePolicy.MAX_NAME_CHARS} characters.",
            "Il nome di una modalità va da 1 a ${UserModePolicy.MAX_NAME_CHARS} caratteri.",
        )
        UserModeListIssue.DUPLICATE -> language.pick("A mode with this name already exists.", "Esiste già una modalità con questo nome.")
        UserModeListIssue.TOO_MANY -> language.pick(
            "You can have at most ${UserModePolicy.MAX_MODES} modes.",
            "Puoi avere al massimo ${UserModePolicy.MAX_MODES} modalità.",
        )
        UserModeListIssue.UNKNOWN -> language.pick("This mode no longer exists.", "Questa modalità non esiste più.")
    }
}

/**
 * Sistema › Modalità (0.8). Cambiare modalità da qui è un gesto dell'utente e passa da [UserModeSwitcher], come il
 * riquadro e il widget: le regole `user_mode_changed` scattano una volta. Un valore corretto a mano è CLEAN.
 */
@HiltViewModel
class ModesViewModel @Inject constructor(
    private val modes: RoomUserModeStore,
    private val values: RoomStoredValueStore,
    private val automations: AutomationStore,
    private val switcher: UserModeSwitcher,
    private val editor: UserModeEditor,
    private val languageSource: RenderLanguageSource,
) : ViewModel() {
    /** Per i test: orologio e fuso dei testi «oggi alle…». */
    internal var nowMillis: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Messaggi brevi (snackbar) dopo un'azione rifiutata o riuscita. */
    val messages = mutableMessages.asSharedFlow()

    val state: StateFlow<ModesState> = combine(
        modes.observe(),
        values.observe(),
        automations.observeAll(),
        languageSource.language,
    ) { snapshot, stored, rules, language ->
        ModesUiMapper.state(snapshot, stored, rules, language, nowMillis(), zone())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ModesState())

    private val language: RenderLanguage get() = languageSource.current

    fun onSwitchMode(name: String) = act {
        when (switcher.switchTo(name, ChangeAuthor.User)) {
            is ModeSwitch.Changed, ModeSwitch.Unchanged -> Unit
            ModeSwitch.UnknownMode -> say(ModesUiMapper.issueText(UserModeListIssue.UNKNOWN, language))
        }
    }

    fun onAddMode(name: String) = act { report(editor.add(name)) }

    fun onRenameMode(from: String, to: String) = act { report(editor.rename(from, to)) }

    fun onDeleteMode(name: String) = act { report(editor.delete(name)) }

    fun onEditValue(key: String, value: String) = act {
        when (values.editByUser(key, value)) {
            StoredEditResult.SAVED -> Unit
            StoredEditResult.INVALID -> say(language.pick("This value does not fit the variable's type.", "Il valore non è del tipo della variabile."))
            StoredEditResult.MISSING -> say(language.pick("This variable no longer exists.", "Questa variabile non esiste più."))
        }
    }

    fun onDeleteValue(key: String) = act { values.delete(key) }

    private fun report(result: ModeEditResult) {
        if (result is ModeEditResult.Refused) say(ModesUiMapper.issueText(result.issue, language))
    }

    private fun say(text: String) {
        mutableMessages.tryEmit(text)
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                say(language.pick("Could not save the change.", "Non è stato possibile salvare la modifica."))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
