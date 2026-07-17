package dev.argus.automation.budget

import dev.argus.brain.ProviderId
import dev.argus.data.UsageWindows
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
import java.time.ZoneId

enum class LimitWindow { HOUR, DAY, MONTH_COST }

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
 * ora/giorno e sul tetto costo mensile. Separato dal cooldown per-regola dell'Engine.
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

        // Ordine deterministico: prima provider (HOUR->DAY->MONTH_COST), poi global (stesso ordine).
        val checks = listOf(
            Check(LimitWindow.HOUR, provider, providerLimits.maxCallsPerHour?.toLong(), callsFor(hourAgg, wire)),
            Check(LimitWindow.DAY, provider, providerLimits.maxCallsPerDay?.toLong(), callsFor(dayAgg, wire)),
            Check(LimitWindow.MONTH_COST, provider, providerLimits.maxCostPerMonthMicros, costFor(monthAgg, wire)),
            Check(LimitWindow.HOUR, BudgetScope.Global, globalLimits.maxCallsPerHour?.toLong(), callsAll(hourAgg)),
            Check(LimitWindow.DAY, BudgetScope.Global, globalLimits.maxCallsPerDay?.toLong(), callsAll(dayAgg)),
            Check(LimitWindow.MONTH_COST, BudgetScope.Global, globalLimits.maxCostPerMonthMicros, costAll(monthAgg)),
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

    private fun softFloor(limit: Long, softPct: Int): Long = (limit * softPct + 99) / 100
}
