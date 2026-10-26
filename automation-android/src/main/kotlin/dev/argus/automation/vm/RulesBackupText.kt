package dev.argus.automation.vm

import dev.argus.data.backup.BackupFailure
import dev.argus.data.backup.ExportOutcome
import dev.argus.data.backup.ImportSummary
import dev.argus.data.backup.RuleDiscardReason
import dev.argus.data.backup.RulesBackupFormat
import dev.argus.ui.presentation.RenderLanguage

/**
 * Testi del backup delle regole (0.4.0, F1), nella lingua dell'utente. Nessun contenuto del file
 * entra nei testi: solo conteggi e motivi di un vocabolario chiuso.
 */
internal object RulesBackupText {
    fun exportDone(done: ExportOutcome.Done, language: RenderLanguage): String {
        val exported = language.pick(
            if (done.exported == 1) "1 rule exported." else "${done.exported} rules exported.",
            if (done.exported == 1) "1 regola esportata." else "${done.exported} regole esportate.",
        )
        if (done.skipped <= 0) return exported
        val skipped = language.pick(
            if (done.skipped == 1) "1 unreadable rule was left out."
            else "${done.skipped} unreadable rules were left out.",
            if (done.skipped == 1) "1 regola illeggibile è stata lasciata fuori."
            else "${done.skipped} regole illeggibili sono state lasciate fuori.",
        )
        return "$exported $skipped"
    }

    /** «N regole importate da approvare; M scartate (motivo).» */
    fun importSummary(summary: ImportSummary, language: RenderLanguage): String {
        val imported = language.pick(
            when (summary.imported) {
                0 -> "No rules imported"
                1 -> "1 rule imported, to approve"
                else -> "${summary.imported} rules imported, to approve"
            },
            when (summary.imported) {
                0 -> "Nessuna regola importata"
                1 -> "1 regola importata da approvare"
                else -> "${summary.imported} regole importate da approvare"
            },
        )
        val total = summary.discardedTotal
        if (total == 0) return "$imported."
        val discarded = language.pick(
            "$total discarded (${reasons(summary, language)})",
            if (total == 1) "1 scartata (${reasons(summary, language)})"
            else "$total scartate (${reasons(summary, language)})",
        )
        return "$imported; $discarded."
    }

    private fun reasons(summary: ImportSummary, language: RenderLanguage): String {
        val entries = summary.discarded.filterValues { it > 0 }.entries.sortedBy { it.key.ordinal }
        if (entries.size == 1) return reason(entries.single().key, language)
        return entries.joinToString(", ") { (why, count) -> "${reason(why, language)}: $count" }
    }

    private fun reason(reason: RuleDiscardReason, language: RenderLanguage): String = when (reason) {
        RuleDiscardReason.MALFORMED_ENTRY -> language.pick("malformed entry", "voce malformata")
        RuleDiscardReason.INVALID_RULE -> language.pick("not a valid rule", "regola non valida")
        RuleDiscardReason.RULE_TOO_LARGE -> language.pick("too large", "troppo grande")
        RuleDiscardReason.NOT_SAVED -> language.pick("could not be saved", "non salvata")
    }

    fun failure(failure: BackupFailure, language: RenderLanguage): String = when (failure) {
        BackupFailure.NOTHING_TO_EXPORT -> language.pick(
            "There are no rules to export.",
            "Non ci sono regole da esportare.",
        )
        BackupFailure.EXPORT_TOO_LARGE -> language.pick(
            "Too many rules for one file (limit ${RulesBackupFormat.MAX_RULES} rules or 2 MB).",
            "Troppe regole per un solo file (limite ${RulesBackupFormat.MAX_RULES} regole o 2 MB).",
        )
        BackupFailure.WRITE_FAILED -> language.pick(
            "Unable to write the file.",
            "Impossibile scrivere il file.",
        )
        BackupFailure.READ_FAILED -> language.pick(
            "Unable to read the file.",
            "Impossibile leggere il file.",
        )
        BackupFailure.FILE_TOO_LARGE -> language.pick(
            "The file is too large (limit 2 MB).",
            "Il file è troppo grande (limite 2 MB).",
        )
        BackupFailure.NOT_JSON -> language.pick(
            "The file is damaged or is not a JSON file.",
            "Il file è danneggiato o non è un file JSON.",
        )
        BackupFailure.WRONG_FORMAT -> language.pick(
            "This is not an Argus rules export.",
            "Non è un'esportazione di regole Argus.",
        )
        BackupFailure.UNSUPPORTED_VERSION -> language.pick(
            "This file uses a format version that Argus does not support.",
            "Questo file usa una versione del formato che Argus non supporta.",
        )
        BackupFailure.TOO_MANY_RULES -> language.pick(
            "The file has more than ${RulesBackupFormat.MAX_RULES} rules.",
            "Il file ha più di ${RulesBackupFormat.MAX_RULES} regole.",
        )
        BackupFailure.NO_RULES -> language.pick(
            "The file contains no rules.",
            "Il file non contiene regole.",
        )
        BackupFailure.SAVE_FAILED -> language.pick(
            "Unable to save the imported rules: nothing was added.",
            "Impossibile salvare le regole importate: non è stato aggiunto nulla.",
        )
    }
}
