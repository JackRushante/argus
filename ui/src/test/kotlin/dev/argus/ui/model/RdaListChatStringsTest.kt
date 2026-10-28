package dev.argus.ui.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stringhe del blocco lista e chat del redesign A (prefisso `rda_lc_`): stesse chiavi in inglese e italiano,
 * stessi segnaposto, niente testo lasciato in una lingua sola. In piu': un valore assente si scrive «n/d» e
 * non e' un testo scritto nel codice.
 */
class RdaListChatStringsTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()
    private val listSource = File("src/main/kotlin/dev/argus/ui/screens/AutomationListScreen.kt").readText()
    private val chatSource = File("src/main/kotlin/dev/argus/ui/screens/ChatScreen.kt").readText()

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private val englishLc = strings(english).filterKeys { it.startsWith("rda_lc_") }
    private val italianLc = strings(italian).filterKeys { it.startsWith("rda_lc_") }

    @Test
    fun `list and chat keys are the same in both languages`() {
        assertTrue(englishLc.isNotEmpty())
        assertEquals(englishLc.keys, italianLc.keys)
    }

    @Test
    fun `list and chat strings carry the same placeholders in both languages`() {
        englishLc.forEach { (key, text) ->
            assertEquals(placeholders(text), placeholders(italianLc.getValue(key)), key)
        }
    }

    @Test
    fun `list and chat strings are translated, not copied`() {
        // Numeri e unita' di misura dell'asse dei tempi sono uguali in entrambe le lingue.
        val sameOnPurpose = setOf("rda_lc_wait_axis_start", "rda_lc_wait_axis_band", "rda_lc_wait_axis_end")
        englishLc.forEach { (key, text) ->
            if (key !in sameOnPurpose) assertNotEquals(text, italianLc.getValue(key), key)
            assertTrue(text.isNotBlank() && italianLc.getValue(key).isNotBlank(), key)
        }
    }

    @Test
    fun `apostrophes are escaped as Android requires`() {
        (englishLc + italianLc.mapKeys { "it:" + it.key }).forEach { (key, text) ->
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
        }
    }

    @Test
    fun `an absent value is written n slash d in italian and never as a text in the code`() {
        assertEquals("n/d", italianLc.getValue("rda_lc_na"))
        assertEquals("n/a", englishLc.getValue("rda_lc_na"))
        assertFalse("\"n/d\"" in listSource, "«n/d» e' una risorsa, non un testo nel codice")
        assertFalse("\"n/a\"" in listSource)
    }

    @Test
    fun `the chat and the list read their texts from resources, with no Italian literal left in the code`() {
        listOf("Prossimo scatto", "Nessun errore", "Rivedi e approva", "Argus sta pensando", "Annulla").forEach { literal ->
            assertFalse("\"$literal" in listSource, "testo scritto nel codice della lista: $literal")
            assertFalse("\"$literal" in chatSource, "testo scritto nel codice della chat: $literal")
        }
    }

    @Test
    fun `the singular and the plural of the counted lines differ`() {
        assertEquals("1 avviso da leggere nel dettaglio", italianLc.getValue("rda_lc_draft_warning_one"))
        assertTrue("%1\$d" in italianLc.getValue("rda_lc_draft_warning_many"))
        assertEquals("1 errore", italianLc.getValue("rda_lc_today_one"))
        assertTrue("%1\$d" in italianLc.getValue("rda_lc_today_many"))
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
