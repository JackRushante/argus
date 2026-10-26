package dev.argus.ui.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stringhe del blocco UI 0.4.0 (prefisso `ui40_`): stesse chiavi in inglese e italiano, stessi segnaposto,
 * nessun testo lasciato in una lingua sola. In piu', le descrizioni per TalkBack di B50 non sono piu'
 * scritte nel codice.
 */
class Ui40StringsTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()
    private val settingsSource = File("src/main/kotlin/dev/argus/ui/screens/SettingsScreen.kt").readText()
    private val listSource = File("src/main/kotlin/dev/argus/ui/screens/AutomationListScreen.kt").readText()

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private val englishUi40 = strings(english).filterKeys { it.startsWith("ui40_") }
    private val italianUi40 = strings(italian).filterKeys { it.startsWith("ui40_") }

    @Test
    fun `ui40 keys are the same in both languages`() {
        assertTrue(englishUi40.isNotEmpty())
        assertEquals(englishUi40.keys, italianUi40.keys)
    }

    @Test
    fun `ui40 strings carry the same placeholders in both languages`() {
        englishUi40.forEach { (key, text) ->
            assertEquals(placeholders(text), placeholders(italianUi40.getValue(key)), key)
        }
    }

    @Test
    fun `ui40 strings are translated, not copied`() {
        // «Shizuku» e' un nome proprio: resta uguale.
        val sameOnPurpose = setOf("ui40_example_shizuku_tag")
        englishUi40.forEach { (key, text) ->
            if (key !in sameOnPurpose) assertNotEquals(text, italianUi40.getValue(key), key)
            assertTrue(text.isNotBlank() && italianUi40.getValue(key).isNotBlank(), key)
        }
    }

    @Test
    fun `apostrophes are escaped as Android requires`() {
        (englishUi40 + italianUi40.mapKeys { "it:" + it.key }).forEach { (key, text) ->
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
        }
    }

    @Test
    fun `the reasoning token count takes the already grouped number`() {
        // B49: il numero arriva formattato col separatore delle migliaia, quindi e' un testo (%s).
        assertTrue("%1\$s" in strings(english).getValue("settings_budget_provider_reasoning"))
        assertTrue("%1\$s" in strings(italian).getValue("settings_budget_provider_reasoning"))
    }

    // ---- B50: accessibilita' ------------------------------------------------------------------------

    @Test
    fun `the health status description is translated and is no longer a literal in the code`() {
        assertEquals("Status: OK", englishUi40.getValue("ui40_health_ok_cd"))
        assertEquals("Stato: a posto", italianUi40.getValue("ui40_health_ok_cd"))
        assertFalse(
            Regex("""contentDescription\s*=\s*"ok"""").containsMatchIn(settingsSource),
            "TalkBack leggeva «ok» in inglese anche in italiano",
        )
        assertTrue("R.string.ui40_health_ok_cd" in settingsSource)
    }

    @Test
    fun `the warnings mark in the list is described for TalkBack`() {
        assertEquals("Has warnings", englishUi40.getValue("ui40_list_has_warnings"))
        assertEquals("Ha avvisi", italianUi40.getValue("ui40_list_has_warnings"))
        assertTrue("R.string.ui40_list_has_warnings" in listSource)
        // Non e' piu' un pallino solo colorato.
        assertFalse("CircleShape" in listSource)
    }

    @Test
    fun `the filtered empty list says it is the filter, not that the rules are gone`() {
        assertEquals("No rules with this filter", englishUi40.getValue("ui40_list_filter_empty_title"))
        assertEquals("Nessuna regola con questo filtro", italianUi40.getValue("ui40_list_filter_empty_title"))
        assertEquals("Nessuna automazione", strings(italian).getValue("list_empty_title"))
    }

    @Test
    fun `an empty stats window says the 30 days sentence of the brief`() {
        assertEquals("Nessuno scatto negli ultimi %1\$d giorni", italianUi40.getValue("ui40_stats_none"))
        assertEquals("No firings in the last %1\$d days", englishUi40.getValue("ui40_stats_none"))
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
