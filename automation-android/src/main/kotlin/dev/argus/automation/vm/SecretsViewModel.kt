package dev.argus.automation.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.secrets.SecretDeletion
import dev.argus.engine.model.Automation
import dev.argus.engine.model.SecretPolicy
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.secrets.SecretEntry
import dev.argus.engine.secrets.SecretVault
import dev.argus.ui.model.SecretRowUi
import dev.argus.ui.model.SecretsState
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

sealed interface SecretsEvent {
    data class Message(val text: String) : SecretsEvent
}

/** The rows of Sistema › Segreti: names only, with how many rules use each one. */
internal fun secretRows(entries: List<SecretEntry>, rules: List<Automation>): List<SecretRowUi> {
    val usage = HashMap<String, Int>()
    rules.forEach { rule -> SecretPolicy.namesUsedBy(rule).forEach { usage[it] = (usage[it] ?: 0) + 1 } }
    return entries.map { SecretRowUi(it.name, usage[it.name] ?: 0, it.lastUsedAtMillis) }
}

/**
 * 0.8 · Sistema › Segreti. The state carries names, usage counts and last-use times; a value passes through
 * [onSave] straight to the vault and is never kept, logged or put in a message.
 */
@HiltViewModel
class SecretsViewModel @Inject constructor(
    private val vault: SecretVault,
    private val deletion: SecretDeletion,
    automations: AutomationStore,
    private val languageSource: RenderLanguageSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SecretsState())
    val state: StateFlow<SecretsState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<SecretsEvent>(extraBufferCapacity = 4)
    val events = mutableEvents.asSharedFlow()

    private val language: RenderLanguage get() = languageSource.current
    private val busy = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            combine(vault.observe(), automations.observeAll()) { entries, rules -> secretRows(entries, rules) }
                .catch { emit(emptyList()) }
                .collect { rows -> mutableState.update { it.copy(secrets = rows, loading = false) } }
        }
        // Reads the vault once: the flow above starts from what the vault had when it was created.
        viewModelScope.launch { runCatching { vault.entries() } }
    }

    fun onSave(name: String, value: String) {
        if (!busy.compareAndSet(false, true)) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val saved = try {
                vault.save(name, value)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            finish(
                if (saved) {
                    language.pick("Secret $name saved", "Segreto $name salvato")
                } else {
                    language.pick("The secret could not be saved", "Non è stato possibile salvare il segreto")
                },
            )
        }
    }

    fun onDelete(name: String) {
        if (!busy.compareAndSet(false, true)) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val deleted = try {
                deletion.delete(name)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            finish(
                if (deleted) {
                    language.pick("Secret $name deleted", "Segreto $name eliminato")
                } else {
                    language.pick("The secret could not be deleted", "Non è stato possibile eliminare il segreto")
                },
            )
        }
    }

    private fun finish(message: String) {
        busy.set(false)
        mutableState.update { it.copy(busy = false) }
        mutableEvents.tryEmit(SecretsEvent.Message(message))
    }
}
