package dev.argus.automation.budget

import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import dev.argus.data.UsageWindows
import dev.argus.data.dao.UsageDao
import dev.argus.data.entities.UsageEventEntity
import dev.argus.data.entities.UsageEventKind
import dev.argus.data.entities.UsageEventOutcome
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
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
) : Brain {
    private val notified = ConcurrentHashMap<String, Boolean>()
    /**
     * Il check e la registrazione del consumo formano un'unica sezione critica. Senza questo gate
     * due trigger concorrenti possono leggere entrambi N-1 chiamate, oltrepassare un limite HARD e
     * contattare entrambi il provider prima che uno dei due eventi usage sia persistito.
     */
    private val callGate = Mutex()

    override suspend fun compile(
        nl: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult = callGate.withLock {
        val config = selectedConfig()
        val now = nowMillis()
        when (val verdict = verdictFor(config, now)) {
            is BudgetVerdict.HardExceeded -> {
                recordBlocked(config, UsageEventKind.COMPILE, now)
                notifyOnce(verdict.tripped, now)
                return CompileResult(
                    reply = BudgetMeta.blockedReply(),
                    draft = null,
                    metaError = BudgetMeta.BUDGET_EXCEEDED,
                )
            }
            is BudgetVerdict.UnpricedModel -> {
                recordBlocked(config, UsageEventKind.COMPILE, now)
                notifyUnpricedOnce(verdict.providerId)
                return CompileResult(
                    reply = BudgetMeta.unpricedModelReply(),
                    draft = null,
                    metaError = BudgetMeta.UNPRICED_MODEL,
                )
            }
            is BudgetVerdict.SoftExceeded -> notifyOnce(verdict.tripped, now)
            BudgetVerdict.Ok -> {}
        }
        val result = delegate.compile(nl, manifest, state)
        // S15: il transport (bridge Hermes incluso) ora riporta l'usage reale anche in compile.
        recordUsage(config, UsageEventKind.COMPILE, now, result.metaError, result.usage)
        return result
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = callGate.withLock {
        val config = selectedConfig()
        val now = nowMillis()
        when (val verdict = verdictFor(config, now)) {
            is BudgetVerdict.HardExceeded -> return hardBlock(config, UsageEventKind.ACT, now, context, verdict.tripped)
            is BudgetVerdict.UnpricedModel ->
                return unpricedModelBlock(config, UsageEventKind.ACT, now, context, verdict.providerId)
            is BudgetVerdict.SoftExceeded -> notifyOnce(verdict.tripped, now)
            BudgetVerdict.Ok -> {}
        }
        val result = delegate.act(context, goal, contextSources, allowedTools)
        recordUsage(config, UsageEventKind.ACT, now, result.metaError, result.usage)
        return result
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
    ): ActResult = callGate.withLock {
        val config = selectedConfig()
        val now = nowMillis()
        when (val verdict = verdictFor(config, now)) {
            is BudgetVerdict.HardExceeded -> return hardBlock(config, UsageEventKind.ACT, now, context, verdict.tripped)
            is BudgetVerdict.UnpricedModel ->
                return unpricedModelBlock(config, UsageEventKind.ACT, now, context, verdict.providerId)
            is BudgetVerdict.SoftExceeded -> notifyOnce(verdict.tripped, now)
            BudgetVerdict.Ok -> {}
        }
        val result = delegate.actResolved(context, goal, contextSources, allowedTools, runtimeData)
        recordUsage(config, UsageEventKind.ACT, now, result.metaError, result.usage)
        return result
    }

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        callGate.withLock {
            val config = selectedConfig()
            val now = nowMillis()
            when (val verdict = verdictFor(config, now)) {
                is BudgetVerdict.HardExceeded ->
                    return hardBlock(config, UsageEventKind.ACT_V2, now, context, verdict.tripped)
                is BudgetVerdict.UnpricedModel ->
                    return unpricedModelBlock(
                        config,
                        UsageEventKind.ACT_V2,
                        now,
                        context,
                        verdict.providerId,
                    )
                is BudgetVerdict.SoftExceeded -> notifyOnce(verdict.tripped, now)
                BudgetVerdict.Ok -> {}
            }
            val result = delegate.actV2(context, action)
            recordUsage(config, UsageEventKind.ACT_V2, now, result.metaError, result.usage)
            return result
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
        config: ProviderConfig,
        kind: UsageEventKind,
        now: Long,
        context: FireContext,
        tripped: TrippedLimit,
    ): ActResult {
        recordBlocked(config, kind, now)
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
        config: ProviderConfig,
        kind: UsageEventKind,
        now: Long,
        context: FireContext,
        providerId: ProviderId,
    ): ActResult {
        recordBlocked(config, kind, now)
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
        usage: dev.argus.engine.brain.TurnUsage?,
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
                outcome = if (metaError == null) UsageEventOutcome.OK else UsageEventOutcome.ERROR,
                tokensIn = usage?.inputTokens,
                tokensOut = usage?.outputTokens,
                costMicros = cost,
                pricingVersion = if (cost != null) ProviderCatalog.PRICING_VERSION else null,
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
