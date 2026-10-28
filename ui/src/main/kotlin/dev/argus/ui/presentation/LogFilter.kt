package dev.argus.ui.presentation

import dev.argus.engine.runtime.AuditKind
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRow

/**
 * Filtro del Log per esito (tavole «Log» e «Log · filtro», 0.7.0). Vive solo nella schermata: non e' nello
 * stato del ViewModel e non cambia cosa si legge dal database, filtra le righe gia' caricate.
 *
 * I filtri non sono una partizione: una regola finita in quarantena e' sia un problema sia un evento del
 * ciclo di vita, e compare sotto entrambi.
 */
enum class LogFilter {
    /** Ogni riga. */
    ALL,

    /** Cio' che non e' andato come doveva: errori, blocchi, esecuzioni fallite o parziali, regole da rivedere. */
    PROBLEMS,

    /** Righe di esecuzioni con almeno un'azione generativa/cloud. */
    GENERATIVE,

    /** Armata, abilitata, disabilitata, eliminata, da rivedere: transizioni, non esecuzioni. */
    LIFECYCLE,
    ;

    fun matches(row: LogRow): Boolean = when (this) {
        ALL -> true
        PROBLEMS -> row.isProblem()
        GENERATIVE -> row.isGenerative
        LIFECYCLE -> row.kind in LIFECYCLE_KINDS
    }

    companion object {
        private val LIFECYCLE_KINDS = setOf(
            AuditKind.RULE_ARMED,
            AuditKind.RULE_ENABLED,
            AuditKind.RULE_DISABLED,
            AuditKind.RULE_DELETED,
            AuditKind.RULE_NEEDS_REVIEW,
        )

        private val PROBLEM_KINDS = setOf(
            AuditKind.ERROR,
            AuditKind.BLOCKED_POLICY,
            AuditKind.VALIDATION_REJECTED,
            AuditKind.ARM_FAILED,
            AuditKind.SCHEDULING_FAILED,
            AuditKind.ENABLE_FAILED,
            AuditKind.SUPPRESSED_BUDGET,
            AuditKind.RULE_NEEDS_REVIEW,
        )

        private fun LogRow.isProblem(): Boolean =
            kind in PROBLEM_KINDS ||
                (kind == AuditKind.FIRED && (outcome == LogOutcome.FAILED || outcome == LogOutcome.PARTIAL))

        /** Le righe che passano [filter], nello stesso ordine. */
        fun apply(filter: LogFilter, rows: List<LogRow>): List<LogRow> =
            if (filter == ALL) rows else rows.filter(filter::matches)
    }
}

/**
 * Un valore assente nel Log si scrive «n/d», mai 0 e mai una stringa vuota (invariante 8): una riga senza
 * testo o senza orario resta leggibile come «non disponibile».
 */
fun String?.orNotAvailable(notAvailable: String): String =
    if (this.isNullOrBlank()) notAvailable else this
