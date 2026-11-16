package dev.argus.automation.vm

import android.net.Uri
import dev.argus.data.backup.BackupFailure
import dev.argus.data.backup.DocumentRead
import dev.argus.data.backup.ExportOutcome
import dev.argus.data.backup.ImportOutcome
import dev.argus.data.backup.ImportPreview
import dev.argus.data.backup.RuleDiscardReason
import dev.argus.data.backup.RulesBackup
import dev.argus.data.backup.RulesBackupFormat
import dev.argus.ui.model.TextImportPreview
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ImportPreviewsTest {
    @Test
    fun `the preview title counts the rules and gives the size`() {
        val one = ImportPreviewText.of(ImportPreview.Ready(listOf("A"), emptyMap(), 820), RenderLanguage.IT)
        assertEquals("1 regola · 820 byte", one.title)
        assertNull(one.moreText)
        assertNull(one.discardedText)

        val many = ImportPreviewText.of(ImportPreview.Ready(listOf("A", "B"), emptyMap(), 1_434), RenderLanguage.EN)
        assertEquals("2 rules · 1.4 KB", many.title)
        assertEquals("2 regole · 1,4 KB", ImportPreviewText.of(ImportPreview.Ready(listOf("A", "B"), emptyMap(), 1_434), RenderLanguage.IT).title)

        val none = ImportPreviewText.of(ImportPreview.Ready(emptyList(), mapOf(RuleDiscardReason.INVALID_RULE to 2), 50), RenderLanguage.IT)
        assertEquals("Nessuna regola da importare · 50 byte", none.title)
        assertEquals(false, none.canImport)
        assertEquals("2 voci verranno scartate (regola non valida).", none.discardedText)
    }

    @Test
    fun `only the first names are listed, the others are counted`() {
        val names = (1..25).map { "Regola $it" }
        val preview = ImportPreviewText.of(ImportPreview.Ready(names, emptyMap(), 2 * 1024 * 1024), RenderLanguage.IT)

        assertEquals(ImportPreviewText.MAX_NAMES, preview.ruleNames.size)
        assertEquals("e altre 5", preview.moreText)
        assertEquals("25 regole · 2,0 MB", preview.title)
    }

    @Test
    fun `a text that is not a rule is called a text, not a damaged file`() {
        assertEquals(
            "This text is not an Argus rule or rules backup.",
            ImportPreviewText.textFailure(BackupFailure.NOT_JSON, RenderLanguage.EN),
        )
        assertEquals(
            "Il testo è troppo grande (limite 2 MB).",
            ImportPreviewText.textFailure(BackupFailure.FILE_TOO_LARGE, RenderLanguage.IT),
        )
        assertEquals(
            RulesBackupText.failure(BackupFailure.TOO_MANY_RULES, RenderLanguage.IT),
            ImportPreviewText.textFailure(BackupFailure.TOO_MANY_RULES, RenderLanguage.IT),
        )
    }

    @Test
    fun `the live preview follows the text and refuses too large text before reading it`() = runTest {
        val backup = PreviewOnlyBackup()
        val previewer = TextImportPreviewer(
            backup,
            backgroundScope,
            { RenderLanguage.EN },
            UnconfinedTestDispatcher(testScheduler),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TextImportPreview.Empty, previewer.preview.value)

        previewer.onTextChange("{\"format\":\"argus-rules\"}")
        testScheduler.advanceUntilIdle()
        val ready = assertIs<TextImportPreview.Ready>(previewer.preview.value)
        assertEquals(listOf("Notte"), ready.preview.ruleNames)
        assertEquals(1, backup.previews)

        previewer.onTextChange("x".repeat(RulesBackupFormat.MAX_FILE_BYTES + 1))
        testScheduler.advanceUntilIdle()
        assertEquals(TextImportPreview.Invalid("The text is too large (limit 2 MB)."), previewer.preview.value)
        assertEquals(1, backup.previews, "il testo troppo grande non arriva alla lettura")

        previewer.clear()
        testScheduler.advanceUntilIdle()
        assertEquals(TextImportPreview.Empty, previewer.preview.value)
    }

    private class PreviewOnlyBackup : RulesBackup {
        var previews = 0

        override fun preview(bytes: ByteArray): ImportPreview {
            previews++
            return ImportPreview.Ready(listOf("Notte"), emptyMap(), bytes.size)
        }

        override suspend fun export(destination: Uri, stripPersonalData: Boolean): ExportOutcome = error("non usato")
        override suspend fun import(source: Uri): ImportOutcome = error("non usato")
        override suspend fun read(source: Uri): DocumentRead = error("non usato")
        override suspend fun importBytes(bytes: ByteArray): ImportOutcome = error("non usato")
    }
}
