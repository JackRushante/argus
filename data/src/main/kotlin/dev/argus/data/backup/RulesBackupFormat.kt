package dev.argus.data.backup

import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationStatus
import java.time.LocalDate

/**
 * Formato del file di backup/condivisione delle regole (0.4.0).
 *
 * ```
 * {"format":"argus-rules","version":1,"exportedAt":"<ISO>","appVersion":"<versionName>",
 *  "rules":[{"enabled":<bool>,"draft":<AutomationDraft>}]}
 * ```
 *
 * Il file contiene SOLO i dati eseguibili della regola (la stessa bozza tipata che l'LLM produce in
 * chat): niente impronte, dati di approvazione, log, audit, impostazioni, chiavi API, whitelist dei
 * contatti, consumi. All'import ogni voce diventa una bozza da approvare: `enabled` è solo
 * informativo e non arma mai nulla.
 *
 * 0.7.2, etichette: una chiave FACOLTATIVA alla radice, scritta solo se almeno una regola ne ha,
 * ```
 *  "labels":[{"rule":<indice in rules>,"names":["Casa","Lavoro"]}]
 * ```
 * La versione resta 1, in entrambe le direzioni: le versioni precedenti leggono solo `format`, `version` e
 * `rules` e ignorano le altre chiavi della radice, quindi importano tutte le regole senza etichette; un file
 * senza `labels` (versioni precedenti) si importa come prima. La voce di una regola resta esattamente
 * `{"enabled","draft"}`: una chiave in più lì farebbe scartare la regola alle versioni precedenti. Le
 * etichette sono organizzazione: una sezione `labels` illeggibile si ignora e non scarta mai una regola.
 */
object RulesBackupFormat {
    const val FORMAT = "argus-rules"
    const val VERSION = 1
    const val MIME_TYPE = "application/json"

    /** Limiti dell'import (input non fidato). */
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    const val MAX_RULES = 500

    /** Profondità massima di annidamento JSON: oltre, il parser ricorsivo non viene nemmeno avviato. */
    const val MAX_NESTING = 64

    /** Dimensione massima del JSON di una singola bozza (caratteri). */
    const val MAX_RULE_CHARS = 100_000

    /** Nome predefinito proposto dal selettore di file: `argus-rules-yyyy-MM-dd.json`. */
    fun defaultFileName(date: LocalDate): String = "argus-rules-$date.json"

    /**
     * 0.7.2: nome proposto per una regola condivisa come file, `argus-rule-<nome>.json`. Del nome restano
     * lettere e cifre ASCII (minuscole) unite da trattini, al massimo 40 caratteri; senza, `argus-rule.json`.
     */
    fun shareFileName(ruleName: String): String {
        val slug = java.text.Normalizer.normalize(ruleName, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_SLUG_CHARS)
            .trim('-')
        return if (slug.isEmpty()) "argus-rule.json" else "argus-rule-$slug.json"
    }

    /**
     * 0.7.2: un testo incollato o ricevuto da un'altra app, come byte UTF-8 da importare. null se supera
     * [MAX_FILE_BYTES]: il controllo avviene PRIMA di qualsiasi lettura del contenuto, come per un file.
     */
    fun utf8WithinCap(text: String): ByteArray? {
        // Ogni carattere vale almeno un byte: oltre il limite in caratteri lo è anche in byte.
        if (text.length > MAX_FILE_BYTES) return null
        return text.toByteArray(Charsets.UTF_8).takeIf { it.size <= MAX_FILE_BYTES }
    }

    private const val MAX_SLUG_CHARS = 40
}

/**
 * 0.8: modalità e valori conservati, in due chiavi FACOLTATIVE alla radice (le versioni precedenti le ignorano):
 * ```
 *  "modes":{"names":["Casa","Lavoro"],"active":"Casa"},
 *  "storedValues":[{"key":"caffe_oggi","type":"NUMBER","value":"2","integrity":"CLEAN",
 *                   "confidentiality":"PUBLIC","writerRule":"<id>"|null,"updatedAt":<ms>}]
 * ```
 * L'export completo le scrive entrambe, ma un valore SECRET solo nel backup cifrato con la passphrase (in un
 * file in chiaro manca, chiave compresa); quello senza dati personali tiene solo i nomi delle modalità (niente
 * modalità attiva, niente valori). Lettura tollerante come per le etichette: una voce malformata si salta e
 * non scarta mai una regola. Si ripristinano solo dall'import di Sistema (file scelto dall'utente).
 */
data class BackupState(
    val modes: List<String> = emptyList(),
    val activeMode: String? = null,
    val values: List<dev.argus.engine.model.StoredValue> = emptyList(),
) {
    val isEmpty: Boolean get() = modes.isEmpty() && activeMode == null && values.isEmpty()
}

/** Una voce del file: la bozza, se la regola era attiva al momento dell'export e le sue etichette. */
data class BackupRule(
    val enabled: Boolean,
    val draft: AutomationDraft,
    /** 0.7.2: etichette della regola (organizzazione, fuori dalla bozza e dalla sua impronta). */
    val labels: List<String> = emptyList(),
)

