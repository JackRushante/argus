package dev.argus.ui.presentation

import dev.argus.ui.model.LocalPresetUi

/**
 * Logica pura dei preset dei server locali nel dialogo del provider Custom (0.5.1, F4): quale preset è
 * attivo per l'indirizzo scritto.
 */
object LocalPresetHints {
    /** Il preset il cui indirizzo coincide con [baseUrl] (senza spazi né slash finale), o null. */
    fun active(presets: List<LocalPresetUi>, baseUrl: String): LocalPresetUi? {
        val clean = baseUrl.trim().trimEnd('/')
        return presets.firstOrNull { it.baseUrl == clean }
    }
}
