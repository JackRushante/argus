package dev.argus.ui.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stringhe di ricerca, etichette e pausa per gruppo (0.7.2, prefisso `lbl_`): stesse chiavi e segnaposto in
 * inglese e italiano, tradotte, apostrofi validi per Android; il testo della tavola approvata resta quello.
 */
class LabelStringsTest {
    private val english = strings(File("src/main/res/values/strings.xml").readText())
    private val italian = strings(File("src/main/res/values-it/strings.xml").readText())

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }.filterKeys { it.startsWith("lbl_") }

    @Test
    fun `label keys are the same in both languages`() {
        assertTrue(english.size >= 30)
        assertEquals(english.keys, italian.keys)
    }

    @Test
    fun `label strings carry the same placeholders in both languages`() {
        english.forEach { (key, text) ->
            assertEquals(PLACEHOLDER.findAll(text).map { it.value }.toList(), PLACEHOLDER.findAll(italian.getValue(key)).map { it.value }.toList(), key)
        }
    }

    @Test
    fun `label strings are translated, not copied`() {
        val sameOnPurpose = setOf("lbl_chip_count")
        english.forEach { (key, text) ->
            if (key !in sameOnPurpose) assertNotEquals(text, italian.getValue(key), key)
            assertTrue(text.isNotBlank() && italian.getValue(key).isNotBlank(), key)
        }
    }

    @Test
    fun `apostrophes are escaped as Android requires`() {
        (english + italian.mapKeys { "it:" + it.key }).forEach { (key, text) ->
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
        }
    }

    @Test
    fun `the italian copy is the one of the approved mockup`() {
        assertEquals("Cerca per nome, app o azione", italian.getValue("lbl_search_placeholder"))
        assertEquals("Tutte · %1\$d", italian.getValue("lbl_chip_all"))
        assertEquals("Etichetta «%1\$s»", italian.getValue("lbl_group_title"))
        assertEquals("%1\$d attive", italian.getValue("lbl_group_active_many"))
        assertEquals("%1\$d da approvare", italian.getValue("lbl_group_waiting"))
        assertEquals("Pausa gruppo", italian.getValue("lbl_group_pause"))
        assertEquals("Riprendi gruppo", italian.getValue("lbl_group_resume"))
        assertEquals("Pause group", english.getValue("lbl_group_pause"))
        assertEquals("Resume group", english.getValue("lbl_group_resume"))
        assertEquals("Etichette", italian.getValue("lbl_detail_title"))
        assertEquals("Labels", english.getValue("lbl_detail_title"))
        assertEquals(
            "Le etichette si scelgono nel dettaglio della regola. Non cambiano cosa fa la regola e non richiedono " +
                "una nuova approvazione.",
            italian.getValue("lbl_list_note"),
        )
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
