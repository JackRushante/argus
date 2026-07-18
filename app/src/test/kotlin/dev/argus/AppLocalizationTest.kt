package dev.argus

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppLocalizationTest {
    private val defaultStrings = File("src/main/res/values/strings.xml").readText()
    private val italianStrings = File("src/main/res/values-it/strings.xml").readText()
    private val navSource = File("src/main/kotlin/dev/argus/nav/ArgusNavHost.kt").readText()

    @Test
    fun `default english and italian resources expose the same keys`() {
        fun keys(xml: String): Set<String> = STRING_NAME.findAll(xml)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(keys(defaultStrings), keys(italianStrings))
    }

    @Test
    fun `navigation and dialogs do not regress to hardcoded italian copy`() {
        listOf(
            "Automazioni",
            "Sistema",
            "Impostazione di sistema non disponibile.",
            "Aggiungi alla whitelist",
            "Regola non disponibile",
            "Inserimento manuale",
        ).forEach { literal ->
            assertFalse(literal in navSource, "testo utente hardcoded nel modulo app: $literal")
        }
    }

    private companion object {
        val STRING_NAME = Regex("""<string\s+name="([^"]+)"""")
    }
}
