package dev.argus.automation.budget

import dev.argus.brain.ProviderId
import dev.argus.engine.brain.TurnUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CostEstimatorTest {
    @Test
    fun `modello noto calcola micro usd esatti`() {
        val cost = CostEstimator.estimate(
            ProviderId.OPENAI,
            "gpt-5.5",
            TurnUsage(inputTokens = 1_000_000, outputTokens = 100_000),
        )
        // input 1M * $1.25/M = 1_250_000 ; output 100k * $10/M = 1_000_000
        assertEquals(2_250_000L, cost)
    }

    @Test
    fun `cached tokens scorporati alla tariffa cached`() {
        val cost = CostEstimator.estimate(
            ProviderId.OPENAI,
            "gpt-5.5",
            TurnUsage(
                inputTokens = 1_000_000,
                outputTokens = 100_000,
                cachedInputTokens = 400_000,
            ),
        )
        // (600_000 * 1_250_000 + 400_000 * 125_000 + 100_000 * 10_000_000) / 1M
        // = (750_000_000_000 + 50_000_000_000 + 1_000_000_000_000) / 1M = 1_800_000
        assertEquals(1_800_000L, cost)
    }

    @Test
    fun `modello sconosciuto ritorna null senza lanciare`() {
        assertNull(
            CostEstimator.estimate(
                ProviderId.OPENAI,
                "gpt-9000",
                TurnUsage(inputTokens = 1_000, outputTokens = 1_000),
            ),
        )
    }

    @Test
    fun `provider senza listino ritorna null`() {
        val usage = TurnUsage(inputTokens = 1_000, outputTokens = 1_000, model = "whatever")
        assertNull(CostEstimator.estimate(ProviderId.HERMES, "whatever", usage))
        assertNull(CostEstimator.estimate(ProviderId.CUSTOM_OPENAI_COMPAT, "whatever", usage))
    }

    @Test
    fun `usage o model null ritorna null`() {
        assertNull(CostEstimator.estimate(ProviderId.OPENAI, "gpt-5.5", null))
        assertNull(
            CostEstimator.estimate(
                ProviderId.OPENAI,
                null,
                TurnUsage(inputTokens = 1_000, outputTokens = 1_000),
            ),
        )
    }

    @Test
    fun `arrotondamento half-up`() {
        // gpt-5-mini input $0.25/M: 3 token -> 3 * 250_000 = 750_000 ; (+500_000)/1M = 1
        val cost = CostEstimator.estimate(
            ProviderId.OPENAI,
            "gpt-5-mini",
            TurnUsage(inputTokens = 3, outputTokens = 0),
        )
        assertEquals(1L, cost)
    }
}
