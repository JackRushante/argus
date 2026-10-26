package dev.argus.ui.model

import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.screens.shizukuOnboardingCopy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Shizuku troppo vecchio è uno stato vero (0.3.6): onboarding e Sistema lo dicono con le parole
 * giuste, e le stringhe nuove esistono in inglese e in italiano con gli stessi segnaposto.
 */
class ShizukuStatusCopyTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()

    @Test
    fun `an outdated Shizuku step asks to update and come back, in both languages`() {
        assertEquals(
            "Questa versione di Shizuku è troppo vecchia per Argus: aggiornala e torna qui",
            shizukuOnboardingCopy(ShizukuStatus.UNSUPPORTED, RenderLanguage.IT).first,
        )
        assertEquals(
            "This Shizuku version is too old for Argus: update it and come back",
            shizukuOnboardingCopy(ShizukuStatus.UNSUPPORTED, RenderLanguage.EN).first,
        )
    }

    @Test
    fun `an outdated Shizuku is not described as not running`() {
        listOf(RenderLanguage.IT, RenderLanguage.EN).forEach { language ->
            assertNotEquals(
                shizukuOnboardingCopy(ShizukuStatus.INSTALLED_NOT_RUNNING, language),
                shizukuOnboardingCopy(ShizukuStatus.UNSUPPORTED, language),
                language.name,
            )
        }
    }

    @Test
    fun `every Shizuku status has onboarding copy and an action unless it is already authorized`() {
        ShizukuStatus.entries.forEach { status ->
            val (body, cta) = shizukuOnboardingCopy(status, RenderLanguage.IT)
            assertTrue(body.isNotBlank(), status.name)
            if (status == ShizukuStatus.AUTHORIZED) {
                assertEquals(null, cta, status.name)
            } else {
                assertTrue(!cta.isNullOrBlank(), status.name)
            }
        }
    }

    @Test
    fun `the new Shizuku strings are exact in both languages`() {
        assertEquals("Shizuku is too old: update it", string(english, "settings_shizuku_unsupported"))
        assertEquals("Shizuku troppo vecchio: aggiornalo", string(italian, "settings_shizuku_unsupported"))
        assertEquals(
            "Running as root: rule commands run as root",
            string(english, "settings_shizuku_ok_root"),
        )
        assertEquals(
            "Attivo come root: i comandi delle regole girano come root",
            string(italian, "settings_shizuku_ok_root"),
        )
        assertEquals(
            "runs commands as root (Shizuku runs as root)",
            string(english, "rule_shell_privileges_root_label"),
        )
        assertEquals(
            "esegue comandi come root (Shizuku gira come root)",
            string(italian, "rule_shell_privileges_root_label"),
        )
        assertEquals(
            "runs commands with Shizuku privileges",
            string(english, "rule_shell_privileges_unknown_label"),
        )
        assertEquals(
            "esegue comandi con i privilegi di Shizuku",
            string(italian, "rule_shell_privileges_unknown_label"),
        )
    }

    @Test
    fun `the shell label for plain shell privileges keeps the UID 2000 copy`() {
        assertEquals(
            "runs commands with shell privileges (UID 2000)",
            string(english, "rule_shell_privileges_label"),
        )
        assertEquals(
            "esegue comandi con privilegi shell (UID 2000)",
            string(italian, "rule_shell_privileges_label"),
        )
    }

    @Test
    fun `english and italian ui resources have the same keys and placeholders`() {
        val en = strings(english)
        val translated = strings(italian)

        assertEquals(en.keys, translated.keys)
        en.forEach { (key, text) ->
            assertEquals(placeholders(text), placeholders(translated.getValue(key)), key)
        }
    }

    private fun string(xml: String, name: String): String =
        requireNotNull(strings(xml)[name]) { "stringa mancante: $name" }

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%(\d+\$)?[sd]""")
    }
}
