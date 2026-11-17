package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ClockVariable
import dev.argus.engine.model.Condition
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.LiveSystemClock
import dev.argus.engine.model.LlmOutputPolicy
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SecretPolicy
import dev.argus.engine.model.StateValueCoercion
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import dev.argus.engine.model.declaredType
import dev.argus.engine.model.integrity
import dev.argus.engine.model.joinConfidentiality
import dev.argus.engine.model.provenance
import dev.argus.engine.safety.SafeExtractionRegex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.time.Clock
import java.util.Locale

/** Percorso user-readable, one-based, stabile nell'albero approvato. */
@JvmInline
value class ActionPath(val value: String) {
    init {
        require(PATH.matches(value)) { "Percorso azione non valido" }
    }

    internal fun branch(name: String, index: Int): ActionPath =
        ActionPath("$value.$name.${index + 1}")

    internal fun iteration(iteration: Int, index: Int): ActionPath =
        ActionPath("$value.while[$iteration].${index + 1}")

    /** true se questo percorso è [ancestor] o sta dentro di lui. */
    internal fun isWithin(ancestor: ActionPath): Boolean =
        value == ancestor.value || value.startsWith(ancestor.value + ".")

    /** Il segmento subito sotto [ancestor] (`then`, `else`, `while[3]`), o null. */
    internal fun segmentAfter(ancestor: ActionPath): String? {
        if (!value.startsWith(ancestor.value + ".")) return null
        return value.substring(ancestor.value.length + 1).substringBefore('.')
    }

    /** Risolve il path nell'albero approvato; il numero d'iterazione non modifica la struttura. */
    fun resolve(actions: List<Action>): Action? {
        val rootText = value.substringBefore('.')
        var current = actions.getOrNull((rootText.toIntOrNull() ?: return null) - 1) ?: return null
        var cursor = rootText.length
        while (cursor < value.length) {
            val match = SEGMENT.matchAt(value, cursor) ?: return null
            val childIndex = (match.groupValues[2].ifEmpty { match.groupValues[3] }.toIntOrNull()
                ?: return null) - 1
            current = when (match.groupValues[1]) {
                "then" -> (current as? Action.If)?.then?.getOrNull(childIndex)
                "else" -> (current as? Action.If)?.orElse?.getOrNull(childIndex)
                else -> (current as? Action.While)?.body?.getOrNull(childIndex)
            } ?: return null
            cursor = match.range.last + 1
        }
        return current
    }

    companion object {
        private val PATH = Regex("^[1-9][0-9]*(?:\\.(?:then|else)\\.[1-9][0-9]*|\\.while\\[[1-9][0-9]*\\]\\.[1-9][0-9]*)*$")
        private val SEGMENT = Regex("\\.(then|else)\\.([1-9][0-9]*)|\\.while\\[[1-9][0-9]*\\]\\.([1-9][0-9]*)")
        internal fun root(index: Int): ActionPath = ActionPath((index + 1).toString())
    }
}

data class ProgramActionResult(
    val result: ActionResult,
    /** Presente solo per una capture completata realmente; mai per SUBMITTED. */
    val capturedText: String? = null,
) {
    override fun toString(): String =
        "ProgramActionResult(result=$result, capturedText=${if (capturedText == null) "<absent>" else "<redacted>"})"
}

fun interface ProgramActionRunner {
    suspend fun execute(action: ResolvedProgramAction, path: ActionPath): ProgramActionResult
}

data class ProgramJournalEntry(
    val path: ActionPath,
    val actionType: String,
    val outcome: ActionJournalOutcome,
    val errorCode: String? = null,
)

fun interface ProgramExecutionJournal {
    suspend fun record(entry: ProgramJournalEntry)
}

object NoopProgramExecutionJournal : ProgramExecutionJournal {
    override suspend fun record(entry: ProgramJournalEntry) = Unit
}

/**
 * Una domanda `ask` già risolta (0.8): il testo approvato con i segnaposto riempiti, le etichette
 * letterali e l'attesa massima. Il [path] la distingue dalle altre domande della stessa esecuzione.
 */
class ProgramQuestion(
    val path: ActionPath,
    val prompt: String,
    val choices: List<String>,
    val timeoutMs: Long,
    /**
     * Dove il programma si è fermato e con quali variabili: chi pone la domanda la conserva (cifrata) per
     * farlo proseguire con [ProgramInterpreter.resume] se il processo muore prima della risposta.
     */
    val continuation: ProgramContinuation? = null,
    /** 0.8 «Prova a secco»: le parti di [prompt] che vengono da un valore TAINTED (vedi [ResolvedProgramAction.taintedRanges]). */
    val promptTainted: List<IntRange> = emptyList(),
) {
    override fun toString(): String =
        "ProgramQuestion(path=${path.value}, prompt=<redacted>, choices=${choices.size}, timeoutMs=$timeoutMs)"
}

/**
 * 0.8 «Prova a secco»: osserva, in ordine, ciò che il programma decide (le condizioni di `if` e `while`) e ogni
 * passo registrato. Solo osservabilità: non cambia l'esito. Default: nessun osservatore.
 */
