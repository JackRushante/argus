package dev.argus.ui.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Riga «Sveglie esatte» in Sistema (0.3.6): da Android 14 il permesso e' negato di default, quindi
 * il testo deve dire cosa si perde senza di esso, in entrambe le lingue e senza segnaposto.
 */
class ExactAlarmsCopyTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()

    @Test
    fun `the exact alarms row copy is exact in both languages`() {
        assertEquals("Exact alarms", string(english, "settings_exact_alarms_title"))
        assertEquals("Sveglie esatte", string(italian, "settings_exact_alarms_title"))
        assertEquals(
            "granted: time rules and long waits start on time",
            string(english, "settings_exact_alarms_ok"),
        )
        assertEquals(
            "concesse: le regole a orario e le attese lunghe partono puntuali",
            string(italian, "settings_exact_alarms_ok"),
        )
        assertEquals(
            "not granted: time rules may start 30-40 minutes late and long waits may slip",
            string(english, "settings_exact_alarms_warn"),
        )
        assertEquals(
            "non concesse: le regole a orario possono partire anche 30-40 minuti in ritardo e le attese lunghe slittare",
            string(italian, "settings_exact_alarms_warn"),
        )
    }

    @Test
    fun `the exact alarms strings have no placeholders to drift apart`() {
        listOf("settings_exact_alarms_title", "settings_exact_alarms_ok", "settings_exact_alarms_warn")
            .forEach { name ->
                assertTrue('%' !in string(english, name), "segnaposto in inglese: $name")
                assertTrue('%' !in string(italian, name), "segnaposto in italiano: $name")
            }
    }

    private fun string(xml: String, name: String): String =
        requireNotNull(STRING.findAll(xml).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)) {
            "stringa mancante: $name"
        }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
