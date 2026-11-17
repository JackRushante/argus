package dev.argus.engine.runtime

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId

/**
 * «Chiedimi prima di eseguire» (0.8, `confirm`). Lo scatto di una regola con `confirm` passa trigger,
 * pausa, policy, condizioni e claim (cooldown e deduplica valgono come se fosse partita), poi invece
 * di eseguire chiede all'utente. [envelope] contiene l'evento che l'ha fatta scattare: chi lo conserva
 * lo cifra, perché può contenere il testo di un messaggio.
 */
class ConfirmationRequest(
    val automationId: AutomationId,
    val ruleName: String,
    val approvalFingerprint: ApprovalFingerprint,
    val envelope: TriggerEnvelope,
    val executionId: ExecutionId,
    val timeoutMs: Long,
    val requestedAtMillis: Long,
) {
    override fun toString(): String =
        "ConfirmationRequest(automationId=${automationId.value}, executionId=${executionId.value}, " +
            "timeoutMs=$timeoutMs, event=<redacted>)"
}

enum class ConfirmationRequestResult {
    /** La domanda è conservata e mostrata: la regola aspetta Esegui o Annulla. */
    REQUESTED,

    /** C'è già una domanda aperta per questa regola: una sola alla volta, questo scatto finisce qui. */
    ALREADY_PENDING,

    /** Non si può chiedere (notifiche spente, archivio non scrivibile): la regola non esegue. */
    UNAVAILABLE,
}

/** Porta Android della conferma: conserva l'evento cifrato, mostra la notifica Esegui/Annulla. */
fun interface ConfirmationGate {
    suspend fun request(request: ConfirmationRequest): ConfirmationRequestResult

    companion object {
        /** Default senza runtime: nessuna regola con `confirm` esegue mai senza chiedere. */
        val UNAVAILABLE = ConfirmationGate { ConfirmationRequestResult.UNAVAILABLE }
    }
}

/**
 * L'utente ha toccato Esegui. [approvalFingerprint] è quella vista quando la domanda è partita: se la
 * regola è cambiata nel frattempo la conferma non vale. [confirmationId] è l'identità casuale della
 * domanda (non il token del pulsante): dà l'id dell'evento dell'esecuzione confermata, così un doppio
 * tocco è un duplicato.
 */
class ConfirmedFire(
    val confirmationId: String,
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    val envelope: TriggerEnvelope,
) {
    init {
        require(ID.matches(confirmationId)) { "confirmationId non valido" }
    }

    /** Evento dell'esecuzione confermata: distinto dallo scatto che ha chiesto, unico per domanda. */
    val runEventId: TriggerEventId get() = TriggerEventId("$EVENT_PREFIX$confirmationId")

    override fun toString(): String =
        "ConfirmedFire(confirmationId=$confirmationId, automationId=${automationId.value}, event=<redacted>)"

    companion object {
        const val EVENT_PREFIX = "confirmed:"
        val ID = Regex("^[a-z0-9]{16,64}$")
    }
}

/** Come si chiude una domanda senza esecuzione: codici chiusi del Log, mai un errore. */
enum class ConfirmationClosed(val code: String) {
    CANCELLED(ConfirmationCodes.CANCELLED),
    EXPIRED(ConfirmationCodes.EXPIRED),
}

/** Codici chiusi della conferma nel Log (audit `detail`). */
object ConfirmationCodes {
    const val REQUESTED = "confirmation_requested"
    const val PENDING = "confirmation_pending"
    const val UNAVAILABLE = "confirmation_unavailable"
    const val CANCELLED = "confirmation_cancelled"
    const val EXPIRED = "confirmation_expired"

    /** Esegui toccato, ma la regola non è più quella approvata, armata e con `confirm`. */
    const val STALE = "confirmation_stale"

    val ALL = setOf(REQUESTED, PENDING, UNAVAILABLE, CANCELLED, EXPIRED, STALE)
}
