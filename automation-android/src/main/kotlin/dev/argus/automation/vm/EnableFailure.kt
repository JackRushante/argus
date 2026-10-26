package dev.argus.automation.vm

import dev.argus.data.ArgusDatabase
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.AuditKind
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.flow.first

/** Motivo `ENABLE_FAILED` della riattivazione di una regola una tantum con l'orario già passato. */
internal const val ONE_SHOT_IN_PAST = "one_shot_in_past"

/** Testo del motivo [ONE_SHOT_IN_PAST]: dice cosa fare, non il codice. */
internal fun oneShotInPastText(language: RenderLanguage): String = language.pick(
    "This rule's time has already passed: edit it to choose a new time.",
    "L'orario di questa regola è già passato: modificala per scegliere un nuovo orario.",
)

private const val ENABLE_FAILURE_LOOKUP_LIMIT = 5

/**
 * Dopo una riattivazione fallita legge il motivo che il coordinator ha appena scritto nell'audit
 * (`ENABLE_FAILED`, dettaglio = codice). Restituisce il testo specifico se il motivo è noto e
 * più utile del messaggio generico, altrimenti null. Solo codici: nessun dato dell'utente.
 */
internal suspend fun enableFailureMessage(
    database: ArgusDatabase,
    id: AutomationId,
    sinceMillis: Long,
    language: RenderLanguage,
): String? {
    val code = cancellationSafeOrNull {
        database.auditDao()
            .observeLogForAutomation(id.value, ENABLE_FAILURE_LOOKUP_LIMIT)
            .first()
            .firstOrNull { it.kind == AuditKind.ENABLE_FAILED && it.atMillis >= sinceMillis }
            ?.detail
    }
    return when (code) {
        ONE_SHOT_IN_PAST -> oneShotInPastText(language)
        else -> null
    }
}
