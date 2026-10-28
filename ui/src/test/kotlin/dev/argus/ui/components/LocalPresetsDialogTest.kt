package dev.argus.ui.components

import dev.argus.ui.R
import dev.argus.ui.model.LocalPresetUi
import dev.argus.ui.presentation.ConnectionFormValidation
import dev.argus.ui.presentation.LocalPresetHints
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.5.1 F4: preset dei server locali nel dialogo del provider Custom. Il dialogo compila l'indirizzo e mostra la
 * nota sui 16.000 token di contesto; la regola sul cleartext (solo loopback) non cambia.
 */
class LocalPresetsDialogTest {
    private val presets = listOf(
        LocalPresetUi("ollama", "Ollama", "http://127.0.0.1:11434/v1"),
        LocalPresetUi("lmstudio", "LM Studio", "http://127.0.0.1:1234/v1"),
        LocalPresetUi("llamacpp", "llama.cpp", "http://127.0.0.1:8080/v1"),
    )

    @Test
    fun `the active preset is the one whose address is written, ignoring spaces and a trailing slash`() {
        assertEquals("ollama", LocalPresetHints.active(presets, "http://127.0.0.1:11434/v1")?.id)
        assertEquals("lmstudio", LocalPresetHints.active(presets, "  http://127.0.0.1:1234/v1/  ")?.id)
        assertEquals("llamacpp", LocalPresetHints.active(presets, "http://127.0.0.1:8080/v1")?.id)
        assertNull(LocalPresetHints.active(presets, "http://127.0.0.1:9999/v1"))
        assertNull(LocalPresetHints.active(presets, ""))
        assertNull(LocalPresetHints.active(emptyList(), "http://127.0.0.1:11434/v1"))
    }

    @Test
    fun `every preset has its own note and an unknown one has none`() {
        val hints = presets.map { assertNotNull(localPresetHintRes(it.id), it.id) }
        assertEquals(hints.size, hints.toSet().size, "una nota per ogni server")
        assertEquals(R.string.ai51_local_hint_ollama, localPresetHintRes("ollama"))
        assertNull(localPresetHintRes("altro"))
    }

    @Test
    fun `every preset passes the form rules for the custom provider and keeps the cleartext rule`() {
        presets.forEach { preset ->
            val problems = ConnectionFormValidation.providerForm(
                baseUrl = preset.baseUrl,
                model = "qwen-local",
                apiKey = "",
                baseUrlEditable = true,
                cleartextLoopbackAllowed = true,
                keyRequired = false,
                keyConfigured = false,
                minKeyLength = ConnectionFormValidation.MIN_CUSTOM_SECRET_LENGTH,
            )
            assertTrue(problems.accepted, "${preset.id}: ${problems.url}")
        }
        // La regola non cambia: http solo verso un host loopback esatto, e solo se il dialogo lo ammette.
        listOf("http://192.168.1.10:11434/v1", "http://studio.local:1234/v1", "http://localhost.example.com/v1")
            .forEach { url ->
                val problems = ConnectionFormValidation.providerForm(
                    baseUrl = url, model = "m", apiKey = "", baseUrlEditable = true,
                    cleartextLoopbackAllowed = true, keyRequired = false, keyConfigured = false,
                    minKeyLength = ConnectionFormValidation.MIN_CUSTOM_SECRET_LENGTH,
                )
                assertFalse(problems.accepted, url)
                assertNotNull(problems.url, url)
            }
    }

    @Test
    fun `the local server strings exist in both languages and are translated`() {
        val english = strings(File("src/main/res/values/strings.xml").readText()).filterKeys { it.startsWith("ai51_") }
        val italian = strings(File("src/main/res/values-it/strings.xml").readText()).filterKeys { it.startsWith("ai51_") }

        assertEquals(
            setOf(
                "ai51_local_presets_label", "ai51_local_note", "ai51_local_hint_ollama", "ai51_local_hint_lmstudio",
                "ai51_local_hint_llamacpp", "ai51_local_loopback_note",
            ),
            english.keys,
        )
        assertEquals(english.keys, italian.keys)
        english.forEach { (key, text) ->
            assertNotEquals(text, italian.getValue(key), key)
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(italian.getValue(key)), "apostrofo non escapato in it:$key")
        }
        // La nota sul contesto cita i 16.000 token (con il separatore di ogni lingua).
        assertTrue("16,000 tokens" in english.getValue("ai51_local_note"))
        assertTrue("16.000 token" in italian.getValue("ai51_local_note"))
        // Ogni server dice come portare il contesto a 16384.
        listOf("ai51_local_hint_ollama", "ai51_local_hint_lmstudio", "ai51_local_hint_llamacpp").forEach { key ->
            assertTrue("16384" in english.getValue(key), key)
            assertTrue("16384" in italian.getValue(key), key)
        }
    }

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