interface ProgramObserver {
    /** La condizione dell'`if` o del `while` (con [loop]) in [path] è stata valutata con [result]. */
    fun onCondition(path: ActionPath, loop: Boolean, result: ConditionEvaluator.Result) {}

    /** Un passo del programma (foglia eseguita, bloccata o fallita, attesa, domanda) è stato registrato. */
    fun onStep(step: ProgramStep) {}

    companion object {
        val NONE: ProgramObserver = object : ProgramObserver {}
    }
}

sealed interface QuestionOutcome {
    /** L'utente ha toccato un pulsante: [choice] è l'etichetta del pulsante (l'interprete la ricontrolla). */
    data class Answered(val choice: String) : QuestionOutcome

    /** Nessuna risposta entro l'attesa approvata. */
    data object TimedOut : QuestionOutcome

    /** La domanda non si può porre (notifiche spente, archivio non scrivibile…): codice chiuso. */
    data class Unavailable(val code: String) : QuestionOutcome
}

/**
 * Porta delle domande `ask`. L'attesa è cooperativa: chi la implementa sospende senza tenere il
 * processore sveglio (come `wait`) e torna al primo fra risposta e scadenza.
 */
fun interface ProgramQuestions {
    suspend fun ask(question: ProgramQuestion): QuestionOutcome

    companion object {
        const val UNAVAILABLE_CODE = "ask_unavailable"

        /** Default senza runtime: nessuna domanda si pone, il programma si ferma chiuso. */
        val UNAVAILABLE = ProgramQuestions { QuestionOutcome.Unavailable(UNAVAILABLE_CODE) }
    }
}

/** Di quale regola ed esecuzione è una domanda: la notifica e il suo token sono per questa esecuzione. */
class QuestionContext(
    val automationId: dev.argus.engine.model.AutomationId,
    val ruleName: String,
    val approvalFingerprint: dev.argus.engine.model.ApprovalFingerprint,
    val executionId: ExecutionId,
    val eventId: TriggerEventId,
    /** L'evento che ha fatto scattare la regola: serve per riprendere il programma dopo un riavvio. */
    val envelope: TriggerEnvelope? = null,
) {
    override fun toString(): String =
        "QuestionContext(automationId=${automationId.value}, executionId=${executionId.value}, event=<redacted>)"
}

/** Porta Android di `ask` a livello di motore: l'Engine la lega a regola ed esecuzione. */
fun interface QuestionAsker {
    suspend fun ask(context: QuestionContext, question: ProgramQuestion): QuestionOutcome

    companion object {
        val UNAVAILABLE = QuestionAsker { _, _ -> QuestionOutcome.Unavailable(ProgramQuestions.UNAVAILABLE_CODE) }
    }
}

data class ProgramStep(
    val path: ActionPath,
    val actionType: String,
    val result: ActionResult,
)

data class ProgramExecutionResult(
    val steps: List<ProgramStep>,
    /** null = programma completato; altrimenti codice bounded e senza payload. */
    val stopCode: String? = null,
    val stopPath: ActionPath? = null,
) {
    val completed: Boolean get() = stopCode == null
}

/**
 * Interprete deterministico P4-B. Risolve i binding una volta, rilegge lo stato a ogni condizione
 * di flusso, esegue solo foglie risolte e mantiene una deadline dura di sei ore.
 */
