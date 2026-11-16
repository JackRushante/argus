package dev.argus.automation.vm

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.VarBinding

/**
 * Segreti di una regola nella modifica in chat: i valori delle intestazioni HTTP (token, chiavi,
 * cookie) e i letterali SECRET delle variabili non partono verso il provider. Nel prompt diventano
 * segnaposto opachi (`argus_redacted_secret_1`, ...) e, dopo il compile, [restore] rimette il
 * valore vero dove il modello ha lasciato il segnaposto. Un valore che l'utente ha chiesto di cambiare
 * (il modello scrive un valore nuovo) resta quello nuovo, come per le coordinate e il contatto.
 */
internal object EditSecretRedaction {
    const val PLACEHOLDER_PREFIX = "argus_redacted_secret_"

    data class Redacted(val draft: AutomationDraft, val originals: Map<String, String>)

    /** Sostituisce ogni valore segreto con un segnaposto; lo stesso valore riceve lo stesso segnaposto. */
    fun redact(draft: AutomationDraft): Redacted {
        val originals = LinkedHashMap<String, String>()
        val byValue = HashMap<String, String>()
        val rewritten = rewrite(draft) { value ->
            byValue.getOrPut(value) {
                (PLACEHOLDER_PREFIX + (originals.size + 1)).also { originals[it] = value }
            }
        }
        return Redacted(if (originals.isEmpty()) draft else rewritten, originals)
    }

    /** Rimette i valori originali dove [fromModel] ha lasciato un segnaposto di [originals]. */
    fun restore(fromModel: AutomationDraft, originals: Map<String, String>): AutomationDraft {
        if (originals.isEmpty()) return fromModel
        return rewrite(fromModel, secretsOnly = false) { value -> originals[value] ?: value }
    }

    /**
     * Applica [transform] ai valori delle intestazioni (a ogni profondità di if/while) e ai letterali:
     * in redazione ai soli SECRET, in ripristino a tutti, perché il modello può aver cambiato la
     * riservatezza lasciando il segnaposto.
     */
    private fun rewrite(
        draft: AutomationDraft,
        secretsOnly: Boolean = true,
        transform: (String) -> String,
    ): AutomationDraft {
        fun actions(items: List<Action>): List<Action> = items.map { action ->
            when (action) {
                is Action.HttpRequest -> action.headers
                    ?.let { headers -> action.copy(headers = headers.mapValues { (_, value) -> transform(value) }) }
                    ?: action
                is Action.If -> action.copy(then = actions(action.then), orElse = actions(action.orElse))
                is Action.While -> action.copy(body = actions(action.body))
                else -> action
            }
        }
        val vars = draft.vars.map { binding ->
            if (binding is VarBinding.Literal &&
                (!secretsOnly || binding.confidentiality == ConfidentialityLabel.SECRET)
            ) {
                binding.copy(value = transform(binding.value))
            } else {
                binding
            }
        }
        return draft.copy(actions = actions(draft.actions), vars = vars)
    }
}