/** Perché un export o un import non è andato a buon fine. Vocabolario chiuso: il testo lo scrive la UI. */
enum class BackupFailure {
    /** Export: nessuna regola esportabile. */
    NOTHING_TO_EXPORT,

    /** Export: il file supererebbe i limiti dell'import (non sarebbe ripristinabile). */
    EXPORT_TOO_LARGE,

    /** Export: scrittura del file non riuscita. */
    WRITE_FAILED,

    /** Import: file illeggibile. */
    READ_FAILED,

    /** Import: oltre [RulesBackupFormat.MAX_FILE_BYTES]. */
    FILE_TOO_LARGE,

    /** Import: non è JSON (o non è UTF-8, o è annidato oltre il limite). */
    NOT_JSON,

    /** Import: file cifrato (`.argusbackup`): serve la passphrase. Non è un errore da mostrare. */
    PASSPHRASE_REQUIRED,

    /** Import cifrato: la passphrase non apre il file (o il file è stato modificato). */
    WRONG_PASSPHRASE,

    /** Import cifrato: il file è troncato, manca anche l'intestazione. */
    ENCRYPTED_DAMAGED,

    /** Import: JSON valido ma non un'esportazione Argus (`format`/struttura errati). */
    WRONG_FORMAT,

    /** Import: `version` assente o diversa da quella supportata. */
    UNSUPPORTED_VERSION,

    /** Import: più di [RulesBackupFormat.MAX_RULES] regole. */
    TOO_MANY_RULES,

    /** Import: nessuna regola nel file. */
    NO_RULES,

    /** Import: il salvataggio delle bozze non è riuscito. Nessuna bozza è stata aggiunta. */
    SAVE_FAILED,
}

/** Perché una singola regola del file è stata scartata all'import. */
enum class RuleDiscardReason {
    /** La voce non ha la forma `{"enabled":<bool>,"draft":{...}}`. */
    MALFORMED_ENTRY,

    /** La bozza non si decodifica nei tipi del dominio (tipo/campo/valore sconosciuto o errato). */
    INVALID_RULE,

    /** La bozza supera [RulesBackupFormat.MAX_RULE_CHARS]. */
    RULE_TOO_LARGE,

    /** Il repository ha rifiutato la bozza. */
    NOT_SAVED,
}

data class ImportSummary(
    /** Bozze create, tutte in attesa di approvazione. */
    val imported: Int,
    /** Regole scartate, per motivo. */
    val discarded: Map<RuleDiscardReason, Int>,
) {
    val discardedTotal: Int get() = discarded.values.sum()
}

sealed interface ExportOutcome {
    data class Done(val exported: Int, val skipped: Int) : ExportOutcome
    data class Failed(val failure: BackupFailure) : ExportOutcome
}

sealed interface ImportOutcome {
    /** [draftIds] (0.7.2): gli id delle bozze create, nell'ordine del file, per aprirne l'approvazione. */
    data class Done(val summary: ImportSummary, val draftIds: List<String> = emptyList()) : ImportOutcome
    data class Failed(val failure: BackupFailure) : ImportOutcome
}

/** 0.7.2: lettura di un documento con il limite dell'import, senza decodificarlo. */
sealed interface DocumentRead {
    class Bytes(val bytes: ByteArray) : DocumentRead
    data class Failed(val failure: BackupFailure) : DocumentRead
}

/**
 * 0.7.2: cosa importerebbe un testo o un file, senza scrivere nulla: i nomi delle regole valide (ripuliti
 * per la sola visualizzazione), le voci che verrebbero scartate e la dimensione in byte.
 */
sealed interface ImportPreview {
    data class Ready(
        val ruleNames: List<String>,
        val discarded: Map<RuleDiscardReason, Int>,
        val sizeBytes: Int,
    ) : ImportPreview {
        val discardedTotal: Int get() = discarded.values.sum()
    }

    data class Rejected(val failure: BackupFailure) : ImportPreview
}

/** Il testo dell'export completo, pronto da scrivere (in chiaro o cifrato). */
sealed interface BuiltBackup {
    class Ready(val bytes: ByteArray, val exported: Int, val skipped: Int) : BuiltBackup
    data class Failed(val failure: BackupFailure) : BuiltBackup
}

/**
 * Una regola è esportabile se è approvata (armata o disattivata) o «da rivedere» con contenuto
 * leggibile. Una regola in quarantena il cui JSON non si decodifica torna dallo store come
 * segnaposto senza azioni: non ha nulla da esportare.
 */
fun Automation.isBackupExportable(): Boolean =
    status in EXPORTABLE_STATUSES && actions.isNotEmpty()

private val EXPORTABLE_STATUSES = setOf(
    AutomationStatus.ARMED,
    AutomationStatus.DISABLED,
    AutomationStatus.NEEDS_REVIEW,
)
