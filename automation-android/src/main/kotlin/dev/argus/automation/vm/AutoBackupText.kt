package dev.argus.automation.vm

import dev.argus.automation.backup.AutoBackupSnapshot
import dev.argus.data.backup.AutoBackupFailure
import dev.argus.data.backup.AutoBackupFrequency
import dev.argus.data.backup.AutoBackupLastRun
import dev.argus.data.backup.RulesBackupFormat
import dev.argus.ui.model.AutoBackupFrequencyUi
import dev.argus.ui.model.AutoBackupUi
import dev.argus.ui.presentation.RenderLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Testi della card «Backup automatico» (0.7.2), nella lingua dell'utente. Solo conteggi, orari e motivi
 * di un vocabolario chiuso: niente nomi di file, URI o segreti.
 */
internal object AutoBackupText {
    fun ui(snapshot: AutoBackupSnapshot, nowMillis: Long, zone: ZoneId, language: RenderLanguage): AutoBackupUi {
        val state = snapshot.state
        return AutoBackupUi(
            enabled = state.enabled,
            folderLabel = state.folderUri?.let { state.folderLabel ?: "" },
            frequency = when (state.frequency) {
                AutoBackupFrequency.DAILY -> AutoBackupFrequencyUi.DAILY
                AutoBackupFrequency.WEEKLY -> AutoBackupFrequencyUi.WEEKLY
            },
            passphraseSet = state.keyConfigured,
            lastRun = state.lastRun?.let { lastRun(it, nowMillis, zone, language) },
            lastRunFailed = state.lastRun is AutoBackupLastRun.Failed,
            running = snapshot.running,
            settingPassphrase = snapshot.settingPassphrase,
        )
    }

    /** «Ultimo backup: ieri 03:10 · 12 regole», o il motivo del fallimento in parole semplici. */
    fun lastRun(run: AutoBackupLastRun, nowMillis: Long, zone: ZoneId, language: RenderLanguage): String {
        val time = whenText(run.atMillis, nowMillis, zone, language)
        return when (run) {
            is AutoBackupLastRun.Written -> language.pick(
                "Last backup: $time · ${if (run.rules == 1) "1 rule" else "${run.rules} rules"}",
                "Ultimo backup: $time · ${if (run.rules == 1) "1 regola" else "${run.rules} regole"}",
            )
            is AutoBackupLastRun.NothingToBackUp -> language.pick(
                "Last check: $time · no rules to back up yet",
                "Ultimo controllo: $time · ancora nessuna regola da salvare",
            )
            is AutoBackupLastRun.Failed -> language.pick(
                "Last backup failed ($time): ${failure(run.failure, language)}",
                "Ultimo backup non riuscito ($time): ${failure(run.failure, language)}",
            )
        }
    }

    fun failure(failure: AutoBackupFailure, language: RenderLanguage): String = when (failure) {
        AutoBackupFailure.FOLDER_NOT_SET -> language.pick(
            "no folder is chosen.",
            "nessuna cartella scelta.",
        )
        AutoBackupFailure.FOLDER_UNREACHABLE -> language.pick(
            "the folder cannot be reached (deleted, or access removed). Argus tries again later; if it keeps failing, choose the folder again.",
            "la cartella non è raggiungibile (cancellata, o accesso tolto). Argus riprova più tardi; se continua, scegli di nuovo la cartella.",
        )
        AutoBackupFailure.KEY_UNAVAILABLE -> language.pick(
            "the saved key can no longer be read. Set the passphrase again.",
            "la chiave salvata non si legge più. Imposta di nuovo la passphrase.",
        )
        AutoBackupFailure.READ_FAILED -> language.pick(
            "the rules could not be read. Argus tries again later.",
            "non è stato possibile leggere le regole. Argus riprova più tardi.",
        )
        AutoBackupFailure.TOO_LARGE -> language.pick(
            "too many rules for one file (limit ${RulesBackupFormat.MAX_RULES} rules or 2 MB).",
            "troppe regole per un solo file (limite ${RulesBackupFormat.MAX_RULES} regole o 2 MB).",
        )
        AutoBackupFailure.WRITE_FAILED -> language.pick(
            "the file could not be written in the folder (full, or read-only?). Argus tries again later.",
            "non è stato possibile scrivere il file nella cartella (piena, o di sola lettura?). Argus riprova più tardi.",
        )
    }

    /** «oggi 03:10», «ieri 03:10», altrimenti «12 nov 03:10» / «Nov 12 03:10». */
    fun whenText(atMillis: Long, nowMillis: Long, zone: ZoneId, language: RenderLanguage): String {
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val clock = TIME.format(at)
        return when (ChronoUnit.DAYS.between(at.toLocalDate(), today)) {
            0L -> language.pick("today $clock", "oggi $clock")
            1L -> language.pick("yesterday $clock", "ieri $clock")
            else -> language.pick(
                "${DAY_EN.format(at)} $clock",
                "${DAY_IT.format(at)} $clock",
            )
        }
    }

    fun folderRefused(language: RenderLanguage): String = language.pick(
        "Argus cannot use this folder: choose another one.",
        "Argus non può usare questa cartella: scegline un'altra.",
    )

    fun passphraseSaved(language: RenderLanguage): String = language.pick(
        "Passphrase saved. Keep it somewhere safe: it cannot be recovered.",
        "Passphrase salvata. Conservala in un posto sicuro: non si può recuperare.",
    )

    fun passphraseNotSaved(language: RenderLanguage): String = language.pick(
        "Could not save the passphrase: nothing changed.",
        "Impossibile salvare la passphrase: non è cambiato nulla.",
    )

    fun passphraseTooShort(language: RenderLanguage): String = language.pick(
        "The passphrase needs at least 8 characters.",
        "La passphrase deve avere almeno 8 caratteri.",
    )

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_EN = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private val DAY_IT = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
}
