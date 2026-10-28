package dev.argus.ui.model

import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.ValidationIssue
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.ValidationMessages
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Blocco approvazione (0.7.0): stringhe `rda_ap_` in EN e IT e avviso «app non installata». */
class ApprovalStringsTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()

    private fun own(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }.filterKeys { it.startsWith("rda_ap_") }

    @Test
    fun `approval strings exist in both languages with the same placeholders and a real translation`() {
        val en = own(english)
        val it = own(italian)

        assertTrue(en.isNotEmpty())
        assertEquals(en.keys, it.keys)
        en.forEach { (key, text) ->
            assertEquals(
                PLACEHOLDER.findAll(text).map { m -> m.value }.toList(),
                PLACEHOLDER.findAll(it.getValue(key)).map { m -> m.value }.toList(),
                key,
            )
            assertNotEquals(text, it.getValue(key), key)
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(it.getValue(key)), "apostrofo non escapato in it:$key")
        }
    }

    @Test
    fun `the not installed warning names the package in both languages`() {
        val issue = ValidationIssue(Severity.WARNING, "app_not_installed", "diagnostica", detail = "com.youtube")

        val en = ValidationMessages.text(issue, RenderLanguage.EN)
        val it = ValidationMessages.text(issue, RenderLanguage.IT)

        assertTrue(en.endsWith("(com.youtube)"), en)
        assertTrue(it.endsWith("(com.youtube)"), it)
        assertNotEquals(en, it)
        assertTrue("install" in en.lowercase())
        assertTrue("installat" in it.lowercase())
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>([^<]*)</string>""")
        val PLACEHOLDER = Regex("""%(\d+\$)?[sd]""")
    }
}
