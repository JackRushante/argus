package dev.argus.ui.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stringhe del blocco LOG-SISTEMA del redesign A (prefisso `rda_ls_`): stesse chiavi in inglese e italiano, stessi
 * segnaposto, apostrofi con l'escape che Android richiede, nessun testo vuoto. E nessuna schermata del blocco
 * contiene copy utente scritto nel codice al posto della risorsa.
 */
class RdaLsStringsTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private val englishKeys = strings(english).filterKeys { it.startsWith("rda_ls_") }
    private val italianKeys = strings(italian).filterKeys { it.startsWith("rda_ls_") }

    @Test
    fun `keys are the same in both languages`() {
        assertTrue(englishKeys.isNotEmpty())
        assertEquals(englishKeys.keys, italianKeys.keys)
    }

    @Test
    fun `strings carry the same placeholders in both languages`() {
        englishKeys.forEach { (key, text) ->
            assertEquals(placeholders(text), placeholders(italianKeys.getValue(key)), key)
        }
    }

    @Test
    fun `strings are not blank and apostrophes are escaped`() {
        (englishKeys + italianKeys.mapKeys { "it:" + it.key }).forEach { (key, text) ->
            assertTrue(text.isNotBlank(), key)
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
        }
    }

    @Test
    fun `the log filter names the four outcomes of the design`() {
        assertEquals(
            listOf("All", "Problems", "Generative", "Lifecycle"),
            listOf("all", "problems", "generative", "lifecycle").map { englishKeys.getValue("rda_ls_log_filter_$it") },
        )
        assertEquals(
            listOf("Tutti", "Problemi", "Generative", "Ciclo di vita"),
            listOf("all", "problems", "generative", "lifecycle").map { italianKeys.getValue("rda_ls_log_filter_$it") },
        )
    }

    @Test
    fun `a missing value is written n-a in english and n-d in italian`() {
        assertEquals("n/a", englishKeys.getValue("rda_ls_not_available"))
        assertEquals("n/d", italianKeys.getValue("rda_ls_not_available"))
    }

    @Test
    fun `the api key is never named as a value anywhere the sheet can show it`() {
        // «Lascia vuoto per conservare la chiave attuale.» e' il testo di sempre: la chiave non si mostra mai.
        assertEquals("Leave blank to keep the current key.", strings(english).getValue("provider_key_keep_hint"))
        assertEquals("Lascia vuoto per conservare la chiave attuale.", strings(italian).getValue("provider_key_keep_hint"))
        assertEquals("API key configured", strings(english).getValue("settings_key_configured"))
        assertEquals("Chiave configurata", strings(italian).getValue("settings_key_configured"))
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
