package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.ScrollDirection
import dev.argus.engine.model.UiStep
import dev.argus.ui.model.ActionRow

/**
 * Resa dell'approvazione e del dettaglio per `ui_steps` (0.5.1). Come ogni resa di [RuleRenderMapper]
 * parte dai TIPI, mai dalla prosa del modello, ed è bilingue via [RenderLanguage].
 *
 * La macro si mostra INTERA, un passo per riga: l'app da aprire (il package, per esteso), ogni
 * criterio di ricerca com'è scritto nel modello approvato e il testo da digitare con i suoi `${…}`.
 * L'ultima riga ricorda che la macro agisce sull'interfaccia come farebbe l'utente.
 */
internal object ActionRowsUiSteps {

    fun uiSteps(a: Action.UiSteps, l: RenderLanguage): ActionRow {
        val lines = buildList {
            add(
                a.pkg?.let { l.pick("Opens $it first", "Prima apre $it") }
                    ?: l.pick("Works on the screen as it is", "Lavora sullo schermo così com'è"),
            )
            a.steps.forEachIndexed { index, step -> add("${index + 1}. ${stepText(step, l)}") }
            add(
                l.pick(
                    "Acts on the screen like you would: it taps, types and scrolls in the app, and stops if " +
                        "an element is not found.",
                    "Agisce sullo schermo come faresti tu: tocca, scrive e scorre nell'app, e si ferma se " +
                        "un elemento non si trova.",
                ),
            )
        }
        return ActionRow(
            iconKey = "tap",
            label = l.pick("Steps on the screen", "Passi sullo schermo"),
            detail = lines.joinToString("\n"),
            isShell = false,
            shellCommand = null,
            isGenerative = false,
            requiresLiveConfirm = false,
        )
    }

    fun stepText(step: UiStep, l: RenderLanguage): String = when (step) {
        is UiStep.WaitFor -> l.pick(
            "Wait up to ${seconds(step.timeoutMs)} s for ${criteria(step.text, step.id, null, l)}",
            "Aspetta fino a ${seconds(step.timeoutMs)} s: ${criteria(step.text, step.id, null, l)}",
        )
        is UiStep.Click -> {
            val which = step.index?.let { l.pick(" (match number ${it + 1})", " (corrispondenza numero ${it + 1})") }.orEmpty()
            l.pick(
                "Tap the element with ${criteria(step.text, step.id, step.desc, l)}$which",
                "Tocca l'elemento con ${criteria(step.text, step.id, step.desc, l)}$which",
            )
        }
        is UiStep.Type -> l.pick("Type \"${step.text}\" in the focused field", "Scrivi \"${step.text}\" nel campo attivo")
        is UiStep.Scroll -> when (step.dir) {
            ScrollDirection.UP -> l.pick("Scroll up", "Scorri in su")
            ScrollDirection.DOWN -> l.pick("Scroll down", "Scorri in giù")
        }
        UiStep.Back -> l.pick("Press Back", "Premi Indietro")
        UiStep.Home -> l.pick("Press Home", "Premi Home")
        is UiStep.Check -> if (step.present) {
            l.pick(
                "Check that \"${step.text}\" is on the screen, otherwise stop",
                "Controlla che \"${step.text}\" sia sullo schermo, altrimenti fermati",
            )
        } else {
            l.pick(
                "Check that \"${step.text}\" is NOT on the screen, otherwise stop",
                "Controlla che \"${step.text}\" NON sia sullo schermo, altrimenti fermati",
            )
        }
    }

    private fun seconds(timeoutMs: Int): String {
        val whole = timeoutMs / 1_000
        val tenths = (timeoutMs % 1_000) / 100
        return if (tenths == 0) whole.toString() else "$whole.$tenths"
    }

    /** Criteri di ricerca, tutti quelli presenti, nell'ordine fisso testo · id · descrizione. */
    private fun criteria(text: String?, id: String?, desc: String?, l: RenderLanguage): String {
        val parts = buildList {
            text?.let { add(l.pick("text \"$it\"", "testo \"$it\"")) }
            id?.let { add(l.pick("id \"$it\"", "id \"$it\"")) }
            desc?.let { add(l.pick("description \"$it\"", "descrizione \"$it\"")) }
        }
        return parts.joinToString(l.pick(" and ", " e "))
    }
}
