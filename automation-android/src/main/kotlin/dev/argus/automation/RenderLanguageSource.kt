package dev.argus.automation

import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lingua di rendering corrente per chi produce testo fuori da Compose (B34): segue il cambio di
 * lingua del telefono senza riavviare l'app. I ViewModel sopravvivono al cambio di configurazione,
 * quindi chi genera testo dentro un flow combina [language] per rigenerarlo; chi lo genera al
 * momento (errori, messaggi) legge [current].
 */
class RenderLanguageSource(private val system: () -> RenderLanguage = RenderLanguage::system) {
    private val state = MutableStateFlow(system())

    val language: StateFlow<RenderLanguage> = state.asStateFlow()

    val current: RenderLanguage get() = state.value

    /** Rilegge il locale di sistema; da chiamare a ogni cambio di configurazione. */
    fun refresh() {
        state.value = system()
    }
}
