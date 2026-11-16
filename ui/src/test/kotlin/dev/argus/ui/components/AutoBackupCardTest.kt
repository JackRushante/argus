package dev.argus.ui.components

import dev.argus.ui.model.AutoBackupUi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backup automatico e import cifrato (0.7.2): validazione della passphrase, stringhe e segreti. */
class AutoBackupCardTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()
    private val cardSource = File("src/main/kotlin/dev/argus/ui/components/AutoBackupCard.kt").readText()
    private val sectionSource = File("src/main/kotlin/dev/argus/ui/components/RulesBackupSection.kt").readText()

    @Test
    fun `a passphrase needs 8 characters and the same text twice`() {
        assertEquals(PassphraseProblem.TOO_SHORT, AutoBackupPassphrase.problem("1234567", "1234567"))
        assertEquals(PassphraseProblem.TOO_SHORT, AutoBackupPassphrase.problem("", ""))
        assertEquals(PassphraseProblem.MISMATCH, AutoBackupPassphrase.problem("12345678", "12345679"))
        assertNull(AutoBackupPassphrase.problem("12345678", "12345678"))
        // Le emoji contano una volta, come le vede l'utente.
        val fourLocks = "🔒".repeat(4)
        assertEquals(PassphraseProblem.TOO_SHORT, AutoBackupPassphrase.problem(fourLocks, fourLocks))
    }

    @Test
    fun `the card can be switched on and run only with a folder and a passphrase`() {
        assertFalse(AutoBackupUi().ready)
        assertFalse(AutoBackupUi(folderLabel = "Backup").ready)
        assertFalse(AutoBackupUi(passphraseSet = true).ready)
        assertTrue(AutoBackupUi(folderLabel = "Backup", passphraseSet = true).ready)
    }

    @Test
    fun `passphrase fields are never saved across recreation`() {
        listOf(cardSource, sectionSource).forEach { source ->
            assertFalse(Regex("""rememberSaveable[^\n]*passphrase""", RegexOption.IGNORE_CASE).containsMatchIn(source))
            assertFalse(Regex("""var\s+(passphrase|confirmation)\s+by\s+rememberSaveable""").containsMatchIn(source))
        }
        assertTrue("PasswordVisualTransformation()" in cardSource)
        assertTrue("PasswordVisualTransformation()" in sectionSource)
    }

    @Test
    fun `the new backup strings exist in both languages, translated`() {
        val en = strings(english).filterKeys(::isNewKey)
        val it = strings(italian).filterKeys(::isNewKey)

        assertTrue(en.size >= 30)
        assertEquals(en.keys, it.keys)
        val sameOnPurpose = setOf("backup_auto_passphrase_label")
        en.forEach { (key, text) ->
            if (key !in sameOnPurpose) assertNotEquals(text, it.getValue(key), key)
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(it.getValue(key)), "apostrofo non escapato in it:$key")
        }
    }

    @Test
    fun `the unrecoverable passphrase is said plainly`() {
        val en = strings(english).getValue("backup_passphrase_sheet_body")
        val it = strings(italian).getValue("backup_passphrase_sheet_body")
        assertTrue("nobody can recover it" in en)
        assertTrue("nessuno può recuperarla" in it)
    }

    private fun isNewKey(key: String) = key.startsWith("backup_auto_") || key.startsWith("backup_passphrase_") ||
        key.startsWith("backup_import_encrypted") || key == "backup_import_passphrase_field" || key == "backup_import_open"

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
