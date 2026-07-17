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
    private fun agg(
        id: String,
        calls: Long,
        blocked: Long = 0,
        cost: Long? = null,
        tokensIn: Long? = null,
        tokensOut: Long? = null,
    ) =
        ProviderUsageAggregate(
            providerId = id,
            calls = calls,
            okCalls = (calls - blocked).coerceAtLeast(0),
            errorCalls = 0,
            blockedCalls = blocked,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
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

    @Test fun `token in e out separati per provider e finestra`() {
        val snapshot = BudgetUsageSnapshot(
            hour = listOf(agg("hermes", calls = 1, tokensIn = 100, tokensOut = 10)),
            day = listOf(agg("hermes", calls = 3, tokensIn = 400, tokensOut = 40)),
            month = listOf(agg("hermes", calls = 9, tokensIn = 900, tokensOut = 90)),
        )
        val hermes = budgetUi(snapshot, BudgetSettings()).perProvider.single()
        assertEquals(100L, hermes.tokensInHour)
        assertEquals(10L, hermes.tokensOutHour)
        assertEquals(400L, hermes.tokensInDay)
        assertEquals(40L, hermes.tokensOutDay)
        assertEquals(900L, hermes.tokensInMonth)
        assertEquals(90L, hermes.tokensOutMonth)
    }

    @Test fun `costTracked dal catalogo e dollari solo per i priced`() {
        val snapshot = BudgetUsageSnapshot(
            month = listOf(
                agg("openai", calls = 2, cost = 1_000_000),
                // costMicros legacy per un token-only: NON deve arrivare in UI.
                agg("openrouter", calls = 1, cost = 123_456),
                agg("hermes", calls = 1, cost = null),
            ),
        )
        val ui = budgetUi(snapshot, BudgetSettings())
        val byId = ui.perProvider.associateBy { it.providerId }
        assertTrue(byId.getValue("openai").costTracked)
        assertEquals(1_000_000L, byId.getValue("openai").costMonthMicros)
        assertFalse(byId.getValue("openrouter").costTracked)
        assertNull(byId.getValue("openrouter").costMonthMicros)
        assertFalse(byId.getValue("hermes").costTracked)
        assertNull(byId.getValue("hermes").costMonthMicros)
        // Il totale mese scarta anche il costo legacy del token-only.
        assertEquals(1_000_000L, ui.costMonthMicros)
    }

    @Test fun `token mese globali sommano solo i token-only`() {
        val snapshot = BudgetUsageSnapshot(
            month = listOf(
                agg("hermes", calls = 2, tokensIn = 100, tokensOut = 10),
                agg("openrouter", calls = 1, tokensIn = 50, tokensOut = 5),
                agg("openai", calls = 4, tokensIn = 9_000, tokensOut = 900),
            ),
        )
        val ui = budgetUi(snapshot, BudgetSettings())
        assertEquals(150L, ui.tokensInMonth)
        assertEquals(15L, ui.tokensOutMonth)
    }

    @Test fun `token mese null quando nessun token-only ha dati`() {
        val snapshot = BudgetUsageSnapshot(
            month = listOf(
                agg("hermes", calls = 2, tokensIn = null, tokensOut = null),
                agg("openai", calls = 4, tokensIn = 9_000, tokensOut = 900),
            ),
        )
        val ui = budgetUi(snapshot, BudgetSettings())
        assertNull(ui.tokensInMonth)
        assertNull(ui.tokensOutMonth)
    }

    @Test fun `limite token mensile esposto dal global`() {
        val settings = BudgetSettings(global = BudgetLimits(maxTokensPerMonth = 5_000))
        assertEquals(5_000L, budgetUi(BudgetUsageSnapshot(), settings).tokenLimitMonth)
        assertNull(budgetUi(BudgetUsageSnapshot(), BudgetSettings()).tokenLimitMonth)
    }

    @Test fun `soft warning dal tetto token globale`() {
        val settings = BudgetSettings(global = BudgetLimits(maxTokensPerMonth = 1_000), softThresholdPct = 80)
        val over = BudgetUsageSnapshot(month = listOf(agg("hermes", calls = 2, tokensIn = 700, tokensOut = 100)))
        assertTrue(budgetUi(over, settings).softWarningActive)

        val under = BudgetUsageSnapshot(month = listOf(agg("hermes", calls = 2, tokensIn = 300, tokensOut = 100)))
        assertFalse(budgetUi(under, settings).softWarningActive)
    }

    @Test fun `soft warning dal tetto token per provider token-only`() {
        val settings = BudgetSettings(
            perProvider = mapOf("hermes" to BudgetLimits(maxTokensPerMonth = 1_000)),
            softThresholdPct = 80,
        )
        val snapshot = BudgetUsageSnapshot(month = listOf(agg("hermes", calls = 2, tokensIn = 800, tokensOut = 0)))
        assertTrue(budgetUi(snapshot, settings).softWarningActive)
    }
}
