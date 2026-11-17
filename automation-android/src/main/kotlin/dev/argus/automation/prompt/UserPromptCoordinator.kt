package dev.argus.automation.prompt

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ConfirmationClosed
import dev.argus.engine.runtime.ConfirmationGate
import dev.argus.engine.runtime.ConfirmationRequest
import dev.argus.engine.runtime.ConfirmationRequestResult
import dev.argus.engine.runtime.ConfirmedFire
import dev.argus.engine.runtime.ProgramContinuationCodec
import dev.argus.engine.runtime.ProgramQuestion
import dev.argus.engine.runtime.QuestionAsker
import dev.argus.engine.runtime.QuestionContext
import dev.argus.engine.runtime.QuestionOutcome
import dev.argus.engine.runtime.ResumedQuestion
import dev.argus.engine.runtime.TriggerEnvelopeCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * All'avvio del processo: rimostra le conferme ancora valide (un riavvio cancella notifiche e sveglie),
 * chiude quelle scadute e toglie le domande orfane. Alla revoca della privacy toglie tutto: le domande
 * possono contenere il testo di un messaggio, come le risposte differite.
 */
class UserPromptStartup(
    private val scope: CoroutineScope,
    private val coordinator: UserPromptCoordinator,
    private val privacyAccepted: Flow<Boolean>,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                coordinator.sweep(repost = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Una domanda illeggibile non blocca l'avvio.
            }
        }
        scope.launch {
            privacyAccepted.distinctUntilChanged().drop(1).collect { accepted ->
                if (!accepted) coordinator.clearAll()
            }
        }
    }
}

/** Il tocco su un pulsante di una notifica di Argus (0.8). */
sealed interface PromptAnswer {
    data object Run : PromptAnswer
    data object Cancel : PromptAnswer

    /** L'indice del pulsante, mai il testo: il testo si prende dalla domanda approvata. */
    data class Choice(val index: Int) : PromptAnswer
}

/** La parte Android delle domande: notifica, sveglia di scadenza. */
interface UserPromptSurface {
    /** Mostra (o rimostra) la domanda; false se non si può (permesso, notifiche o canale spenti). */
    fun show(prompt: PendingPrompt, nowMillis: Long): Boolean

    fun dismiss(promptId: String)

    /** Sveglia che a [atMillis] chiude la domanda anche col telefono addormentato. Best effort. */
    fun scheduleExpiry(promptId: String, atMillis: Long)

    fun cancelExpiry(promptId: String)
}

/** Il motore, visto dalle domande: si risolve pigramente (il motore dipende da questo coordinatore). */
interface ConfirmedRuns {
    suspend fun run(confirmed: ConfirmedFire)
    suspend fun closed(automationId: AutomationId, reason: ConfirmationClosed)

    /** Una domanda `ask` risposta (o scaduta) dopo la morte del programma che la poneva: lo fa proseguire. */
    suspend fun resume(resumed: ResumedQuestion)
}

/**
 * «Chiedimi prima di eseguire» e le domande `ask` (0.8). Conserva ogni domanda cifrata con la sua
 * scadenza ([PendingPromptStore]), mostra la notifica con un token casuale per domanda e accetta solo
 * un tocco con quel token ([handle]). Una conferma sopravvive a morte del processo e riavvio entro la sua
 * scadenza ([sweep] la rimostra o la chiude come scaduta). Una domanda `ask` aspetta senza tenere il
 * processore sveglio ([pause], come `wait`); con lei è conservata, cifrata, la continuazione del programma
 * (evento, punto, variabili con le loro label), così se il processo muore la risposta o la scadenza lo
 * fanno proseguire da lì ([ConfirmedRuns.resume]). Una domanda senza continuazione si toglie all'avvio.
 */
