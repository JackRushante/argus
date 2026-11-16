package dev.argus.ui.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stringhe 0.7.2 di condivisione, import da testo, modelli e «Cosa cambia»: stesse chiavi in inglese e in
 * italiano, stessi segnaposto, tradotte e con gli apostrofi escapati come vuole Android.
 */
class ShareGalleryStringsTest {
    private val english = strings(File("src/main/res/values/strings.xml").readText())
    private val italian = strings(File("src/main/res/values-it/strings.xml").readText())

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private fun ofPrefix(map: Map<String, String>) = map.filterKeys { key -> PREFIXES.any { key.startsWith(it) } }

    @Test
    fun `every prefix has strings and the same keys in both languages`() {
        PREFIXES.forEach { prefix -> assertTrue(english.keys.any { it.startsWith(prefix) }, prefix) }
        assertEquals(ofPrefix(english).keys, ofPrefix(italian).keys)
    }

    @Test
    fun `strings carry the same placeholders and are translated`() {
        ofPrefix(english).forEach { (key, text) ->
            val it = italian.getValue(key)
            assertEquals(placeholders(text), placeholders(it), key)
            assertNotEquals(text, it, key)
            assertTrue(text.isNotBlank() && it.isNotBlank(), key)
        }
    }

    @Test
    fun `apostrophes are escaped as Android requires`() {
        (ofPrefix(english) + ofPrefix(italian).mapKeys { "it:" + it.key }).forEach { (key, text) ->
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
        }
    }

    @Test
    fun `the share sheet uses the wording of the approved mockup`() {
        assertEquals("Condividi regola", italian.getValue("share_title"))
        assertEquals("Togli i dati personali", italian.getValue("share_strip_label"))
        assertEquals("Condividi come testo", italian.getValue("share_as_text"))
        assertEquals("Salva file", italian.getValue("share_save_file"))
        assertEquals("Copia", italian.getValue("share_copy"))
        assertEquals("Cosa cambia", italian.getValue("diff_title"))
        assertEquals("What changes", english.getValue("diff_title"))
    }

    private companion object {
        val PREFIXES = listOf("share_", "diff_")
        val STRING = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
