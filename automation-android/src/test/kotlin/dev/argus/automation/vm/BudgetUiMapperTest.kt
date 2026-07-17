package dev.argus.automation.vm

import dev.argus.automation.budget.BudgetLimits
import dev.argus.automation.budget.BudgetSettings
import dev.argus.data.dao.ProviderUsageAggregate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mapper puro [budgetUi]: testabile su host senza Robolectric (JUnit5). */
class BudgetUiMapperTest {
    private fun agg(id: String, calls: Long, blocked: Long = 0, cost: Long? = null) =
        ProviderUsageAggregate(
            providerId = id,
            calls = calls,
            okCalls = (calls - blocked).coerceAtLeast(0),
            errorCalls = 0,
            blockedCalls = blocked,
            tokensIn = null,
            tokensOut = null,
            costMicros = cost,
        )

    @Test fun `chiamate escludono i blocked`() {
        val snapshot = BudgetUsageSnapshot(
            hour = listOf(agg("openai", calls = 5, blocked = 2), agg("hermes", calls = 3)),
        )
        val ui = budgetUi(snapshot, BudgetSettings())
        assertEquals(6L, ui.usedHour)
    }

    @Test fun `costo mese null quando tutti i provider sono nd`() {
        val snapshot = BudgetUsageSnapshot(
            month = listOf(agg("hermes", calls = 3, cost = null), agg("custom_openai_compat", calls = 1, cost = null)),
        )
        val ui = budgetUi(snapshot, BudgetSettings())
        assertNull(ui.costMonthMicros)
    }

    @Test fun `costo mese somma i costi presenti`() {
        val snapshot = BudgetUsageSnapshot(
            month = listOf(agg("openai", calls = 3, cost = 1_000_000), agg("hermes", calls = 1, cost = null)),
        )
        val ui = budgetUi(snapshot, BudgetSettings())
        assertEquals(1_000_000L, ui.costMonthMicros)
    }

    @Test fun `perProvider ordinato per wireName con label dal catalog`() {
        val snapshot = BudgetUsageSnapshot(
            hour = listOf(agg("openai", calls = 2), agg("hermes", calls = 1)),
        )
        val ui = budgetUi(snapshot, BudgetSettings())
        assertEquals(listOf("hermes", "openai"), ui.perProvider.map { it.providerId })
        assertEquals("Hermes (self-hosted)", ui.perProvider.first { it.providerId == "hermes" }.providerLabel)
    }

    @Test fun `soft warning dal limite globale`() {
        val snapshot = BudgetUsageSnapshot(hour = listOf(agg("openai", calls = 17)))
        val settings = BudgetSettings(global = BudgetLimits(maxCallsPerHour = 20), softThresholdPct = 80)
        assertTrue(budgetUi(snapshot, settings).softWarningActive)

        val low = BudgetUsageSnapshot(hour = listOf(agg("openai", calls = 10)))
        assertFalse(budgetUi(low, settings).softWarningActive)
    }
}
