package dev.argus.automation.budget

import dev.argus.brain.CompileLimits
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import dev.argus.data.UsageWindows
import dev.argus.data.dao.ProviderTokensAggregate
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
import dev.argus.data.entities.UsageEventEntity
import dev.argus.data.entities.UsageEventKind
import dev.argus.data.entities.UsageEventOutcome
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileMetaError
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.brain.TurnUsage
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.ValidationIssue
import dev.argus.automation.vm.CURRENT_CONTACT_PLACEHOLDER
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.ValidationMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

object BudgetMeta {
    const val BUDGET_EXCEEDED = "budget_exceeded" // rispetta ^[a-z][a-z0-9_]{0,63}$ (invariante ActResult)
    const val UNPRICED_MODEL = "budget_unpriced_model"
    const val UNKNOWN_MODEL = "unknown" // convenzione T9 di UsageEventEntity

    /**
     * Reply mostrata in chat quando il budget HARD blocca la compile. EN di default, IT quando il
     * locale di sistema è italiano (bilingue via [RenderLanguage], come il RuleRenderMapper): questo
     * è testo utente prodotto FUORI da Compose, quindi niente risorse Android qui.
     */
    fun blockedReply(l: RenderLanguage = RenderLanguage.system()): String = l.pick(
        "LLM budget exhausted: I blocked the call. Raise or reset the limits in System → Budget.",
        "Budget LLM esaurito: ho bloccato la chiamata. Alza o azzera i limiti in Sistema → Budget.",
    )

    fun unpricedModelReply(l: RenderLanguage = RenderLanguage.system()): String = l.pick(
        "The selected model has no price in the Argus catalog, so the monetary budget cannot be " +
            "enforced. Choose a priced model or remove the dollar limit.",
        "Il modello selezionato non ha un prezzo nel catalogo Argus, quindi il budget monetario " +
            "non può essere applicato. Scegli un modello con prezzo noto o rimuovi il limite in dollari.",
    )
}

/**
 * Un [Brain] che sa chiamare un provider preciso. [MeteredBrain] ammette una chiamata per la
 * [ProviderConfig] letta in quel momento e la esegue proprio su quella: se l'utente cambia provider fra
 * ammissione e chiamata, la richiesta non va a un provider che il budget non ha controllato.
 */
interface ProviderBoundBrain : Brain {
    fun boundTo(config: ProviderConfig): Brain
}

/** metaError dell'evento d'uso di una chiamata partita e poi cancellata (timeout della lane, regola spenta). */
private const val CANCELLED_META = "cancelled"

/** Gli avvisi più vecchi della finestra più lunga (un mese) non servono più alla deduplica. */
private const val ALERT_KEY_MAX_AGE_MILLIS = 32L * 24 * 60 * 60 * 1_000

/** Stima grezza dei token quando il provider non li riporta: circa 4 caratteri per token. */
private const val CHARS_PER_TOKEN = 4L

/** Parte fissa del prompt di compile (regole, schema delle bozze, manifest): circa 33 000 caratteri. */
private const val COMPILE_PROMPT_TOKENS_ESTIMATE = 9_000L

/** Parte fissa del prompt di una chiamata generativa (regole sui dati non fidati). */
private const val ACT_PROMPT_TOKENS_ESTIMATE = 500L

private const val NOTE_MAX_CHARS = 400
private const val NOTE_PREFIX = "(Argus: your previous reply could not be used: "
private const val NOTE_SUFFIX = ". Reply again with one short sentence, then the final line @@META@@ " +
    "{\"draft\":<object-or-null>,\"error_code\":<string-or-null>}, using only the trigger and action " +
    "shapes listed in the rules; for \"now\" use {\"type\":\"immediate\"}.)"
private val WHITESPACE = Regex("\\s+")

/** Pacchetto che precede un nome di classe (`dev.argus.engine.model.Trigger.Time`): spazio sprecato nella nota. */
private val PACKAGE_PREFIX = Regex("\\b(?:[a-z][a-z0-9]*\\.)+(?=[A-Z])")

/**
 * Nota di correzione del secondo tentativo di compile: codice e, se c'è, il dettaglio del parser (già
 * privo di frammenti di input; i nomi di classe perdono il pacchetto). Tutta la nota sta in
 * [NOTE_MAX_CHARS]: se serve si accorcia il dettaglio, mai le istruzioni.
 */
