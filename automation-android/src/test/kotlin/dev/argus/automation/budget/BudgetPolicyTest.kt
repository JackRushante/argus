package dev.argus.automation.budget

import dev.argus.brain.ProviderId
import dev.argus.data.UsageWindows
import dev.argus.data.dao.ProviderTokensAggregate
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
import dev.argus.data.entities.UsageEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BudgetPolicyTest {
    private val now = 1_700_000_000_000L
    private val rome = ZoneId.of("Europe/Rome")

    @Test
    fun `senza limiti ritorna Ok`() = runTest {
        val policy = policy(
            settings = BudgetSettings(),
            aggregates = listOf(agg("openai", calls = 50)),
        )
        assertEquals(BudgetVerdict.Ok, policy.check(ProviderId.OPENAI, now))
    }

    @Test
    fun `limite orario globale raggiunto ritorna HardExceeded hour global`() = runTest {
        val policy = policy(
            settings = BudgetSettings(global = BudgetLimits(maxCallsPerHour = 10)),
            aggregates = listOf(agg("openai", calls = 6), agg("anthropic", calls = 4)),
        )
        val verdict = policy.check(ProviderId.OPENAI, now)
        assertIs<BudgetVerdict.HardExceeded>(verdict)
        assertEquals("hour:global", verdict.tripped.auditDetail())
    }

    @Test
    fun `soglia soft 80 ritorna SoftExceeded prima del limite`() = runTest {
        val policy = policy(
            settings = BudgetSettings(global = BudgetLimits(maxCallsPerHour = 10), softThresholdPct = 80),
            aggregates = listOf(agg("openai", calls = 8)),
        )
        val verdict = policy.check(ProviderId.OPENAI, now)
        assertIs<BudgetVerdict.SoftExceeded>(verdict)
        assertEquals("hour:global", verdict.tripped.auditDetail())
    }

    @Test
    fun `limite per provider scatta solo per quel provider`() = runTest {
        val settings = BudgetSettings(
            perProvider = mapOf("openai" to BudgetLimits(maxCallsPerHour = 5)),
        )
        val policy = policy(settings, listOf(agg("openai", calls = 5)))
        assertIs<BudgetVerdict.HardExceeded>(policy.check(ProviderId.OPENAI, now))
        assertEquals(BudgetVerdict.Ok, policy.check(ProviderId.ANTHROPIC, now))
    }

    @Test
    fun `provider valutato prima del global`() = runTest {
        val settings = BudgetSettings(
            global = BudgetLimits(maxCallsPerHour = 5),
            perProvider = mapOf("openai" to BudgetLimits(maxCallsPerHour = 5)),
        )
        val policy = policy(settings, listOf(agg("openai", calls = 5)))
        val verdict = policy.check(ProviderId.OPENAI, now)
        assertIs<BudgetVerdict.HardExceeded>(verdict)
        assertIs<BudgetScope.Provider>(verdict.tripped.scope)
        assertEquals("hour:openai", verdict.tripped.auditDetail())
    }

    @Test
    fun `eventi BLOCKED_BUDGET esclusi dal conteggio`() = runTest {
        val policy = policy(
            settings = BudgetSettings(global = BudgetLimits(maxCallsPerHour = 8), softThresholdPct = 80),
            aggregates = listOf(agg("openai", calls = 10, blockedCalls = 4)),
        )
        // effettivo = 10 - 4 = 6 ; soft floor = 7 ; niente hard né soft
        assertEquals(BudgetVerdict.Ok, policy.check(ProviderId.OPENAI, now))
    }

    @Test
    fun `tetto costo mensile usa la finestra currentMonth`() = runTest {
        val dao = RecordingUsageDao(listOf(agg("openai", calls = 1, costMicros = 1_000)))
        val policy = BudgetPolicy(
            dao,
            FakeBudgetSettingsStore(BudgetSettings(global = BudgetLimits(maxCostPerMonthMicros = 10_000_000))),
            zone = { rome },
        )
        policy.check(ProviderId.OPENAI, now)

        val expected = UsageWindows.currentMonth(now, rome)
        assertTrue(
            dao.windows.any { it == expected.startMillis to expected.endMillisExclusive },
            "il dao deve ricevere esattamente currentMonth(now, Europe/Rome)",
        )
    }

    @Test
    fun `costMicros null non consuma il tetto costo`() = runTest {
        val policy = policy(
            settings = BudgetSettings(
                perProvider = mapOf("hermes" to BudgetLimits(maxCostPerMonthMicros = 1_000)),
            ),
            aggregates = listOf(agg("hermes", calls = 20, costMicros = null)),
        )
        assertEquals(BudgetVerdict.Ok, policy.check(ProviderId.HERMES, now))
    }

    // --- tetto TOKEN mensile (provider token-only) ---------------------------

    @Test
    fun `tetto token mensile per provider token-only raggiunto ritorna HardExceeded`() = runTest {
        val policy = policy(
            settings = BudgetSettings(
                perProvider = mapOf("hermes" to BudgetLimits(maxTokensPerMonth = 1_000)),
            ),
            aggregates = listOf(agg("hermes", calls = 3, tokensIn = 600, tokensOut = 400)),
        )
        val verdict = policy.check(ProviderId.HERMES, now)
        assertIs<BudgetVerdict.HardExceeded>(verdict)
        assertEquals("month_tokens:hermes", verdict.tripped.auditDetail())
    }

    @Test
    fun `tetto token mensile soft a 80 prima del limite`() = runTest {
        val policy = policy(
            settings = BudgetSettings(
                perProvider = mapOf("hermes" to BudgetLimits(maxTokensPerMonth = 1_000)),
                softThresholdPct = 80,
            ),
            aggregates = listOf(agg("hermes", calls = 3, tokensIn = 700, tokensOut = 100)),
        )
        val verdict = policy.check(ProviderId.HERMES, now)
        assertIs<BudgetVerdict.SoftExceeded>(verdict)
        assertEquals("month_tokens:hermes", verdict.tripped.auditDetail())
    }

    @Test
    fun `provider costTracked ignora il tetto token e usa il tetto costo`() = runTest {
        // openai ha superato di molto il tetto token, ma è priced: conta solo il costo.
        val policy = policy(
            settings = BudgetSettings(
                perProvider = mapOf(
                    "openai" to BudgetLimits(maxTokensPerMonth = 100, maxCostPerMonthMicros = 10_000_000),
                ),
            ),
            aggregates = listOf(agg("openai", calls = 3, tokensIn = 5_000, tokensOut = 5_000, costMicros = 1_000)),
        )
        assertEquals(BudgetVerdict.Ok, policy.check(ProviderId.OPENAI, now))
    }

    @Test
    fun `provider token-only ignora il tetto costo e usa il tetto token`() = runTest {
        // Anche con un costMicros legacy in tabella, per hermes conta SOLO il tetto token.
        val policy = policy(
            settings = BudgetSettings(
                perProvider = mapOf(
                    "hermes" to BudgetLimits(maxCostPerMonthMicros = 1, maxTokensPerMonth = 1_000),
                ),
            ),
            aggregates = listOf(agg("hermes", calls = 3, tokensIn = 100, tokensOut = 100, costMicros = 999)),
        )
        assertEquals(BudgetVerdict.Ok, policy.check(ProviderId.HERMES, now))
    }

    @Test
    fun `tetto token globale somma solo i provider token-only`() = runTest {
        val settings = BudgetSettings(global = BudgetLimits(maxTokensPerMonth = 1_000))
        // I 10_000 token di openai (priced) NON consumano il tetto token globale.
        val under = policy(
            settings,
            listOf(
                agg("hermes", calls = 2, tokensIn = 300, tokensOut = 100),
                agg("openai", calls = 2, tokensIn = 9_000, tokensOut = 1_000),
            ),
        )
        assertEquals(BudgetVerdict.Ok, under.check(ProviderId.HERMES, now))

        val over = policy(
            settings,
            listOf(
                agg("hermes", calls = 2, tokensIn = 600, tokensOut = 200),
                agg("openrouter", calls = 1, tokensIn = 200, tokensOut = 0),
            ),
        )
        val verdict = over.check(ProviderId.HERMES, now)
        assertIs<BudgetVerdict.HardExceeded>(verdict)
        assertEquals("month_tokens:global", verdict.tripped.auditDetail())
    }

    @Test
    fun `tetto token globale non blocca i provider costTracked`() = runTest {
        val policy = policy(
            settings = BudgetSettings(global = BudgetLimits(maxTokensPerMonth = 100)),
            aggregates = listOf(agg("hermes", calls = 2, tokensIn = 500, tokensOut = 500)),
        )
        // Chiamando openai (priced) il tetto token globale non si applica.
        assertEquals(BudgetVerdict.Ok, policy.check(ProviderId.OPENAI, now))
    }

    @Test
    fun `tetto token per provider isolato dagli altri token-only`() = runTest {
        val policy = policy(
            settings = BudgetSettings(
                perProvider = mapOf("hermes" to BudgetLimits(maxTokensPerMonth = 100)),
            ),
            aggregates = listOf(
                agg("hermes", calls = 2, tokensIn = 90, tokensOut = 20),
                agg("openrouter", calls = 1, tokensIn = 10, tokensOut = 0),
            ),
        )
        assertIs<BudgetVerdict.HardExceeded>(policy.check(ProviderId.HERMES, now))
        assertEquals(BudgetVerdict.Ok, policy.check(ProviderId.OPENROUTER, now))
    }

    // --- helpers -------------------------------------------------------------

    private fun policy(settings: BudgetSettings, aggregates: List<ProviderUsageAggregate>): BudgetPolicy =
        BudgetPolicy(RecordingUsageDao(aggregates), FakeBudgetSettingsStore(settings), zone = { rome })

    private fun agg(
        providerId: String,
        calls: Long,
        blockedCalls: Long = 0,
        costMicros: Long? = null,
        tokensIn: Long? = null,
        tokensOut: Long? = null,
    ) = ProviderUsageAggregate(
        providerId = providerId,
        calls = calls,
        okCalls = calls - blockedCalls,
        errorCalls = 0,
        blockedCalls = blockedCalls,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        costMicros = costMicros,
    )

    private class RecordingUsageDao(
        private val aggregates: List<ProviderUsageAggregate>,
    ) : UsageDao {
        val windows = mutableListOf<Pair<Long, Long>>()
        override suspend fun insert(event: UsageEventEntity): Long = 0
        override suspend fun aggregateBetween(
            startMillis: Long,
            endMillisExclusive: Long,
        ): List<ProviderUsageAggregate> {
            windows += startMillis to endMillisExclusive
            return aggregates
        }
        override suspend fun tokensBetween(
            startMillis: Long,
            endMillisExclusive: Long,
        ): List<ProviderTokensAggregate> =
            aggregates.map { ProviderTokensAggregate(it.providerId, it.tokensIn, it.tokensOut) }
        override suspend fun purgeBefore(cutoffMillis: Long): Int = 0
    }

    private class FakeBudgetSettingsStore(settings: BudgetSettings) : BudgetSettingsStore {
        private val state = MutableStateFlow(settings)
        override fun observe(): StateFlow<BudgetSettings> = state
        override suspend fun setGlobalLimits(limits: BudgetLimits): Boolean = true
        override suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean = true
        override suspend fun setSoftThresholdPct(pct: Int): Boolean = true
    }
}
