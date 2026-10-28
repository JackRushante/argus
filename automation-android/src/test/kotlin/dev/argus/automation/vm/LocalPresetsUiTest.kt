package dev.argus.automation.vm

import dev.argus.brain.LocalServerPresets
import dev.argus.brain.ProviderId
import dev.argus.ui.model.LocalPresetUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 0.5.1 F4: i preset dei server locali arrivano al dialogo solo per il Custom, dall'unica fonte. */
class LocalPresetsUiTest {
    @Test
    fun `only the custom provider offers the local server presets`() {
        ProviderId.entries.filter { it != ProviderId.CUSTOM_OPENAI_COMPAT }.forEach { id ->
            assertTrue(localPresetsUi(id).isEmpty(), id.name)
        }
        val presets = localPresetsUi(ProviderId.CUSTOM_OPENAI_COMPAT)
        assertEquals(listOf("ollama", "lmstudio", "llamacpp"), presets.map(LocalPresetUi::id))
        assertEquals(
            LocalServerPresets.all.map { it.baseUrl },
            presets.map(LocalPresetUi::baseUrl),
        )
        assertEquals(listOf("Ollama", "LM Studio", "llama.cpp"), presets.map(LocalPresetUi::label))
    }

    @Test
    fun `every preset is a loopback address the custom dialog accepts`() {
        localPresetsUi(ProviderId.CUSTOM_OPENAI_COMPAT).forEach { preset ->
            assertTrue(preset.baseUrl.startsWith("http://127.0.0.1:"), preset.baseUrl)
            assertTrue(preset.baseUrl.endsWith("/v1"), preset.baseUrl)
        }
    }
}
