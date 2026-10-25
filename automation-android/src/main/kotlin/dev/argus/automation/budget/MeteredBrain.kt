package dev.argus.automation.budget

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
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.ui.presentation.RenderLanguage
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
    /** Tetti utente: serve a contare le chiamate in volo. Senza, vale solo il verdetto di [policy]. */
    settings: BudgetSettingsStore? = null,
) : Brain {
    private val notified = ConcurrentHashMap<String, Boolean>()

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
        val first = meteredCompile(config) { delegate.compile(nl, manifest, state) }
        // Retry-once degli errori di FORMATO del modello (draft_invalid, meta_parse_error,
        // meta_json_missing), provider-agnostico ma VISIBILE al choke-point: il primo evento appena
        // persistito partecipa al nuovo check e un HARD impedisce il secondo contatto. Se l'utente
        // cambia provider/modello durante il primo turno, non ritentare su una destinazione diversa
        // da quella appena contabilizzata: un retry manuale ripartirà col nuovo config.
        if (first.metaError !in CompileMetaError.FORMAT_ERRORS) return first
        if (selectedConfig() != config) return first
        return meteredCompile(config) { delegate.compile(nl, manifest, state) }
    }

    private suspend fun meteredCompile(
        config: ProviderConfig,
        call: suspend () -> CompileResult,
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
            Admission.Granted -> Unit
        }
        // Ogni tentativo costa ed entra nel budget, anche quando il JSON del modello è malformato.
        return metered(config, UsageEventKind.COMPILE, now, call, { it.metaError }, { it.usage })
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = meteredAct(UsageEventKind.ACT, context) {
        delegate.act(context, goal, contextSources, allowedTools)
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
    ): ActResult = meteredAct(UsageEventKind.ACT, context) {
        delegate.actResolved(context, goal, contextSources, allowedTools, runtimeData)
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        meteredAct(UsageEventKind.ACT_V2, context) { delegate.actV2(context, action) }

    private suspend fun meteredAct(
        kind: UsageEventKind,
        context: FireContext,
        call: suspend () -> ActResult,
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
        return metered(config, kind, now, call, { it.metaError }, { it.usage })
    }

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
     * prenotazione. Il rilascio è garantito anche su eccezione o cancellazione (la cancellazione non
     * registra alcun evento, come prima).
     */
    private suspend fun <R> metered(
        config: ProviderConfig,
        kind: UsageEventKind,
        now: Long,
        call: suspend () -> R,
        metaErrorOf: (R) -> String?,
        usageOf: (R) -> TurnUsage?,
    ): R {
        var released = false
        try {
            val result = call()
            callGate.withLock {
                recordUsage(config, kind, now, metaErrorOf(result), usageOf(result))
                release(config)
                released = true
            }
            return result
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
    ) {
        val model = usage?.model ?: config.model ?: BudgetMeta.UNKNOWN_MODEL
        // Alcuni provider restituiscono un alias/versione datata del modello configurato. Se
        // quell'alias non è nel listino ma il modello scelto dall'utente sì, usa quest'ultimo per
        // il prezzo senza falsificare il model registrato nell'evento.
        val prices = ProviderCatalog.spec(config.providerId).prices
        val pricingModel = sequenceOf(usage?.model, config.model)
            .filterNotNull()
            .firstOrNull(prices::containsKey)
        val cost = CostEstimator.estimate(config.providerId, pricingModel, usage)
        tryInsert(
            UsageEventEntity(
                timestampMs = now,
                providerId = config.providerId.wireName,
                model = model,
                kind = kind,
                // Chiarimento, capability non supportata e limite superato sono risposte BUONE del
                // modello (nessuna regola da creare), non errori: non sporcano le statistiche.
                outcome = if (metaError == null || metaError in CompileMetaError.BENIGN_NO_DRAFT) {
                    UsageEventOutcome.OK
                } else {
                    UsageEventOutcome.ERROR
                },
                tokensIn = usage?.inputTokens,
                tokensOut = usage?.outputTokens,
                costMicros = cost,
                pricingVersion = if (cost != null) ProviderCatalog.PRICING_VERSION else null,
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

    /** One-shot per finestra: chiave = auditDetail + bucket (ora/giorno/mese locale). */
    private suspend fun notifyOnce(tripped: TrippedLimit, now: Long) {
        val key = bucketKey(tripped, now)
        if (notified.putIfAbsent(key, true) == null) {
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
        if (notified.putIfAbsent("unpriced_model:${providerId.wireName}", true) != null) return
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
