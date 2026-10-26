package dev.argus.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Il consiglio sul modello nella finestra del provider (0.3.7): una fascia generica, uguale per
 * onboarding e impostazioni, senza il nome di nessun modello (i nomi cambiano e stanno solo nei
 * suggerimenti del catalogo). Esiste in inglese e in italiano.
 */
class ProviderModelAdviceTest {
    private val english = strings(File("src/main/res/values/strings.xml").readText())
    private val italian = strings(File("src/main/res/values-it/strings.xml").readText())

    @Test
    fun `the advice exists in both languages with the agreed wording`() {
        assertEquals(
            "Argus needs a capable model: at least the base model of a major provider, or a " +
                "self-hosted model with about 27B parameters or more. Smaller models can't write valid rules.",
            english["provider_model_advice"],
        )
        assertEquals(
            "Argus ha bisogno di un modello capace: almeno il modello base di un grande fornitore, " +
                "oppure in locale un modello da circa 27 miliardi di parametri in su. " +
                "I modelli più piccoli non riescono a scrivere regole valide.",
            italian["provider_model_advice"],
        )
    }

    @Test
    fun `the advice cites no model name`() {
        val families = Regex("gpt|claude|gemini|deepseek|kimi|haiku|opus|sonnet|luna|flash|llama|qwen", RegexOption.IGNORE_CASE)
        listOf(english, italian).forEach { texts ->
            assertFalse(families.containsMatchIn(texts.getValue("provider_model_advice")), texts["provider_model_advice"])
        }
    }

    @Test
    fun `the openrouter hint shows the first moving alias of the catalog in both languages`() {
        assertTrue("~deepseek/deepseek-flash-latest" in english.getValue("provider_model_hint_openrouter"))
        assertTrue("~deepseek/deepseek-flash-latest" in italian.getValue("provider_model_hint_openrouter"))
        assertFalse("deepseek-v4.1-flash" in english.getValue("provider_model_hint_openrouter"))
        assertFalse("deepseek-v4.1-flash" in italian.getValue("provider_model_hint_openrouter"))
    }

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
