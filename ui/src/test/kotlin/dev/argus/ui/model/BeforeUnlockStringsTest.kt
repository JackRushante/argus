package dev.argus.ui.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** «Attiva anche prima dello sblocco» (0.8, prefix `before_unlock_`): both languages, and the weaker protection said. */
class BeforeUnlockStringsTest {
    private val english = strings(File("src/main/res/values/strings.xml").readText())
    private val italian = strings(File("src/main/res/values-it/strings.xml").readText())

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }.filterKeys { it.startsWith("before_unlock_") }

    @Test
    fun `the keys are the same in both languages and translated`() {
        assertEquals(setOf("before_unlock_title", "before_unlock_body", "before_unlock_weaker"), english.keys)
        assertEquals(english.keys, italian.keys)
        english.forEach { (key, text) -> assertNotEquals(text, italian.getValue(key), key) }
        (english + italian.mapKeys { "it:" + it.key }).forEach { (key, text) ->
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "unescaped apostrophe in $key")
        }
    }

    @Test
    fun `the option has the approved title and says plainly that the protection is weaker`() {
        assertEquals("Attiva anche prima dello sblocco", italian.getValue("before_unlock_title"))
        assertTrue("weaker protection" in english.getValue("before_unlock_weaker"))
        assertTrue("protezione più debole" in italian.getValue("before_unlock_weaker"))
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