class ProgramInterpreter(
    private val runner: ProgramActionRunner,
    private val stateProvider: suspend (StateReadRequest) -> DeviceState,
    private val conditionEvaluator: ConditionEvaluator,
    private val interpolator: TaintAwareInterpolator = TaintAwareInterpolator(),
    private val journal: ProgramExecutionJournal = NoopProgramExecutionJournal,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    // Seam per la casualità (come il Clock in ConditionEvaluator): default di produzione, override
    // deterministico nei test. Il bound è sempre >= 1 (garantito da resolveBinding + DraftValidator).
    private val randomInt: (Int) -> Int = { bound -> kotlin.random.Random.nextInt(bound) },
    // 0.5.0 (variabile `clock`): orologio e lingua iniettati, così i test sono deterministici. Mai
    // System.currentTimeMillis diretto. Il default è l'orologio vivo del telefono.
    private val clock: Clock = LiveSystemClock,
    private val locale: () -> Locale = ClockVariable::appLocale,
    /** 0.8: le domande `ask` (notifica con un pulsante per scelta). */
    private val questions: ProgramQuestions = ProgramQuestions.UNAVAILABLE,
    /** 0.8 «Prova a secco»: chi osserva le decisioni e i passi (mai un effetto). */
    private val observer: ProgramObserver = ProgramObserver.NONE,
) {
    suspend fun execute(
        trigger: Trigger,
        event: TriggerEvent,
        bindings: List<VarBinding>,
        actions: List<Action>,
    ): ProgramExecutionResult {
        if (!validProgram(bindings, actions)) {
            return ProgramExecutionResult(emptyList(), stopCode = "invalid_program")
        }
        if (!TriggerMatcher().matches(trigger, event)) {
            return ProgramExecutionResult(emptyList(), stopCode = "trigger_mismatch")
        }
        val runtime = Runtime(trigger, event)
        return runtime.run(MAX_EXECUTION_MILLIS) {
            // L'inizio si legge dopo i binding: la variabile `clock` resta la prima lettura dell'orologio.
            runtime.initialize(bindings) && runtime.markStarted() &&
                runtime.runActions(actions, ActionPath::root, depth = 0)
        }
    }

    /**
     * 0.8 `ask`: riprende un programma fermo a una domanda il cui processo è morto. [continuation] deve
     * puntare a un `ask` di QUESTO programma approvato e portare solo variabili che il programma dichiara;
     * altrimenti si ferma con [ASK_RESUME_INVALID] senza eseguire nulla. Le azioni prima della domanda non
     * si ripetono, i rami e i giri attraversati non si rivalutano; poi la risposta ([outcome]) vale come se
     * fosse arrivata al programma vivo e il resto prosegue normalmente, entro le sei ore dall'inizio originale.
     */
    suspend fun resume(
        trigger: Trigger,
        event: TriggerEvent,
        bindings: List<VarBinding>,
        actions: List<Action>,
        continuation: ProgramContinuation,
        outcome: QuestionOutcome,
    ): ProgramExecutionResult {
        if (!validProgram(bindings, actions)) {
            return ProgramExecutionResult(emptyList(), stopCode = "invalid_program")
        }
        if (!TriggerMatcher().matches(trigger, event)) {
            return ProgramExecutionResult(emptyList(), stopCode = "trigger_mismatch")
        }
        val declared = bindings.map { it.name }.toSet() + captureNames(actions)
        if (continuation.askPath.resolve(actions) !is Action.Ask ||
            !declared.containsAll(continuation.variables.keys) ||
            outcome is QuestionOutcome.Unavailable
        ) {
            return ProgramExecutionResult(emptyList(), stopCode = ASK_RESUME_INVALID, stopPath = continuation.askPath)
        }
        val remaining = MAX_EXECUTION_MILLIS - (clock.millis() - continuation.startedAtMillis)
        if (remaining <= 0) {
            return ProgramExecutionResult(emptyList(), stopCode = "deadline_exceeded", stopPath = continuation.askPath)
        }
        val runtime = Runtime(trigger, event)
        return runtime.run(remaining) {
            runtime.restore(continuation, outcome) && runtime.runActions(actions, ActionPath::root, depth = 0)
        }
    }

    private inner class Runtime(
        private val trigger: Trigger,
        private val event: TriggerEvent,
    ) {
        private val scope = VarScope()

        /** Inizio dell'esecuzione sull'orologio del programma (quello originale in una ripresa). */
        private var startedAtMillis = 0L

        /** I `while` in corso, dal più esterno: entrano nella continuazione di una domanda. */
        private val activeLoops = LinkedHashMap<String, LoopPosition>()

        /** Ripresa ([resume]): la domanda a cui tornare e la sua risposta; null quando il programma è ripartito. */
        private var resumeTarget: ProgramContinuation? = null
        private var resumeOutcome: QuestionOutcome? = null
        private val steps = mutableListOf<ProgramStep>()
        var stopCode: String? = null
            private set
        var stopPath: ActionPath? = null
            private set
        var currentPath: ActionPath? = null
            private set

        // B27: il journal e' solo osservabilita' (la UI). Oltre MAX_JOURNAL_ENTRIES si scrivono solo
        // le voci che non sono riuscite; lo stato in memoria (steps) resta completo.
        private var journalWritten = 0
        private var omittedCount = 0
        private var firstOmittedPath: ActionPath? = null

        /** Chiamate AI già partite in questa esecuzione (tetto [MAX_GENERATIVE_CALLS_PER_EXECUTION]). */
        private var generativeCalls = 0

        suspend fun run(timeoutMillis: Long, body: suspend () -> Boolean): ProgramExecutionResult {
            val completed = try {
                withTimeoutOrNull(timeoutMillis) { body() }
            } finally {
                // Anche se l'esecuzione si ferma o viene cancellata, il Log deve dire quante voci mancano.
                withContext(NonCancellable) { flushOmittedSummary() }
            }
            return when {
                completed == null -> result("deadline_exceeded", currentPath)
                stopCode != null -> result(stopCode, stopPath)
                else -> result(null, null)
            }
        }

        fun markStarted(): Boolean {
            startedAtMillis = clock.millis()
            return true
        }

        /** Ripresa: le variabili con le loro label, i giri e le chiamate AI già contate. */
        fun restore(continuation: ProgramContinuation, outcome: QuestionOutcome): Boolean {
            try {
                continuation.variables.forEach(scope::assign)
            } catch (_: IllegalArgumentException) {
                return stop(ASK_RESUME_INVALID, continuation.askPath)
            }
            generativeCalls = continuation.generativeCalls
            startedAtMillis = continuation.startedAtMillis
            resumeTarget = continuation
            resumeOutcome = outcome
            return true
        }

        suspend fun initialize(bindings: List<VarBinding>): Boolean {
            if (bindings.size > MAX_VARIABLES) return stop("invalid_program", null)
            val stateBindings = bindings.filterIsInstance<VarBinding.State>()
            // 0.8: i valori conservati si leggono nella stessa lettura, una volta sola a inizio esecuzione.
            val storedKeys = bindings.filterIsInstance<VarBinding.Stored>().mapTo(linkedSetOf()) { it.key }
            val state = if (stateBindings.isEmpty() && storedKeys.isEmpty()) {
                DeviceState()
            } else {
                try {
                    stateProvider(
                        StateReadRequest(
                            queries = stateBindings.mapTo(linkedSetOf()) { it.query },
                            storedKeys = storedKeys,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return stop("binding_state_unavailable", null)
                }
            }
            for (binding in bindings) {
                val value = resolveBinding(binding, state) ?: return stop("binding_unavailable", null)
                try {
                    scope.assign(binding.name, value)
                } catch (_: IllegalArgumentException) {
                    return stop("invalid_program", null)
                }
            }
            return true
        }

        suspend fun runActions(
            actions: List<Action>,
            pathFor: (Int) -> ActionPath,
            depth: Int,
        ): Boolean {
            if (depth > MAX_FLOW_DEPTH || actions.size > MAX_ACTION_NODES) {
                return stop("invalid_program", null)
            }
            for ((index, action) in actions.withIndex()) {
                val path = pathFor(index)
                // Ripresa: le azioni prima della domanda le ha già eseguite il programma originale.
                val resuming = resumeTarget?.askPath
                if (resuming != null && !resuming.isWithin(path)) continue
                when (action) {
                    is Action.If -> {
                        val branch = if (resuming != null) {
                            // Dentro il ramo già scelto: la condizione non si rivaluta.
                            when (resuming.segmentAfter(path)) {
                                "then" -> action.then to "then"
                                "else" -> action.orElse to "else"
                                else -> return stop(ASK_RESUME_INVALID, path)
                            }
                        } else {
                            when (evaluate(action.condition, path).also { observer.onCondition(path, false, it) }) {
                                ConditionEvaluator.Result.MET -> action.then to "then"
                                ConditionEvaluator.Result.NOT_MET -> action.orElse to "else"
                                ConditionEvaluator.Result.STATE_UNAVAILABLE ->
                                    return stop("condition_state_unavailable", path)
                            }
                        }
                        if (!runActions(branch.first, { child -> path.branch(branch.second, child) }, depth + 1)) {
                            return false
                        }
                    }
                    is Action.While -> {
                        if (action.delayBetweenMs !in 0..MAX_PAUSE_MILLIS || action.body.isEmpty() ||
                            !whileCountWellFormed(action)
                        ) {
                            return stop("invalid_program", path)
                        }
                        // Conteggio effettivo: letterale, oppure variabile NUMBER letta a runtime e
                        // clampata in 1..1000. Variabile assente/non coercibile ⇒ fail-closed (nessuna
                        // iterazione con conteggio ignoto). In ripresa: quello deciso all'ingresso originale.
                        val resumedLoop = resuming?.let { target ->
                            val position = resumeTarget?.loops?.get(path.value)
                            if (position == null || position.effective > MAX_WHILE_ITERATIONS ||
                                target.segmentAfter(path) != "while[${position.iteration}]"
                            ) {
                                return stop(ASK_RESUME_INVALID, path)
                            }
                            position
                        }
                        val effective: Int = resumedLoop?.effective ?: action.maxIterationsVar?.let { varName ->
                            val n = scope.get(varName)?.text?.let(StateValueCoercion::integer)
                                ?: return stop("while_count_unavailable", path)
                            n.coerceIn(1L, MAX_WHILE_ITERATIONS.toLong()).toInt()
                        } ?: action.maxIterations ?: return stop("invalid_program", path)
                        var iteration = resumedLoop?.iteration ?: 1
                        // Il giro della domanda era già cominciato: la sua condizione non si rivaluta.
                        var skipCheck = resumedLoop != null
                        try {
                            while (iteration <= effective) {
                                if (!skipCheck) {
                                    when (evaluate(action.condition, path).also { observer.onCondition(path, true, it) }) {
                                        ConditionEvaluator.Result.NOT_MET -> break
                                        ConditionEvaluator.Result.STATE_UNAVAILABLE ->
                                            return stop("condition_state_unavailable", path)
                                        ConditionEvaluator.Result.MET -> Unit
                                    }
                                }
                                skipCheck = false
                                activeLoops[path.value] = LoopPosition(iteration, effective)
                                if (!runActions(
                                        action.body,
                                        { child -> path.iteration(iteration, child) },
                                        depth + 1,
                                    )
                                ) {
                                    return false
                                }
                                if (iteration < effective && action.delayBetweenMs > 0) {
                                    currentPath = path
                                    pause(action.delayBetweenMs)
                                    currentPath = null
                                }
                                iteration += 1
                            }
                        } finally {
                            activeLoops.remove(path.value)
                        }
                    }
                    is Action.Wait -> {
                        if (resuming != null) return stop(ASK_RESUME_INVALID, path)
                        if (action.durationMs !in 1..MAX_PAUSE_MILLIS) {
                            return stop("invalid_program", path)
                        }
                        currentPath = path
                        pause(action.durationMs)
                        currentPath = null
                        record(path, action, ActionResult.Success)
                    }
                    is Action.Ask -> if (resuming != null) {
                        // La domanda a cui torna la ripresa: la risposta è già arrivata.
                        if (resuming != path) return stop(ASK_RESUME_INVALID, path)
                        val outcome = resumeOutcome ?: return stop(ASK_RESUME_INVALID, path)
                        resumeTarget = null
                        resumeOutcome = null
                        if (!applyAnswer(action, path, outcome)) return false
                    } else if (!runAsk(action, path)) {
                        return false
                    }
                    else -> if (resuming != null) {
                        return stop(ASK_RESUME_INVALID, path)
                    } else if (!runLeaf(action, path)) {
                        return false
                    }
                }
            }
            return true
        }

        private suspend fun runLeaf(action: Action, path: ActionPath): Boolean {
            // S10: un `while` intorno a una chiamata AI non può arrivare a 1000 chiamate per scatto.
            if (action is Action.InvokeLlm || action is Action.InvokeLlmV2) {
                if (generativeCalls >= MAX_GENERATIVE_CALLS_PER_EXECUTION) {
                    record(path, action, ActionResult.Failure(GENERATIVE_CALL_LIMIT))
                    return stop(GENERATIVE_CALL_LIMIT, path)
                }
                generativeCalls += 1
            }
            val resolved = when (val resolution = interpolator.resolve(action, scope)) {
                is ActionResolution.Blocked -> {
                    record(path, action, ActionResult.Failure(resolution.code))
                    return stop(resolution.code, path)
                }
                is ActionResolution.Resolved -> resolution.value
            }
            currentPath = path
            val executed = try {
                runner.execute(resolved, path).also { currentPath = null }
            } catch (error: CancellationException) {
                // Mantieni currentPath: se è la deadline interna, il result indica la foglia
                // interrotta; se è cancellazione esterna, l'eccezione continua a propagare.
                throw error
            } catch (_: Exception) {
                currentPath = null
                ProgramActionResult(ActionResult.Failure("executor_exception"))
            }

            val captureName = captureName(action)
            var result = executed.result
            if (captureName != null && result !is ActionResult.Failure) {
                if (result != ActionResult.Success || executed.capturedText == null) {
                    result = ActionResult.Failure("capture_missing")
                    record(path, action, result)
                    return stop("capture_missing", path)
                }
                // 0.8: una risposta vincolata si ricontrolla qui, l'ultima difesa prima della variabile CLEAN.
                val output = (action as? Action.InvokeLlm)?.output
                val captured = if (output != null) {
                    val canonical = LlmOutputPolicy.normalize(output, executed.capturedText)
                    if (canonical == null) {
                        record(path, action, ActionResult.Failure(LlmOutputPolicy.OUTPUT_INVALID))
                        return stop(LlmOutputPolicy.OUTPUT_INVALID, path)
                    }
                    VarValue(
                        text = canonical,
                        type = LlmOutputPolicy.varType(output),
                        integrity = IntegrityLabel.CLEAN,
                        confidentiality = ConfidentialityLabel.PRIVATE,
                        provenance = resolved.inputProvenance + ValueProvenance.MODEL,
                    )
                } else {
                    capturedValue(action, resolved, executed.capturedText)
                }
                try {
                    scope.assign(captureName, captured)
                } catch (_: IllegalArgumentException) {
                    result = ActionResult.Failure("capture_too_long")
                    record(path, action, result)
                    return stop("capture_too_long", path)
                }
            }
            record(path, action, result)
            return true // D7: un fallimento foglia non impedisce le successive.
        }

        /**
         * `ask` (0.8): pone la domanda risolta e aspetta la risposta senza passare dal runner. Il valore
         * catturato è sempre una delle etichette APPROVATE (quelle del template, non quelle risolte), quindi
         * CLEAN e PUBLIC; una risposta fuori elenco ferma il programma. Senza risposta vale la scelta
         * predefinita, altrimenti il programma si ferma con [ASK_TIMEOUT].
         */
        private suspend fun runAsk(action: Action.Ask, path: ActionPath): Boolean {
            val resolvedLeaf = when (val resolution = interpolator.resolve(action, scope)) {
                is ActionResolution.Blocked -> {
                    record(path, action, ActionResult.Failure(resolution.code))
                    return stop(resolution.code, path)
                }
                is ActionResolution.Resolved -> resolution.value
            }
            val resolved = resolvedLeaf.action as? Action.Ask ?: return stop("resolved_action_invalid", path)
            currentPath = path
            val continuation = ProgramContinuation(
                askPath = path,
                variables = scope.snapshot(),
                loops = LinkedHashMap(activeLoops),
                generativeCalls = generativeCalls,
                startedAtMillis = startedAtMillis,
            )
            val outcome = try {
                questions.ask(
                    ProgramQuestion(
                        path,
                        resolved.prompt,
                        action.choices,
                        action.timeoutMs,
                        continuation,
                        promptTainted = resolvedLeaf.taintedRanges["prompt"].orEmpty(),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                QuestionOutcome.Unavailable(ASK_FAILED)
            }
            currentPath = null
            return applyAnswer(action, path, outcome)
        }

        /** La risposta di una domanda (dal programma vivo o da una ripresa) diventa la variabile approvata. */
        private suspend fun applyAnswer(action: Action.Ask, path: ActionPath, outcome: QuestionOutcome): Boolean {
            val (choice, result) = when (outcome) {
                is QuestionOutcome.Answered -> {
                    if (outcome.choice !in action.choices) {
                        record(path, action, ActionResult.Failure(ASK_ANSWER_INVALID))
                        return stop(ASK_ANSWER_INVALID, path)
                    }
                    outcome.choice to ActionResult.Success
                }
                QuestionOutcome.TimedOut -> {
                    val default = action.defaultChoice?.takeIf { it in action.choices }
                    if (default == null) {
                        record(path, action, ActionResult.Failure(ASK_TIMEOUT))
                        return stop(ASK_TIMEOUT, path)
                    }
                    default to ActionResult.SuccessWithDetail(ASK_TIMEOUT_DEFAULT)
                }
                is QuestionOutcome.Unavailable -> {
                    val code = journalErrorCode(outcome.code)
                    record(path, action, ActionResult.Failure(code))
                    return stop(code, path)
                }
            }
            try {
                scope.assign(
                    action.captureAs,
                    VarValue(
                        text = choice,
                        type = VarType.TEXT,
                        integrity = IntegrityLabel.CLEAN,
                        confidentiality = ConfidentialityLabel.PUBLIC,
                        provenance = setOf(ValueProvenance.USER),
                    ),
                )
            } catch (_: IllegalArgumentException) {
                record(path, action, ActionResult.Failure("capture_too_long"))
                return stop("capture_too_long", path)
            }
            record(path, action, result)
            return true
        }

        private suspend fun evaluate(
            condition: Condition,
            path: ActionPath,
        ): ConditionEvaluator.Result {
            currentPath = path
            val request = StateReadPlanner.forCondition(condition)
            val state = if (request.isEmpty) {
                DeviceState()
            } else {
                try {
                    stateProvider(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    DeviceState()
                }
            }
            return conditionEvaluator.flowResult(condition, state, scope::get).also {
                currentPath = null
            }
        }

        private fun resolveBinding(binding: VarBinding, state: DeviceState): VarValue? {
            // 0.8: un valore conservato porta le etichette che aveva quando è stato scritto. Una chiave
            // assente o di un altro tipo rende la variabile non disponibile, come uno stato illeggibile.
            val saved = (binding as? VarBinding.Stored)?.let { stored ->
                state.stored[stored.key]?.takeIf { it.type == stored.valueType } ?: return null
            }
            val raw = when (binding) {
                is VarBinding.Literal -> binding.value
                // Intero casuale generato dal motore in [0, max). Difesa in profondità: max < 1 non
                // è realizzabile (Random.nextInt lancerebbe) → null ⇒ binding_unavailable.
                is VarBinding.RandomInt ->
                    if (binding.max >= 1) randomInt(binding.max).toString() else null
                is VarBinding.State -> state.queryValues[binding.query.canonicalId]
                    ?.takeIf { StateValueCoercion.compatible(it, binding.valueType) }
                is VarBinding.ClockTime -> ClockVariable.format(binding, clock, locale())
                is VarBinding.Stored -> saved?.text
                is VarBinding.TriggerPayload -> triggerValue(binding.field)?.let { payload ->
                    val pattern = binding.extractionRegex
                    if (pattern == null) {
                        payload
                    } else {
                        when (val extracted = SafeExtractionRegex.extract(pattern, payload)) {
                            is SafeExtractionRegex.Result.Match -> extracted.value
                            SafeExtractionRegex.Result.NoMatch,
                            SafeExtractionRegex.Result.InvalidPattern,
                            -> null
                        }
                    }
                }
            } ?: return null
            if (raw.length > VarScope.MAX_RUNTIME_VALUE_CHARS) return null
            return VarValue(
                text = normalize(raw, binding.declaredType) ?: return null,
                type = binding.declaredType,
                integrity = saved?.integrity ?: binding.integrity,
                confidentiality = saved?.let { joinConfidentiality(binding.confidentiality, it.confidentiality) }
                    ?: binding.confidentiality,
                provenance = binding.provenance(trigger),
            )
        }

        private fun triggerValue(field: TriggerField): String? {
            val current = event
            return when {
                trigger is Trigger.Notification && current is TriggerEvent.NotificationPosted ->
                    when (field) {
                        TriggerField.TEXT -> current.text
                        TriggerField.TITLE -> current.title
                        TriggerField.SENDER -> current.sender
                        TriggerField.NUMBER -> null
                    }
                trigger is Trigger.PhoneState && current is TriggerEvent.PhoneStateChanged &&
                    trigger.event == current.event -> when (trigger.event) {
                        PhoneEvent.SMS_RECEIVED -> when (field) {
                            TriggerField.TEXT -> current.smsText
                            TriggerField.NUMBER -> current.number
                            TriggerField.TITLE, TriggerField.SENDER -> null
                        }
                        PhoneEvent.INCOMING_CALL,
                        PhoneEvent.CALL_ENDED,
                        PhoneEvent.MISSED_CALL,
                        -> if (field == TriggerField.NUMBER) current.number else null
                    }
                // 0.8: il titolo dell'evento di calendario che ha fatto scattare la regola.
                trigger is Trigger.CalendarEvent && current is TriggerEvent.CalendarEventFired ->
                    if (field == TriggerField.TITLE) current.title else null
                else -> null
            }
        }

        private suspend fun record(path: ActionPath, action: Action, result: ActionResult) {
            val step = ProgramStep(path, action.journalType(), result)
            steps += step
            observer.onStep(step)
            val outcome = result.journalOutcome()
            // Una voce non riuscita (FAILED) o ancora aperta (SUBMITTED/DEFERRED) si scrive sempre: la
            // risoluzione asincrona delle azioni generative rilegge proprio quelle righe.
            if (journalWritten >= MAX_JOURNAL_ENTRIES && outcome == ActionJournalOutcome.SUCCEEDED) {
                omittedCount += 1
                if (firstOmittedPath == null) firstOmittedPath = path
                return
            }
            journalWritten += 1
            writeJournal(
                ProgramJournalEntry(
                    path = path,
                    actionType = step.actionType,
                    outcome = outcome,
                    errorCode = result.journalCode(),
                ),
            )
        }

        /**
         * Riepilogo finale delle voci non scritte. Usa il path del primo passaggio omesso: e' un path
         * valido e, non avendo una riga sua, non ne sovrascrive nessuna.
         */
        suspend fun flushOmittedSummary() {
            val path = firstOmittedPath ?: return
            val count = omittedCount
            firstOmittedPath = null
            omittedCount = 0
            writeJournal(
                ProgramJournalEntry(
                    path = path,
                    actionType = OmittedSteps.ACTION_TYPE,
                    outcome = ActionJournalOutcome.SUCCEEDED,
                    errorCode = OmittedSteps.code(count),
                ),
            )
        }

        private suspend fun writeJournal(entry: ProgramJournalEntry) {
            try {
                journal.record(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Come il journal flat: l'osservabilità non cambia l'esito del programma.
            }
        }

        private fun stop(code: String, path: ActionPath?): Boolean {
            stopCode = code
            stopPath = path
            return false
        }

        fun result(code: String?, path: ActionPath?): ProgramExecutionResult =
            ProgramExecutionResult(steps.toList(), code, path)
    }

    private fun capturedValue(
        action: Action,
        resolved: ResolvedProgramAction,
        text: String,
    ): VarValue {
        val producer = when (action) {
            is Action.RunShell -> ValueProvenance.SHELL
            is Action.InvokeLlm, is Action.InvokeLlmV2 -> ValueProvenance.MODEL
            // 0.8 scaffold: la scelta dell'utente e la risposta di un server.
            is Action.Ask -> ValueProvenance.USER
            is Action.HttpRequest -> ValueProvenance.NETWORK
            else -> error("Azione non catturabile")
        }
        val provenance = buildSet {
            addAll(resolved.inputProvenance)
            if (action is Action.InvokeLlmV2 && action.stateContext.isNotEmpty()) {
                add(ValueProvenance.DEVICE_STATE)
            }
            add(producer)
        }
        return VarValue(
            // Come `$(…)` in shell: il newline finale dell'output non fa parte del valore.
            text = if (action is Action.RunShell) text.removeSuffix("\n").removeSuffix("\r") else text,
            type = VarType.TEXT,
            integrity = IntegrityLabel.TAINTED,
            confidentiality = capturedConfidentiality(action, resolved),
            provenance = provenance,
        )
    }

    /**
     * Floor conservativo P4-A: output shell/model può riflettere qualunque input segreto. 0.8: la risposta
     * di un server è SECRET solo se la richiesta ha portato un valore della cassaforte o una variabile
     * SECRET, altrimenti PRIVATE (resta sempre almeno PRIVATE: è un dato esterno).
     */
    private fun capturedConfidentiality(action: Action, resolved: ResolvedProgramAction): ConfidentialityLabel =
        when (action) {
            is Action.HttpRequest -> if (SecretPolicy.carriesSecrets(action)) {
                ConfidentialityLabel.SECRET
            } else {
                joinConfidentiality(ConfidentialityLabel.PRIVATE, resolved.inputConfidentiality)
            }
            else -> ConfidentialityLabel.SECRET
        }

    private fun captureName(action: Action): String? = when (action) {
        is Action.RunShell -> action.captureAs
        is Action.InvokeLlm -> action.captureAs
        is Action.InvokeLlmV2 -> action.captureAs
        is Action.Ask -> action.captureAs
        is Action.HttpRequest -> action.captureAs
        else -> null
    }

    /** Le variabili prodotte dalle azioni del programma, in ogni ramo e ciclo. */
    private fun captureNames(actions: List<Action>): Set<String> = buildSet {
        val pending = ArrayDeque(actions)
        while (pending.isNotEmpty()) {
            val action = pending.removeFirst()
            captureName(action)?.let(::add)
            when (action) {
                is Action.If -> {
                    pending.addAll(action.then)
                    pending.addAll(action.orElse)
                }
                is Action.While -> pending.addAll(action.body)
                else -> Unit
            }
        }
    }

    /** Defense in depth iterativa: anche un ramo non scelto deve rispettare tutti i bound. */
    private fun validProgram(bindings: List<VarBinding>, actions: List<Action>): Boolean {
        if (bindings.size > MAX_VARIABLES) return false
        val names = bindings.map { it.name }
        if (names.any { !VarBinding.NAME_REGEX.matches(it) } || names.size != names.toSet().size) {
            return false
        }
        data class Frame(val action: Action, val depth: Int)
        val pending = ArrayDeque<Frame>()
        actions.forEach { pending.addLast(Frame(it, 0)) }
        val captures = mutableListOf<String>()
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (action, depth) = pending.removeFirst()
            nodes += 1
            if (nodes > MAX_ACTION_NODES || depth > MAX_FLOW_DEPTH) return false
            captureName(action)?.let(captures::add)
            when (action) {
                is Action.If -> {
                    if (action.then.isEmpty() && action.orElse.isEmpty()) return false
                    action.then.forEach { pending.addLast(Frame(it, depth + 1)) }
                    action.orElse.forEach { pending.addLast(Frame(it, depth + 1)) }
                }
                is Action.While -> {
                    if (action.body.isEmpty() || !whileCountWellFormed(action) ||
                        action.delayBetweenMs !in 0..MAX_PAUSE_MILLIS
                    ) {
                        return false
                    }
                    action.body.forEach { pending.addLast(Frame(it, depth + 1)) }
                }
                is Action.Wait -> if (action.durationMs !in 1..MAX_PAUSE_MILLIS) return false
                else -> Unit
            }
        }
        if (captures.any { !VarBinding.NAME_REGEX.matches(it) }) return false
        val allNames = names + captures
        return allNames.size <= MAX_VARIABLES && allNames.size == allNames.toSet().size
    }

    /**
     * XOR sul conteggio del while: esattamente uno tra maxIterations (letterale 1..1000) e
     * maxIterationsVar (nome ben formato). Il valore effettivo della variabile è risolto e clampato a
     * runtime; qui vale solo la forma statica (defense-in-depth, il DraftValidator è l'autorità a monte).
     */
    private fun whileCountWellFormed(action: Action.While): Boolean {
        val literal = action.maxIterations
        val varName = action.maxIterationsVar
        if ((literal == null) == (varName == null)) return false
        if (literal != null && literal !in 1..MAX_WHILE_ITERATIONS) return false
        if (varName != null && !VarBinding.NAME_REGEX.matches(varName)) return false
        return true
    }

    /**
     * NUMBER e BOOLEAN entrano nei template in forma CANONICA, mai come la stringa grezza che è solo
     * riuscita a passare il parsing (S1): un numero diventa `-?[0-9]+(\.[0-9]+)?`, un booleano
     * `true`/`false`. Un numero che non si scrive così resta non disponibile, come un valore non
     * convertibile.
     */
    private fun normalize(raw: String, type: VarType): String? = when (type) {
        VarType.TEXT -> raw
        VarType.NUMBER -> StateValueCoercion.number(raw)?.let(::canonicalNumber)
        VarType.BOOLEAN -> StateValueCoercion.boolean(raw)?.toString()
    }

    private fun canonicalNumber(value: Double): String? {
        val plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        val text = if (plain == "-0") "0" else plain
        return text.takeIf { it.length <= MAX_CANONICAL_NUMBER_CHARS && CANONICAL_NUMBER.matches(it) }
    }

    companion object {
        const val MAX_EXECUTION_MILLIS = 6L * 60 * 60 * 1_000
        private const val MAX_VARIABLES = 16
        private const val MAX_FLOW_DEPTH = 4
        private const val MAX_ACTION_NODES = 64
        private const val MAX_WHILE_ITERATIONS = 1_000
        private const val MAX_PAUSE_MILLIS = 3_600_000L
        /** Voci di journal scritte per esecuzione prima di tenere solo quelle non riuscite (B27). */
        const val MAX_JOURNAL_ENTRIES = 100

        /**
         * Tetto duro alle chiamate AI (invoke_llm) di una sola esecuzione: oltre, il programma si ferma
         * con [GENERATIVE_CALL_LIMIT], un codice di policy. È anche il caso peggiore mostrato in
         * approvazione per una regola con una chiamata AI dentro un ciclo.
         */
        const val MAX_GENERATIVE_CALLS_PER_EXECUTION = 30
        const val GENERATIVE_CALL_LIMIT = "generative_call_limit"

        /** 0.8 `ask`: nessuna risposta e nessuna scelta predefinita. Non è un guasto: l'utente non ha risposto. */
        const val ASK_TIMEOUT = "ask_timeout"

        /** 0.8 `ask`: nessuna risposta, vale la scelta predefinita approvata. */
        const val ASK_TIMEOUT_DEFAULT = "ask_timeout_default"

        /** 0.8 `ask`: la risposta arrivata non è una delle etichette approvate. */
        const val ASK_ANSWER_INVALID = "ask_answer_invalid"

        /** 0.8 `ask`: la porta delle domande ha lanciato un errore. */
        const val ASK_FAILED = "ask_failed"

        /** 0.8 `ask`: la continuazione salvata non corrisponde al programma approvato. Fallisce chiuso. */
        const val ASK_RESUME_INVALID = "ask_resume_invalid"

        /** 0.8 `ask`: dopo un riavvio la regola non è più quella armata della domanda: non si prosegue. */
        const val ASK_RESUME_STALE = "ask_resume_stale"
        private const val MAX_CANONICAL_NUMBER_CHARS = 64
        private val CANONICAL_NUMBER = Regex("^-?[0-9]+(?:\\.[0-9]+)?$")
    }
}