internal fun correctionNote(code: String, detail: String?): String {
    val reason = buildString {
        append(code)
        val clean = detail?.replace(PACKAGE_PREFIX, "")?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (clean.isNotEmpty()) append(": ").append(clean)
    }
    val room = (NOTE_MAX_CHARS - NOTE_PREFIX.length - NOTE_SUFFIX.length).coerceAtLeast(code.length)
    var cut = reason.take(room)
    if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
    return NOTE_PREFIX + cut + NOTE_SUFFIX
}

private const val REPAIR_NOTE_PREFIX = "(Argus: a first draft for this request failed validation. "
private const val REPAIR_NOTE_SUFFIX = " Reply again with one short sentence, then the final line @@META@@ " +
    "{\"draft\":<object-or-null>,\"error_code\":<string-or-null>}. Compile the request again avoiding exactly " +
    "these errors; or, if the request cannot be met within these rules (for example a limit " +
    "is exceeded), return draft null with the matching error_code (limit_exceeded for a limit) and explain why.)"
private const val REPAIR_MAX_ERRORS = 6

/**
 * Nota del giro di riparazione: SOLO codici e messaggi (inglesi) del validatore, uno per codice (e per
 * passo, quando il codice porta il numero del passo di una macro). Nessun dato dell'utente oltre a quello
 * già presente nella richiesta: i messaggi vengono dalla tabella fissa di [ValidationMessages], mai dal
 * testo diagnostico del validatore, che può citare nomi di variabili o pacchetti.
 */
internal fun repairNote(errors: List<ValidationIssue>): String {
    val reasons = errors.distinctBy { it.code to it.detail }.take(REPAIR_MAX_ERRORS).joinToString(" ") { issue ->
        "${issue.code}: ${ValidationMessages.text(issue, RenderLanguage.EN).trimEnd('.', ' ')}."
    }
    return REPAIR_NOTE_PREFIX + reasons + REPAIR_NOTE_SUFFIX
}

/** Quale ritentativo di compile è partito: serve al banco per contarli separatamente. */
enum class CompileRetryKind {
    /** Errore di formato del modello (o `draft_missing`): nota di correzione. */
    FORMAT,

    /** Bozza ben formata ma con errori ERROR del validatore: giro di riparazione. */
    REPAIR,
}

/**
 * Errori che il validatore dà a una bozza appena compilata: la STESSA fonte (stesso [dev.argus.engine.safety.DraftValidator],
 * stessa whitelist) che decide `canArm` in approvazione.
 */
fun interface DraftErrorSource {
    suspend fun errors(draft: AutomationDraft): List<ValidationIssue>
}

/** Canale notifiche budget, separato da AutomationNotifier (che richiede FireContext). */
fun interface BudgetAlerts {
    suspend fun notify(title: String, text: String)
}

/**
 * Decorator [Brain] che fa da unico choke-point per il budget LLM (aggregato cross-regola).
 * Pre-call: [BudgetPolicy.check] → HARD blocca il transport; SOFT avvisa una sola volta per finestra.
 * Post-call: scrive un evento usage con token e costo stimato. Il cooldown per-regola dell'Engine
 * resta invariato: qui si misura solo l'aggregato.
 *
 * Il lock NON è tenuto durante la rete (B16): una compile da 50 s non blocca un act concorrente.
 * Check e PRENOTAZIONE dello slot avvengono sotto lock; la chiamata di rete è fuori; la
 * registrazione dell'usage e la liberazione dello slot di nuovo sotto lock. Le chiamate in volo
 * contano nei tetti chiamate/ora e chiamate/giorno (con [settings] disponibile): due trigger
 * concorrenti con un solo slot rimasto non possono passare entrambi.
 */
