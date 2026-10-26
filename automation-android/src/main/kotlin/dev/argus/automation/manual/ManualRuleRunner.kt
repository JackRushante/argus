package dev.argus.automation.manual

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationPause
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.Job

/** Perché un ingresso manuale è stato rifiutato. Nessuno di questi motivi esegue qualcosa. */
enum class ManualRejection {
    /** Nessuna regola con quell'id. */
    UNKNOWN_RULE,

    /** La regola esiste ma non è armata (bozza, disattivata, da rivedere). */
    NOT_ARMED,

    /** La regola è armata ma il suo trigger non è `manual`. */
    NOT_MANUAL,

    /** L'intent non porta il token giusto per quella regola. */
    BAD_TOKEN,
}

sealed interface ManualRunResult {
    /**
     * Consegnata al motore. Con [paused] il motore salterà lo scatto e lo scriverà nel Log come
     * `paused`: «Pausa automazioni» vale anche per l'avvio manuale. [completion] finisce quando il
     * motore ha concluso (anche un programma lungo): chi ha un gesto in corso (scorciatoia, widget) può
     * aspettarla per poco, perché le azioni che aprono app partono dal contesto del tocco.
     */
    data class Accepted(
        val ruleName: String,
        val paused: Boolean,
        val completion: Job,
    ) : ManualRunResult

    data class Rejected(val reason: ManualRejection) : ManualRunResult
}

/** Consegna al motore l'evento manuale. In produzione passa dal dispatch frame (wakelock, manutenzione). */
fun interface ManualEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

/**
 * Esegue [block] fuori dal chiamante, tenendo vivo il processo per la durata del lavoro (in
 * produzione con la lease del servizio in primo piano). Il [Job] restituito è il lavoro.
 */
fun interface ManualWorkLauncher {
    fun launch(automationId: AutomationId, block: suspend () -> Unit): Job
}

/**
 * Unico punto d'ingresso dell'avvio manuale (0.4.1, F8): «Esegui ora», scorciatoia sulla Home,
 * riquadro delle impostazioni rapide e widget finiscono tutti qui.
 *
 * Ogni ingresso accetta SOLO l'id di una regola ARMATA con trigger `manual`. Le scorciatoie e i
 * widget (intent che possono passare da fuori) portano anche il token per regola. L'esecuzione poi
 * segue la strada normale del motore: impronta approvata, capacità, condizioni, cooldown, journal. Il
 * motore stesso riverifica che l'evento sia della regola giusta (l'evento manuale è accettato solo da
 * un trigger `manual`) e quindi un rifiuto qui non è l'unica difesa.
 */
class ManualRuleRunner(
    private val store: AutomationStore,
    private val tokens: ManualTokenStore,
    private val pause: AutomationPause,
    private val dispatcher: ManualEventDispatcher,
    private val launcher: ManualWorkLauncher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Parte casuale dell'id evento: due tocchi distinti non sono mai lo stesso evento (la deduplica non li fonde). */
    private val nonce: () -> String = { java.util.UUID.randomUUID().toString().take(NONCE_CHARS) },
) {
    /** Dal Dettaglio: chiamante dentro l'app, senza intent. Nessun token. */
    suspend fun runFromApp(ruleId: String): ManualRunResult = start(ruleId)

    /** Dal riquadro delle impostazioni rapide: la regola l'ha scelta l'utente nell'app, nessun intent da fuori. */
    suspend fun runFromTile(ruleId: String): ManualRunResult = start(ruleId)

    /**
     * Da una scorciatoia o da un widget: l'intent può arrivare da un'altra app, quindi serve il token
     * di QUELLA regola. Il controllo del token viene prima di ogni lettura della regola.
     */
    suspend fun runFromIntent(ruleId: String?, token: String?): ManualRunResult {
        if (ruleId.isNullOrBlank() || !tokens.isValid(AutomationId(ruleId), token)) {
            return ManualRunResult.Rejected(ManualRejection.BAD_TOKEN)
        }
        return start(ruleId)
    }

    private suspend fun start(ruleId: String): ManualRunResult {
        if (ruleId.isBlank() || ruleId.length > MAX_ID_CHARS) {
            return ManualRunResult.Rejected(ManualRejection.UNKNOWN_RULE)
        }
        val automation = store.get(AutomationId(ruleId))
            ?: return ManualRunResult.Rejected(ManualRejection.UNKNOWN_RULE)
        if (automation.status != AutomationStatus.ARMED || !automation.enabled) {
            return ManualRunResult.Rejected(ManualRejection.NOT_ARMED)
        }
        if (automation.trigger !is Trigger.Manual) {
            return ManualRunResult.Rejected(ManualRejection.NOT_MANUAL)
        }
        val fingerprint = automation.approvalFingerprint
            ?: return ManualRunResult.Rejected(ManualRejection.NOT_ARMED)
        val envelope = TriggerEnvelope(
            id = TriggerEventId("manual:${fingerprint.value}:${nowMillis()}:${nonce()}"),
            event = TriggerEvent.ManualFired(automation.id, fingerprint),
        )
        // La pausa si legge prima di consegnare, solo per dire all'utente com'è andata: a decidere è il
        // motore, che salta lo scatto e lo scrive nel Log.
        val paused = pause.isPaused()
        val job = launcher.launch(automation.id) { dispatcher.dispatch(envelope) }
        return ManualRunResult.Accepted(ruleName = automation.name, paused = paused, completion = job)
    }

    private companion object {
        const val NONCE_CHARS = 12
        const val MAX_ID_CHARS = 128
    }
}
