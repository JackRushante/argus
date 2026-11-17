package dev.argus.ui.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stringhe di Sistema › Modalità (0.8, prefissi `modes_` e `settings_modes_`): stesse chiavi e segnaposto in
 * inglese e italiano, tradotte, apostrofi validi per Android; il testo della tavola approvata resta quello.
 */
class ModesStringsTest {
    private val englishXml = File("src/main/res/values/strings.xml").readText()
    private val italianXml = File("src/main/res/values-it/strings.xml").readText()
    private val english = strings(englishXml)
    private val italian = strings(italianXml)

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
            .filterKeys { it.startsWith("modes_") || it.startsWith("settings_modes_") }

    private fun plural(xml: String, name: String, quantity: String): String =
        Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
            .let { Regex("""<item quantity="$quantity">(.*?)</item>""").find(it)!!.groupValues[1] }

    @Test
    fun `mode keys are the same in both languages`() {
        assertTrue(english.size >= 30)
        assertEquals(english.keys, italian.keys)
    }

    @Test
    fun `mode strings carry the same placeholders in both languages`() {
        english.forEach { (key, text) ->
            assertEquals(
                PLACEHOLDER.findAll(text).map { it.value }.toList(),
                PLACEHOLDER.findAll(italian.getValue(key)).map { it.value }.toList(),
                key,
            )
        }
    }

    @Test
    fun `mode strings are translated, not copied`() {
        english.forEach { (key, text) ->
            assertNotEquals(text, italian.getValue(key), key)
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
        assertEquals("Modalità", italian.getValue("modes_title"))
        assertEquals("Modalità attiva", italian.getValue("modes_active_label"))
        assertEquals("Le tue modalità", italian.getValue("modes_list_label"))
        assertEquals("Aggiungi modalità", italian.getValue("modes_add"))
        assertEquals("Variabili salvate", italian.getValue("modes_values_label"))
        assertEquals("Puoi cambiarla anche dal riquadro rapido e dal widget.", italian.getValue("modes_switch_hint"))
        assertEquals("Modifica la modalità %1\$s", italian.getValue("modes_edit_cd"))
        assertEquals(
            "Una regola legge o cambia modalità e variabili solo se è scritto nella regola che hai approvato. " +
                "Tocca una variabile per correggerne il valore o eliminarla.",
            italian.getValue("modes_footer"),
        )
        assertEquals("%1\$d regole la usano come condizione", plural(italianXml, "modes_condition_rules", "other"))
        assertEquals("%1\$d rules use it as a condition", plural(englishXml, "modes_condition_rules", "other"))
    }

    private companion object {
        val STRING = Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""")
        val PLACEHOLDER = Regex("""%\d\$[sd]""")
    }
}
