package dev.argus.automation.vm

import dev.argus.data.backup.BackupFailure
import dev.argus.data.backup.ImportOutcome
import dev.argus.data.backup.ImportPreview
import dev.argus.data.backup.RulesBackup
import dev.argus.data.backup.RulesBackupFormat
import dev.argus.ui.model.ImportPreviewUi
import dev.argus.ui.model.TextImportPreview
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Testi dell'anteprima di un import (0.7.2), nella lingua dell'utente. Dal contenuto ricevuto entrano solo i
 * nomi delle regole, già ripuliti dal servizio per la visualizzazione; il resto sono conteggi e motivi.
 */
internal object ImportPreviewText {
    /** Quanti nomi si mostrano; gli altri si contano («e altre 5»). */
    const val MAX_NAMES = 20

    fun of(preview: ImportPreview.Ready, language: RenderLanguage): ImportPreviewUi {
        val count = preview.ruleNames.size
        val rules = language.pick(
            when (count) {
                0 -> "No rules to import"
                1 -> "1 rule"
                else -> "$count rules"
            },
            when (count) {
                0 -> "Nessuna regola da importare"
                1 -> "1 regola"
                else -> "$count regole"
            },
        )
        val hidden = count - MAX_NAMES
        return ImportPreviewUi(
            title = "$rules · ${sizeLabel(preview.sizeBytes, language)}",
            ruleNames = preview.ruleNames.take(MAX_NAMES),
            moreText = if (hidden > 0) language.pick("and $hidden more", "e altre $hidden") else null,
            discardedText = RulesBackupText.previewDiscarded(preview.discarded, language),
        )
    }

    /** «820 byte», «1.4 KB» / «1,4 KB», «2.0 MB». */
    fun sizeLabel(bytes: Int, language: RenderLanguage): String {
        val locale = if (language == RenderLanguage.IT) Locale.ITALIAN else Locale.ENGLISH
        return when {
            bytes < 1024 -> language.pick(if (bytes == 1) "1 byte" else "$bytes bytes", "$bytes byte")
            bytes < 1024 * 1024 -> String.format(locale, "%.1f KB", bytes / 1024.0)
            else -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /** Perché un testo non si importa. Un testo che non è una regola si dice tale, non «file danneggiato». */
    fun textFailure(failure: BackupFailure, language: RenderLanguage): String = when (failure) {
        BackupFailure.NOT_JSON, BackupFailure.WRONG_FORMAT -> language.pick(
            "This text is not an Argus rule or rules backup.",
            "Questo testo non è una regola Argus né un backup delle regole.",
        )
        BackupFailure.FILE_TOO_LARGE -> language.pick(
            "The text is too large (limit 2 MB).",
            "Il testo è troppo grande (limite 2 MB).",
        )
        else -> RulesBackupText.failure(failure, language)
    }

    /** L'esito di un import come (testo, bozze create); nessuna bozza = è un errore da mostrare come tale. */
    fun outcome(outcome: ImportOutcome, language: RenderLanguage, text: Boolean): Pair<String, Int> = when (outcome) {
        is ImportOutcome.Done -> RulesBackupText.importSummary(outcome.summary, language) to outcome.summary.imported
        is ImportOutcome.Failed ->
            (if (text) textFailure(outcome.failure, language) else RulesBackupText.failure(outcome.failure, language)) to 0
    }
}

/**
 * Anteprima dal vivo di «Importa da testo» (0.7.2): a ogni cambio del testo, sullo sfondo, cosa importerebbe.
 * Un testo più recente annulla l'anteprima di quello prima. Il testo non lascia mai la memoria.
 */
internal class TextImportPreviewer(
    private val backup: RulesBackup,
    scope: CoroutineScope,
    private val language: () -> RenderLanguage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val texts = MutableStateFlow("")
    private val mutablePreview = MutableStateFlow<TextImportPreview>(TextImportPreview.Empty)
    val preview: StateFlow<TextImportPreview> = mutablePreview.asStateFlow()

    init {
        scope.launch {
            texts.collectLatest { text ->
                if (text.isBlank()) {
                    mutablePreview.value = TextImportPreview.Empty
                    return@collectLatest
                }
                mutablePreview.value = TextImportPreview.Checking
                mutablePreview.value = try {
                    withContext(dispatcher) { previewOf(text) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    TextImportPreview.Invalid(ImportPreviewText.textFailure(BackupFailure.NOT_JSON, language()))
                }
            }
        }
    }

    fun onTextChange(text: String) {
        texts.value = text
    }

    fun clear() {
        texts.value = ""
    }

    private fun previewOf(text: String): TextImportPreview {
        // Il limite prima di qualsiasi lettura del contenuto.
        val bytes = RulesBackupFormat.utf8WithinCap(text)
            ?: return TextImportPreview.Invalid(ImportPreviewText.textFailure(BackupFailure.FILE_TOO_LARGE, language()))
        return when (val preview = backup.preview(bytes)) {
            is ImportPreview.Rejected -> TextImportPreview.Invalid(ImportPreviewText.textFailure(preview.failure, language()))
            is ImportPreview.Ready -> TextImportPreview.Ready(ImportPreviewText.of(preview, language()))
        }
    }
}