class UserPromptCoordinator(
    private val store: PendingPromptStore,
    private val surface: UserPromptSurface,
    private val runs: ConfirmedRuns,
    /** L'attesa di `wait` (sveglia di sistema, quota del wakelock rilasciata). */
    private val pause: suspend (Long) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ConfirmationGate, QuestionAsker {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<String>>()

    override suspend fun request(request: ConfirmationRequest): ConfirmationRequestResult {
        val now = nowMillis()
        val creation = store.create(
            kind = PromptKind.CONFIRM,
            automationId = request.automationId.value,
            ruleName = request.ruleName,
            fingerprint = request.approvalFingerprint.value,
            executionId = request.executionId.value,
            createdAtMillis = now,
            expiresAtMillis = now + request.timeoutMs,
            envelope = TriggerEnvelopeCodec.encode(request.envelope),
        )
        val prompt = when (creation) {
            is PendingPromptStore.Creation.Created -> creation.prompt
            PendingPromptStore.Creation.AlreadyPending -> return ConfirmationRequestResult.ALREADY_PENDING
            PendingPromptStore.Creation.Failed -> return ConfirmationRequestResult.UNAVAILABLE
        }
        if (!surface.show(prompt, now)) {
            store.remove(prompt.id)
            return ConfirmationRequestResult.UNAVAILABLE
        }
        surface.scheduleExpiry(prompt.id, prompt.expiresAtMillis)
        return ConfirmationRequestResult.REQUESTED
    }

    override suspend fun ask(context: QuestionContext, question: ProgramQuestion): QuestionOutcome {
        val now = nowMillis()
        val resumable = resumableParts(context, question)
        val creation = store.create(
            kind = PromptKind.ASK,
            automationId = context.automationId.value,
            ruleName = context.ruleName,
            fingerprint = context.approvalFingerprint.value,
            executionId = context.executionId.value,
            createdAtMillis = now,
            expiresAtMillis = now + question.timeoutMs,
            envelope = resumable?.first,
            prompt = question.prompt,
            choices = question.choices,
            continuation = resumable?.second,
        )
        val prompt = (creation as? PendingPromptStore.Creation.Created)?.prompt
            ?: return QuestionOutcome.Unavailable(ASK_UNAVAILABLE)
        val answer = CompletableDeferred<String>()
        waiters[prompt.id] = answer
        try {
            if (!surface.show(prompt, now)) return QuestionOutcome.Unavailable(ASK_NOTIFICATION_UNAVAILABLE)
            // Se il processo muore, la sveglia chiude la domanda alla scadenza e fa proseguire il programma.
            if (prompt.continuation != null) surface.scheduleExpiry(prompt.id, prompt.expiresAtMillis)
            val chosen = coroutineScope {
                val timer = async { pause(question.timeoutMs) }
                try {
                    select<String?> {
                        answer.onAwait { it }
                        timer.onAwait { null }
                    }
                } finally {
                    timer.cancel()
                }
            }
            return chosen?.let(QuestionOutcome::Answered) ?: QuestionOutcome.TimedOut
        } finally {
            withContext(NonCancellable) {
                waiters.remove(prompt.id)
                store.remove(prompt.id)
                surface.dismiss(prompt.id)
                if (prompt.continuation != null) surface.cancelExpiry(prompt.id)
            }
        }
    }

    /**
     * Evento e continuazione codificati, o null se il programma non si può riprendere (manca l'uno o
     * l'altra, o insieme superano [MAX_RESUME_CHARS]): allora la domanda vive solo col programma vivo.
     */
    private fun resumableParts(context: QuestionContext, question: ProgramQuestion): Pair<String, String>? {
        val envelope = context.envelope ?: return null
        val continuation = question.continuation ?: return null
        val encodedEnvelope = TriggerEnvelopeCodec.encode(envelope)
        val encodedContinuation = ProgramContinuationCodec.encode(continuation)
        if (encodedEnvelope.length + encodedContinuation.length > MAX_RESUME_CHARS) return null
        return encodedEnvelope to encodedContinuation
    }

    /** Fa proseguire il programma di una domanda rimasta senza chi la aspettava. false se non si può. */
    private suspend fun resumeOrphan(prompt: PendingPrompt, outcome: QuestionOutcome): Boolean {
        val resumed = try {
            ResumedQuestion(
                questionId = prompt.id,
                automationId = AutomationId(prompt.automationId),
                approvalFingerprint = ApprovalFingerprint(prompt.fingerprint),
                envelope = prompt.envelope?.let(TriggerEnvelopeCodec::decode) ?: return false,
                continuation = prompt.continuation?.let(ProgramContinuationCodec::decode) ?: return false,
                outcome = outcome,
            )
        } catch (_: IllegalArgumentException) {
            return false
        }
        runs.resume(resumed)
        return true
    }

    /**
     * Un tocco su un pulsante. Vale solo col token della domanda, prima della scadenza e col tipo di
     * risposta giusto (Esegui/Annulla per una conferma, l'indice di una scelta esistente per una domanda).
     * true se il tocco è stato accettato.
     */
    suspend fun handle(promptId: String, token: String?, answer: PromptAnswer): Boolean {
        val prompt = store.consume(promptId, token, nowMillis()) { candidate ->
            when (candidate.kind) {
                PromptKind.CONFIRM -> answer == PromptAnswer.Run || answer == PromptAnswer.Cancel
                PromptKind.ASK -> answer is PromptAnswer.Choice && answer.index in candidate.choices.indices
            }
        } ?: return false
        surface.dismiss(prompt.id)
        surface.cancelExpiry(prompt.id)
        when (prompt.kind) {
            PromptKind.CONFIRM -> {
                val automationId = AutomationId(prompt.automationId)
                val envelope = prompt.envelope?.let(TriggerEnvelopeCodec::decode)
                if (answer == PromptAnswer.Run && envelope != null) {
                    runs.run(
                        ConfirmedFire(
                            confirmationId = prompt.id,
                            automationId = automationId,
                            approvalFingerprint = ApprovalFingerprint(prompt.fingerprint),
                            envelope = envelope,
                        ),
                    )
                } else {
                    runs.closed(automationId, ConfirmationClosed.CANCELLED)
                }
            }
            PromptKind.ASK -> {
                val choice = prompt.choices[(answer as PromptAnswer.Choice).index]
                val waiter = waiters[prompt.id]
                if (waiter != null) {
                    waiter.complete(choice)
                } else {
                    // Processo morto e ripartito: la risposta fa proseguire il programma da quella domanda.
                    resumeOrphan(prompt, QuestionOutcome.Answered(choice))
                }
            }
        }
        return true
    }

    /**
     * All'avvio del processo e alla sveglia di scadenza: chiude le conferme scadute (codice chiuso, mai
     * un errore), rimostra quelle ancora valide (un riavvio cancella notifiche e sveglie). Una domanda
     * `ask` rimasta senza il programma che la aspettava: scaduta, fa proseguire il programma come senza
     * risposta; ancora valida, si rimostra; senza continuazione si toglie.
     */
    suspend fun sweep(repost: Boolean) {
        val now = nowMillis()
        for (prompt in store.all()) {
            try {
                when (prompt.kind) {
                    PromptKind.ASK -> when {
                        // Il programma è vivo in questo processo: la sua attesa decide.
                        waiters.containsKey(prompt.id) -> Unit
                        prompt.continuation == null || prompt.envelope == null -> {
                            store.remove(prompt.id)
                            surface.dismiss(prompt.id)
                        }
                        prompt.expired(now) -> if (store.remove(prompt.id)) {
                            surface.dismiss(prompt.id)
                            surface.cancelExpiry(prompt.id)
                            resumeOrphan(prompt, QuestionOutcome.TimedOut)
                        }
                        repost -> showAgain(prompt, now)
                    }
                    PromptKind.CONFIRM -> if (prompt.expired(now)) {
                        if (store.remove(prompt.id)) {
                            surface.dismiss(prompt.id)
                            surface.cancelExpiry(prompt.id)
                            runs.closed(AutomationId(prompt.automationId), ConfirmationClosed.EXPIRED)
                        }
                    } else if (repost) {
                        showAgain(prompt, now)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Una domanda illeggibile non ferma le altre.
            }
        }
    }

    /**
     * Rimostra una domanda ancora valida e ne riprogramma la scadenza. Se nel frattempo un tocco l'ha già
     * consumata (l'avvio del processo corre insieme al tocco che lo ha causato), la notifica si ritira.
     */
    private fun showAgain(prompt: PendingPrompt, now: Long) {
        if (!surface.show(prompt, now)) return
        if (store.get(prompt.id) == null) {
            surface.dismiss(prompt.id)
            return
        }
        surface.scheduleExpiry(prompt.id, prompt.expiresAtMillis)
    }

    /** Revoca della privacy: via ogni domanda e ogni notifica, nessuna esecuzione. */
    fun clearAll() {
        store.all().forEach { prompt ->
            surface.dismiss(prompt.id)
            surface.cancelExpiry(prompt.id)
        }
        store.clear()
        // Un errore (non una cancellazione): il programma si ferma con un codice chiuso, il dispatch no.
        waiters.values.forEach { it.completeExceptionally(IllegalStateException("prompts_cleared")) }
    }

    companion object {
        const val ASK_UNAVAILABLE = "ask_unavailable"
        const val ASK_NOTIFICATION_UNAVAILABLE = "ask_notification_unavailable"

        /** Tetto di evento + continuazione conservati con una domanda (le variabili arrivano a 64 KB l'una). */
        const val MAX_RESUME_CHARS = 64 * 1_024
    }
}
