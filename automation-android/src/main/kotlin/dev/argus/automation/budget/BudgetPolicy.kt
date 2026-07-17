package dev.argus.automation.budget

import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderId
import dev.argus.data.UsageWindows
import dev.argus.data.dao.ProviderTokensAggregate
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
import java.time.ZoneId

enum class LimitWindow { HOUR, DAY, MONTH_COST, MONTH_TOKENS }

sealed interface BudgetScope {
    data object Global : BudgetScope
    data class Provider(val id: ProviderId) : BudgetScope
}

data class TrippedLimit(val window: LimitWindow, val scope: BudgetScope) {
    /** Formato stabile per AuditEvent.detail: "hour:global", "month_cost:openai", ... */
    fun auditDetail(): String {
        val windowToken = when (window) {
            LimitWindow.HOUR -> "hour"
            LimitWindow.DAY -> "day"
            LimitWindow.MONTH_COST -> "month_cost"
            LimitWindow.MONTH_TOKENS -> "month_tokens"
        }
        val scopeToken = when (scope) {
            BudgetScope.Global -> "global"
            is BudgetScope.Provider -> scope.id.wireName
        }
        return "$windowToken:$scopeToken"
    }
}

sealed interface BudgetVerdict {
    data object Ok : BudgetVerdict
    data class SoftExceeded(val tripped: TrippedLimit) : BudgetVerdict
    data class HardExceeded(val tripped: TrippedLimit) : BudgetVerdict
}

/**
 * Aggregato cross-regola: valuta i tetti scelti dall'utente (globali + per-provider) sulle finestre
 * ora/giorno e sul tetto mensile. I limiti chiamate ora/giorno sono universali; il tetto mensile
 * dipende dal provider: COSTO per i costTracked (OpenAI/Anthropic/Gemini), TOKEN (in+out) per i
 * TOKEN-ONLY (Hermes/OpenRouter/Custom). Separato dal cooldown per-regola dell'Engine.
 */
open class BudgetPolicy(
    private val usage: UsageDao,
    private val settings: BudgetSettingsStore,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    open suspend fun check(providerId: ProviderId, nowMillis: Long): BudgetVerdict {
        val current = settings.observe().value
        val zoneId = zone()

        val hourWindow = UsageWindows.lastHour(nowMillis)
        val dayWindow = UsageWindows.currentDay(nowMillis, zoneId)
        val monthWindow = UsageWindows.currentMonth(nowMillis, zoneId)

        val hourAgg = usage.aggregateBetween(hourWindow.startMillis, hourWindow.endMillisExclusive)
        val dayAgg = usage.aggregateBetween(dayWindow.startMillis, dayWindow.endMillisExclusive)
        val monthAgg = usage.aggregateBetween(monthWindow.startMillis, monthWindow.endMillisExclusive)

        val wire = providerId.wireName
        val provider = BudgetScope.Provider(providerId)
        val providerLimits = current.perProvider[wire] ?: BudgetLimits()
        val globalLimits = current.global
        val softPct = current.softThresholdPct

        val costTracked = ProviderCatalog.spec(providerId).costTracked
        // Il tetto mensile cambia natura col provider: costo per i priced, token per i token-only.
        // Il tetto token globale copre SOLO i provider token-only: i priced sono governati dal costo.
        val (providerMonth, globalMonth) = if (costTracked) {
            Check(LimitWindow.MONTH_COST, provider, providerLimits.maxCostPerMonthMicros, costFor(monthAgg, wire)) to
                Check(LimitWindow.MONTH_COST, BudgetScope.Global, globalLimits.maxCostPerMonthMicros, costAll(monthAgg))
        } else {
            val monthTokens = usage.tokensBetween(monthWindow.startMillis, monthWindow.endMillisExclusive)
            Check(LimitWindow.MONTH_TOKENS, provider, providerLimits.maxTokensPerMonth, tokensFor(monthTokens, wire)) to
                Check(LimitWindow.MONTH_TOKENS, BudgetScope.Global, globalLimits.maxTokensPerMonth, tokensTokenOnly(monthTokens))
        }

        // Ordine deterministico: prima provider (HOUR->DAY->MONTH_*), poi global (stesso ordine).
        val checks = listOf(
            Check(LimitWindow.HOUR, provider, providerLimits.maxCallsPerHour?.toLong(), callsFor(hourAgg, wire)),
            Check(LimitWindow.DAY, provider, providerLimits.maxCallsPerDay?.toLong(), callsFor(dayAgg, wire)),
            providerMonth,
            Check(LimitWindow.HOUR, BudgetScope.Global, globalLimits.maxCallsPerHour?.toLong(), callsAll(hourAgg)),
            Check(LimitWindow.DAY, BudgetScope.Global, globalLimits.maxCallsPerDay?.toLong(), callsAll(dayAgg)),
            globalMonth,
        )

        checks.firstOrNull { it.limit != null && it.used >= it.limit }?.let {
            return BudgetVerdict.HardExceeded(TrippedLimit(it.window, it.scope))
        }
        checks.firstOrNull {
            it.limit != null && it.used >= softFloor(it.limit, softPct) && it.used < it.limit
        }?.let {
            return BudgetVerdict.SoftExceeded(TrippedLimit(it.window, it.scope))
        }
        return BudgetVerdict.Ok
    }

    private data class Check(
        val window: LimitWindow,
        val scope: BudgetScope,
        val limit: Long?,
        val used: Long,
    )

    /** Gli eventi BLOCKED_BUDGET non contano: altrimenti i tentativi bloccati bloccano per sempre. */
    private fun calls(aggregate: ProviderUsageAggregate): Long =
        (aggregate.calls - aggregate.blockedCalls).coerceAtLeast(0L)

    private fun callsFor(aggregates: List<ProviderUsageAggregate>, wire: String): Long =
        aggregates.firstOrNull { it.providerId == wire }?.let(::calls) ?: 0L

    private fun callsAll(aggregates: List<ProviderUsageAggregate>): Long =
        aggregates.sumOf(::calls)

    private fun costFor(aggregates: List<ProviderUsageAggregate>, wire: String): Long =
        aggregates.firstOrNull { it.providerId == wire }?.costMicros ?: 0L

    private fun costAll(aggregates: List<ProviderUsageAggregate>): Long =
        aggregates.sumOf { it.costMicros ?: 0L }

    /** Token consumati = in + out; NULL (n/d) pesa 0, come per il costo. */
    private fun tokens(row: ProviderTokensAggregate): Long =
        (row.tokensIn ?: 0L) + (row.tokensOut ?: 0L)

    private fun tokensFor(rows: List<ProviderTokensAggregate>, wire: String): Long =
        rows.firstOrNull { it.providerId == wire }?.let(::tokens) ?: 0L

    /** Somma dei soli provider token-only: i token dei priced non consumano il tetto token globale. */
    private fun tokensTokenOnly(rows: List<ProviderTokensAggregate>): Long =
        rows.filterNot { costTrackedWire(it.providerId) }.sumOf(::tokens)

    private fun costTrackedWire(wire: String): Boolean =
        ProviderId.fromWireName(wire)?.let { ProviderCatalog.spec(it).costTracked } ?: false

    private fun softFloor(limit: Long, softPct: Int): Long = (limit * softPct + 99) / 100
}
