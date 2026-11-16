package dev.argus.automation.vm

import dev.argus.data.LabelAddResult
import dev.argus.ui.presentation.RenderLanguage

/**
 * 0.7.2: che cosa dire dopo «aggiungi etichetta». `null` = niente da dire (aggiunta, o c'era già). Il foglio
 * controlla gli stessi limiti prima di mandare: questi messaggi coprono una corsa (un'altra regola ha appena
 * preso la ventesima etichetta) o un guasto.
 */
internal fun labelAddMessage(result: LabelAddResult?, language: RenderLanguage): String? = when (result) {
    is LabelAddResult.Added, LabelAddResult.AlreadyPresent -> null
    LabelAddResult.Invalid -> language.pick(
        "A label has 1 to 24 characters, without invisible characters.",
        "Un'etichetta ha da 1 a 24 caratteri, senza caratteri invisibili.",
    )
    LabelAddResult.RuleFull -> language.pick(
        "A rule can have up to 5 labels.",
        "Una regola può avere al massimo 5 etichette.",
    )
    LabelAddResult.TooManyLabels -> language.pick(
        "You already have 20 labels: reuse one of them.",
        "Hai già 20 etichette: usane una di quelle esistenti.",
    )
    LabelAddResult.MissingRule, null -> language.pick(
        "Unable to add the label.",
        "Impossibile aggiungere l'etichetta.",
    )
}
