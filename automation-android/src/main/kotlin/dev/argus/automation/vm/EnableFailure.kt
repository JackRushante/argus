package dev.argus.automation.vm

import dev.argus.ui.presentation.RenderLanguage

/**
 * Codice dell'audit `ENABLE_FAILED` quando si riattiva una regola una tantum con l'orario già
 * passato. L'esito della riattivazione è tipizzato (`EnablementResult.OneShotInPast`); il codice
 * serve solo a riconoscere la riga già scritta nel Log.
 */
internal const val ONE_SHOT_IN_PAST = "one_shot_in_past"

/** Testo del motivo [ONE_SHOT_IN_PAST]: dice cosa fare, non il codice. */
internal fun oneShotInPastText(language: RenderLanguage): String = language.pick(
    "This rule's time has already passed: edit it to choose a new time.",
    "L'orario di questa regola è già passato: modificala per scegliere un nuovo orario.",
)
