package dev.argus.automation.vm

import dev.argus.brain.LocalServerPresets
import dev.argus.brain.ProviderId
import dev.argus.ui.model.LocalPresetUi

/**
 * Preset dei server locali (0.5.1, F4) per il dialogo del provider: solo il Custom, che è l'unico a
 * poter cambiare indirizzo. Gli indirizzi vengono dall'unica fonte, [LocalServerPresets].
 */
internal fun localPresetsUi(id: ProviderId): List<LocalPresetUi> =
    if (id == ProviderId.CUSTOM_OPENAI_COMPAT) {
        LocalServerPresets.all.map { LocalPresetUi(id = it.id, label = it.displayName, baseUrl = it.baseUrl) }
    } else {
        emptyList()
    }