class MeteredBrain(
    private val delegate: Brain,
    private val policy: BudgetPolicy,
    private val usage: UsageDao,
    private val selectedConfig: () -> ProviderConfig,
    private val audit: AuditSink,
    private val alerts: BudgetAlerts,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /**
     * Tetti utente: serve a contare le chiamate in volo e ad azzerare gli avvisi già dati quando
     * l'utente cambia i limiti. Senza, vale solo il verdetto di [policy].
     */
    private val settings: BudgetSettingsStore? = null,
    /** Se presente, abilita il giro di riparazione sugli errori del validatore (0.5.0). */
    private val draftErrors: DraftErrorSource? = null,
    /** Notifica ogni ritentativo che parte (formato o riparazione): per il banco e per i test. */
    private val onRetry: ((CompileRetryKind) -> Unit)? = null,
) : Brain {
    /**
     * Avvisi già dati: chiave (limite + finestra) → istante dell'avviso. Si azzera quando cambiano i
     * limiti (un tetto alzato e di nuovo raggiunto va segnalato) e perde le finestre ormai chiuse.
     */
    private val notified = ConcurrentHashMap<String, Long>()

    /** Limiti in vigore all'ultimo avviso: un cambio azzera [notified]. */
    @Volatile
    private var alertSettings: BudgetSettings? = settings?.observe()?.value

    /**
     * Sezione critica corta: check + prenotazione, e registrazione + rilascio. Senza questo gate due
     * trigger concorrenti possono leggere entrambi N-1 chiamate, oltrepassare un limite HARD e
     * contattare entrambi il provider prima che uno dei due eventi usage sia persistito.
     */
    private val callGate = Mutex()

    /** Chiamate in volo per provider (wireName). Si legge e scrive SOLO sotto [callGate]. */
    private val inFlight = HashMap<String, Int>()

    /** La stessa [BudgetPolicy] su una vista dell'usage che somma le chiamate in volo. */
    private val inFlightPolicy: BudgetPolicy? = settings?.let {
        BudgetPolicy(InFlightAwareUsageDao(usage) { inFlight.toMap() }, it, zone)
    }

    override suspend fun compile(
        nl: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        val config = selectedConfig()
        val first = meteredCompile(config, nl) { it.compile(nl, manifest, state) }
        // Retry-once degli errori di FORMATO del modello (draft_invalid, meta_parse_error,
        // meta_json_missing) e di una risposta senza la riga @@META@@ (draft_missing: il protocollo la
        // chiede sempre, anche per un chiarimento), provider-agnostico ma VISIBILE al choke-point: il primo
        // evento appena persistito partecipa al nuovo check e un HARD impedisce il secondo contatto. Se
        // l'utente cambia provider/modello durante il primo turno, non ritentare su una destinazione
        // diversa da quella appena contabilizzata: un retry manuale ripartirà col nuovo config.
        // Una risposta ben formata passa al giro di riparazione; in tutto UN solo ritentativo per messaggio.
        val code = first.metaError ?: return repairIfInvalid(nl, manifest, state, config, first)
        if (!isRetryable(code)) return first
        if (selectedConfig() != config) return first
        // Con temperatura bassa rifare la stessa richiesta ripete lo stesso errore: il secondo tentativo
        // porta in coda al testo utente una nota di correzione, SOLO per questa chiamata (non entra in
        // chat né nel DB: il chiamante conserva il proprio `nl`).
        val retryText = withCorrectionNote(nl, code, first.detail)
        return meteredCompile(config, retryText, { onRetry?.invoke(CompileRetryKind.FORMAT) }) {
            it.compile(retryText, manifest, state)
        }
    }

    /**
     * Giro di riparazione (0.5.0): la bozza è ben formata ma il validatore ha issue ERROR, quelle che
     * disabiliterebbero Arma. Si rifà la richiesta con una nota coi codici e i messaggi, dal choke-point
     * del budget come ogni altra chiamata. Niente riparazione per chiarimenti, rifiuti, `unsupported` e
     * `limit_exceeded` (non hanno bozza), né se l'utente ha cambiato provider, né se la nota farebbe
     * superare il tetto del messaggio. Se la riparazione non produce una risposta utilizzabile (errore di
     * rete, formato rotto, budget) resta la bozza originale con i suoi errori visibili.
     */
    private suspend fun repairIfInvalid(
        nl: String,
        manifest: CapabilityManifest,
        state: DeviceState,
        config: ProviderConfig,
        first: CompileResult,
    ): CompileResult {
        val source = draftErrors ?: return first
        val draft = first.draft ?: return first
        // In modifica il contatto è ancora il segnaposto: la chat lo ripristina DOPO, il validatore qui no.
        if ((draft.trigger as? Trigger.Notification)?.conversationId == CURRENT_CONTACT_PLACEHOLDER) return first
        // 0.8: lo stesso per il contatto del trigger di fine di una regola-intervallo.
        if ((draft.exit?.trigger as? Trigger.Notification)?.conversationId ==
            dev.argus.automation.vm.CURRENT_EXIT_CONTACT_PLACEHOLDER
        ) return first
        val errors = try {
            source.errors(draft).filter { it.severity == Severity.ERROR }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return first
        }
        if (errors.isEmpty()) return first
        if (selectedConfig() != config) return first
        val text = nl + "\n\n" + repairNote(errors)
        if (text.length > CompileLimits.MAX_MESSAGE_CHARS) return first
        val repaired = try {
            meteredCompile(config, text, { onRetry?.invoke(CompileRetryKind.REPAIR) }) {
                it.compile(text, manifest, state)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return first
        }
        // Una bozza (anche ancora con errori) o una risposta motivata del modello (limite superato,
        // chiarimento, non supportato) sostituisce l'originale; un errore dell'app no.
        val usable = repaired.metaError?.let(CompileMetaError::isModelAnswer) ?: (repaired.draft != null)
        return if (usable) repaired else first
    }

    /**
     * Errori di formato e `draft_missing`. Sul telefono un modello piccolo ha risposto a «spegni subito il
     * non disturbare» con una frase e la sola azione in JSON, senza `@@META@@`: anche quella è una bozza
     * persa, e la nota di correzione la recupera. Un chiarimento ben formato (clarification_required) non
     * passa di qui.
     */
    private fun isRetryable(code: String): Boolean =
        code in CompileMetaError.FORMAT_ERRORS || code == CompileMetaError.DRAFT_MISSING

    private fun withCorrectionNote(nl: String, code: String, detail: String?): String {
        val text = nl + "\n\n" + correctionNote(code, detail)
        // Il transport rifiuta un prompt oltre il tetto: in quel caso il ritentativo resta identico.
        return if (text.length <= CompileLimits.MAX_MESSAGE_CHARS) text else nl
    }

    private suspend fun meteredCompile(
        config: ProviderConfig,
        /** Testo utente della chiamata: serve solo alla stima dei token quando il provider non li riporta. */
        userText: String,
        /** Chiamata solo se il budget ammette la chiamata (un blocco HARD non conta come ritentativo partito). */
        onAdmitted: (() -> Unit)? = null,
        call: suspend (Brain) -> CompileResult,
    ): CompileResult {
        val now = nowMillis()
        when (val admission = admit(config, UsageEventKind.COMPILE, now)) {
            is Admission.Blocked -> return when (val verdict = admission.verdict) {
                is BudgetVerdict.UnpricedModel -> {
                    notifyUnpricedOnce(verdict.providerId)
                    CompileResult(
                        reply = BudgetMeta.unpricedModelReply(),
                        draft = null,
                        metaError = BudgetMeta.UNPRICED_MODEL,
                    )
                }
                is BudgetVerdict.HardExceeded -> {
                    notifyOnce(verdict.tripped, now)
                    CompileResult(
                        reply = BudgetMeta.blockedReply(),
                        draft = null,
                        metaError = BudgetMeta.BUDGET_EXCEEDED,
                    )
                }
                else -> error("verdict non bloccante")
            }
            Admission.Granted -> onAdmitted?.invoke()
        }
        // Ogni tentativo costa ed entra nel budget, anche quando il JSON del modello è malformato.
        return metered(
            config, UsageEventKind.COMPILE, now, call, { it.metaError }, { it.usage },
            requestChars = userText.length.toLong(),
            responseCharsOf = { (it.raw ?: it.reply).length.toLong() },
        )
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = meteredAct(UsageEventKind.ACT, context, goal.length.toLong()) {
        it.act(context, goal, contextSources, allowedTools)
    }

    /**
     * Canale RISOLTO P4-D2: stessa contabilità di [act] (stesso choke-point, kind ACT). Il framing
     * anti-injection del dato runtime avviene nel transport a valle; qui conta solo che la capture
     * generativa passi SEMPRE dal budget — un HARD la sopprime esattamente come un act.
     */
    override suspend fun actResolved(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
        runtimeData: List<RuntimeDataBinding>,
    ): ActResult = meteredAct(
        UsageEventKind.ACT,
        context,
        goal.length.toLong() + runtimeData.sumOf { it.value.text.length.toLong() },
    ) {
        it.actResolved(context, goal, contextSources, allowedTools, runtimeData)
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        meteredAct(
            UsageEventKind.ACT_V2,
            context,
            action.goal.length.toLong() + context.state.queryValues.values.sumOf { it.length.toLong() },
        ) { it.actV2(context, action) }

    /** 0.8: la risposta vincolata passa dallo stesso budget di [actResolved]; ogni tentativo conta. */
    override suspend fun actConstrained(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
        runtimeData: List<RuntimeDataBinding>,
        output: OutputSpec,
    ): ActResult = meteredAct(
        UsageEventKind.ACT,
        context,
        goal.length.toLong() + runtimeData.sumOf { it.value.text.length.toLong() },
    ) {
        it.actConstrained(context, goal, contextSources, allowedTools, runtimeData, output)
    }

    private suspend fun meteredAct(
        kind: UsageEventKind,
        context: FireContext,
        /** Caratteri della richiesta noti qui (obiettivo e dati): l'evento si aggiunge sotto. */
        requestChars: Long,
        call: suspend (Brain) -> ActResult,
    ): ActResult {
        val config = selectedConfig()
        val now = nowMillis()
        when (val admission = admit(config, kind, now)) {
            is Admission.Blocked -> return when (val verdict = admission.verdict) {
                is BudgetVerdict.HardExceeded -> hardBlock(now, context, verdict.tripped)
                is BudgetVerdict.UnpricedModel -> unpricedModelBlock(now, context, verdict.providerId)
                else -> error("verdict non bloccante")
            }
            Admission.Granted -> Unit
        }
        return metered(
            config, kind, now, call, { it.metaError }, { it.usage },
            requestChars = requestChars + eventChars(context.event),
            responseCharsOf = { it.text?.length?.toLong() ?: 0L },
        )
    }

    /** Testo dell'evento che il transport mette nel prompt (messaggio, mittente, SMS). */
    private fun eventChars(event: TriggerEvent): Long = when (event) {
        is TriggerEvent.NotificationPosted ->
            listOfNotNull(event.text, event.title, event.sender).sumOf { it.length.toLong() }
        is TriggerEvent.PhoneStateChanged -> event.smsText?.length?.toLong() ?: 0L
        else -> 0L
    }

    /**
     * Stima dei token quando il provider non riporta l'uso (S10): senza, un tetto in token non
     * scatterebbe mai. Circa 4 caratteri per token sul testo noto qui, più la parte fissa del prompt
     * di sistema che il transport aggiunge (regole e schema per la compile). Nessuna risposta del
     * modello (errore di rete, rifiuto) = nessuna stima: la chiamata resta con token n/d.
     */
    private fun estimatedUsage(kind: UsageEventKind, requestChars: Long, responseChars: Long): TurnUsage? {
        if (responseChars <= 0L) return null
        val promptOverhead = when (kind) {
            UsageEventKind.COMPILE -> COMPILE_PROMPT_TOKENS_ESTIMATE
            UsageEventKind.ACT, UsageEventKind.ACT_V2 -> ACT_PROMPT_TOKENS_ESTIMATE
        }
        return TurnUsage(
            inputTokens = promptOverhead + charsToTokens(requestChars),
            outputTokens = charsToTokens(responseChars),
        )
    }

    private fun charsToTokens(chars: Long): Long = (chars.coerceAtLeast(0L) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** Il brain da chiamare per [config]: lo stesso provider che il budget ha appena ammesso. */
    private fun brainFor(config: ProviderConfig): Brain =
        (delegate as? ProviderBoundBrain)?.boundTo(config) ?: delegate

    private sealed interface Admission {
        data object Granted : Admission
        data class Blocked(val verdict: BudgetVerdict) : Admission
    }

    /**
     * Sotto lock: verdetto del budget (comprese le chiamate già in volo) e, se la chiamata è
     * ammessa, prenotazione dello slot. Un blocco viene registrato come evento BLOCKED_BUDGET.
     */
    private suspend fun admit(config: ProviderConfig, kind: UsageEventKind, now: Long): Admission =
        callGate.withLock {
            val verdict = admissionVerdict(config, now)
            when (verdict) {
                is BudgetVerdict.HardExceeded, is BudgetVerdict.UnpricedModel -> {
                    recordBlocked(config, kind, now)
                    Admission.Blocked(verdict)
                }
                is BudgetVerdict.SoftExceeded -> {
                    notifyOnce(verdict.tripped, now)
                    reserve(config)
                    Admission.Granted
                }
                BudgetVerdict.Ok -> {
                    reserve(config)
                    Admission.Granted
                }
            }
        }

    /** Verdetto di [policy], inasprito se contando le chiamate in volo un tetto chiamate scatta. */
    private suspend fun admissionVerdict(config: ProviderConfig, now: Long): BudgetVerdict {
        val base = verdictFor(config, now)
        if (base is BudgetVerdict.HardExceeded || base is BudgetVerdict.UnpricedModel) return base
        val aware = inFlightPolicy
        if (aware != null && inFlight.isNotEmpty()) {
            val withInFlight = try {
                aware.check(config, now)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                BudgetVerdict.Ok
            }
            if (withInFlight is BudgetVerdict.HardExceeded) return withInFlight
        }
        return base
    }

    private fun reserve(config: ProviderConfig) {
        inFlight.merge(config.providerId.wireName, 1, Int::plus)
    }

    private fun release(config: ProviderConfig) {
        val wire = config.providerId.wireName
        val left = (inFlight[wire] ?: 0) - 1
        if (left > 0) inFlight[wire] = left else inFlight.remove(wire)
    }

    /**
     * Esegue la chiamata di rete FUORI dal lock; poi, sotto lock, registra l'usage e libera la
     * prenotazione. Il rilascio è garantito anche su eccezione o cancellazione. Una chiamata
     * cancellata dopo la partenza (timeout della lane, regola spenta) registra un evento ERROR senza
     * token: la richiesta è già dal provider e i tetti chiamate devono vederla (S10).
     */
    private suspend fun <R> metered(
        config: ProviderConfig,
        kind: UsageEventKind,
        now: Long,
        call: suspend (Brain) -> R,
        metaErrorOf: (R) -> String?,
        usageOf: (R) -> TurnUsage?,
        requestChars: Long,
        responseCharsOf: (R) -> Long,
    ): R {
        var released = false
        try {
            // Il provider chiamato è quello appena ammesso, non quello selezionato fra un istante.
            val result = call(brainFor(config))
            callGate.withLock {
                val reported = usageOf(result)
                val estimate = if (reported == null) {
                    estimatedUsage(kind, requestChars, responseCharsOf(result))
                } else {
                    null
                }
                recordUsage(
                    config, kind, now, metaErrorOf(result),
                    usage = reported ?: estimate,
                    estimated = estimate != null,
                )
                release(config)
                released = true
            }
            return result
        } catch (error: CancellationException) {
            if (!released) {
                withContext(NonCancellable) {
                    callGate.withLock {
                        recordUsage(config, kind, now, CANCELLED_META, usage = null, estimated = false)
                        release(config)
                        released = true
                    }
                }
            }
            throw error
        } finally {
            if (!released) {
                withContext(NonCancellable) { callGate.withLock { release(config) } }
            }
        }
    }

    /** Un errore DB del budget non deve spegnere il cervello: fail-open (ma la cancellazione risale). */
    private suspend fun verdictFor(config: ProviderConfig, now: Long): BudgetVerdict = try {
        policy.check(config, now)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        BudgetVerdict.Ok
    }

    private suspend fun hardBlock(
        now: Long,
        context: FireContext,
        tripped: TrippedLimit,
    ): ActResult {
        tryAudit(
            AuditEvent(
                automationId = context.automationId,
                kind = AuditKind.SUPPRESSED_BUDGET,
                atMillis = now,
                detail = tripped.auditDetail(),
                eventId = context.eventId,
                executionId = context.executionId,
            ),
        )
        notifyOnce(tripped, now)
        return ActResult(text = null, metaError = BudgetMeta.BUDGET_EXCEEDED)
    }

    private suspend fun unpricedModelBlock(
        now: Long,
        context: FireContext,
        providerId: ProviderId,
    ): ActResult {
        tryAudit(
            AuditEvent(
                automationId = context.automationId,
                kind = AuditKind.SUPPRESSED_BUDGET,
                atMillis = now,
                detail = "unpriced_model:${providerId.wireName}",
                eventId = context.eventId,
                executionId = context.executionId,
            ),
        )
        notifyUnpricedOnce(providerId)
        return ActResult(text = null, metaError = BudgetMeta.UNPRICED_MODEL)
    }

    private suspend fun recordUsage(
        config: ProviderConfig,
        kind: UsageEventKind,
        now: Long,
        metaError: String?,
        usage: TurnUsage?,
        /** [usage] è una stima dalle lunghezze (il provider non ha riportato l'uso): niente costo. */
        estimated: Boolean,
    ) {
        val model = usage?.model ?: config.model ?: BudgetMeta.UNKNOWN_MODEL
        // Alcuni provider restituiscono un alias/versione datata del modello configurato. Se
        // quell'alias non è nel listino ma il modello scelto dall'utente sì, usa quest'ultimo per
        // il prezzo senza falsificare il model registrato nell'evento.
        val prices = ProviderCatalog.spec(config.providerId).prices
        val pricingModel = sequenceOf(usage?.model, config.model)
            .filterNotNull()
            .firstOrNull(prices::containsKey)
        // Il prezzo è quello valido all'istante dell'evento (listini con data di validità). Un uso
        // stimato non diventa mai una cifra in dollari.
        val cost = if (estimated) null else CostEstimator.estimate(config.providerId, pricingModel, usage, now)
        tryInsert(
            UsageEventEntity(
                timestampMs = now,
                providerId = config.providerId.wireName,
                model = model,
                kind = kind,
                // Chiarimento, capability non supportata e limite superato sono risposte BUONE del
                // modello (nessuna regola da creare), non errori: non sporcano le statistiche.
                outcome = if (metaError == null ||
                    (kind == UsageEventKind.COMPILE && CompileMetaError.isModelAnswer(metaError))
                ) {
                    UsageEventOutcome.OK
                } else {
                    UsageEventOutcome.ERROR
                },
                tokensIn = usage?.inputTokens,
                tokensOut = usage?.outputTokens,
                costMicros = cost,
                // Una stima dei token è marcata qui, così il budget la mostra come tale (≈).
                pricingVersion = when {
                    cost != null -> ProviderCatalog.PRICING_VERSION
                    estimated -> UsageEventEntity.TOKEN_ESTIMATE
                    else -> null
                },
                reasoningTokens = usage?.reasoningTokens,
                finishReason = usage?.finishReason,
            ),
        )
    }

    private suspend fun recordBlocked(config: ProviderConfig, kind: UsageEventKind, now: Long) {
        tryInsert(
            UsageEventEntity(
                timestampMs = now,
                providerId = config.providerId.wireName,
                model = config.model ?: BudgetMeta.UNKNOWN_MODEL,
                kind = kind,
                outcome = UsageEventOutcome.BLOCKED_BUDGET,
                tokensIn = null,
                tokensOut = null,
                costMicros = null,
                pricingVersion = null,
            ),
        )
    }

    /** L'insert è best-effort: mai trasformare una risposta buona in crash. */
    private suspend fun tryInsert(event: UsageEventEntity) {
        try {
            usage.insert(event)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // ignorato: la contabilità budget non deve rompere il turno.
        }
    }

    private suspend fun tryAudit(event: AuditEvent) {
        try {
            audit.record(event)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // ignorato: l'audit budget è best-effort.
        }
    }

    /**
     * Prima di ogni avviso: limiti cambiati ⇒ si riparte da zero; poi via le voci più vecchie della
     * finestra più lunga (il mese), così la mappa non cresce per tutta la vita del processo.
     */
    private fun refreshAlertKeys(now: Long) {
        val current = settings?.observe()?.value
        if (current != alertSettings) {
            alertSettings = current
            notified.clear()
        }
        notified.entries.removeIf { (_, at) -> now - at > ALERT_KEY_MAX_AGE_MILLIS || at > now }
    }

    /** Numero di avvisi ricordati: solo per i test. */
    internal fun rememberedAlertCount(): Int = notified.size

    /** One-shot per finestra: chiave = auditDetail + bucket (ora/giorno/mese locale). */
    private suspend fun notifyOnce(tripped: TrippedLimit, now: Long) {
        refreshAlertKeys(now)
        val key = bucketKey(tripped, now)
        if (notified.putIfAbsent(key, now) == null) {
            try {
                // Titolo/testo dell'alert bilingui via RenderLanguage (EN default, IT se locale it).
                val l = RenderLanguage.system()
                alerts.notify(
                    l.pick("LLM budget", "Budget LLM"),
                    l.pick(
                        "The LLM budget is almost or fully exhausted. Check the limits in " +
                            "System → Budget.",
                        "Budget LLM quasi o del tutto esaurito. Controlla i limiti in " +
                            "Sistema → Budget.",
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }

    private suspend fun notifyUnpricedOnce(providerId: ProviderId) {
        val now = nowMillis()
        refreshAlertKeys(now)
        if (notified.putIfAbsent("unpriced_model:${providerId.wireName}", now) != null) return
        try {
            val l = RenderLanguage.system()
            alerts.notify(
                l.pick("LLM budget", "Budget LLM"),
                l.pick(
                    "The selected model has no catalog price. The call was blocked because a " +
                        "monetary limit is active.",
                    "Il modello selezionato non ha un prezzo nel catalogo. La chiamata è stata " +
                        "bloccata perché è attivo un limite monetario.",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun bucketKey(tripped: TrippedLimit, now: Long): String {
        val bucket = when (tripped.window) {
            LimitWindow.HOUR -> (now / UsageWindows.HOUR_MILLIS).toString()
            LimitWindow.DAY -> Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toString()
            LimitWindow.MONTH_COST, LimitWindow.MONTH_TOKENS ->
                YearMonth.from(Instant.ofEpochMilli(now).atZone(zone())).toString()
        }
        return "${tripped.auditDetail()}:$bucket"
    }
}

/**
 * Vista dell'usage che somma le chiamate in volo (prenotazioni di [MeteredBrain]) alle chiamate già
 * registrate. Una prenotazione è per definizione "adesso", quindi rientra in ogni finestra
 * interrogata dalla policy (ultima ora, giorno corrente). Serve a far contare lo slot prenotato nei
 * tetti chiamate/ora e chiamate/giorno senza duplicare la logica di [BudgetPolicy]. Sola lettura:
 * l'unica scrittura reale resta quella di [MeteredBrain].
 */
internal class InFlightAwareUsageDao(
    private val delegate: UsageDao,
    private val reservations: () -> Map<String, Int>,
) : UsageDao {
    override suspend fun insert(event: UsageEventEntity): Long = delegate.insert(event)

    override suspend fun aggregateBetween(
        startMillis: Long,
        endMillisExclusive: Long,
    ): List<ProviderUsageAggregate> {
        val stored = delegate.aggregateBetween(startMillis, endMillisExclusive)
        val pending = reservations()
        if (pending.isEmpty()) return stored
        val byProvider = stored.associateBy { it.providerId }.toMutableMap()
        pending.forEach { (wire, count) ->
            val row = byProvider[wire]
            byProvider[wire] = row?.copy(calls = row.calls + count)
                ?: ProviderUsageAggregate(
                    providerId = wire,
                    calls = count.toLong(),
                    okCalls = 0,
                    errorCalls = 0,
                    blockedCalls = 0,
                    tokensIn = null,
                    tokensOut = null,
                    costMicros = null,
                )
        }
        return byProvider.values.sortedBy { it.providerId }
    }

    override suspend fun tokensBetween(
        startMillis: Long,
        endMillisExclusive: Long,
    ): List<ProviderTokensAggregate> = delegate.tokensBetween(startMillis, endMillisExclusive)

    override suspend fun purgeBefore(cutoffMillis: Long): Int = delegate.purgeBefore(cutoffMillis)
}
