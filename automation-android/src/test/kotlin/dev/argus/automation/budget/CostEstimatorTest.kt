package dev.argus.automation.budget

import dev.argus.brain.ModelPrice
import dev.argus.brain.ProviderCatalog
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
    fun `le ricerche web si contano ma senza prezzo in catalogo non si addebitano`() {
        val base = TurnUsage(inputTokens = 1_000_000, outputTokens = 100_000)
        val searched = base.copy(webSearches = 5)

        assertEquals(
            CostEstimator.estimate(ProviderId.OPENAI, "gpt-5.5", base),
            CostEstimator.estimate(ProviderId.OPENAI, "gpt-5.5", searched),
        )
        assertEquals(
            CostEstimator.estimate(ProviderId.ANTHROPIC, "claude-opus-4-8", base),
            CostEstimator.estimate(ProviderId.ANTHROPIC, "claude-opus-4-8", searched),
        )
    }

    @Test
    fun `con un prezzo per ricerca il costo delle ricerche si somma ai token`() {
        val price = ModelPrice(
            inputMicrosPerMTok = 1_250_000,
            outputMicrosPerMTok = 10_000_000,
            webSearchMicrosPerCall = 10_000,
        )
        val usage = TurnUsage(inputTokens = 1_000_000, outputTokens = 100_000, webSearches = 3)

        // token: 2_250_000 ; ricerche: 3 * 10_000
        assertEquals(2_280_000L, CostEstimator.priceTurn(price, usage))
        assertEquals(2_250_000L, CostEstimator.priceTurn(price, usage.copy(webSearches = null)))
        assertEquals(2_250_000L, CostEstimator.priceTurn(price.copy(webSearchMicrosPerCall = null), usage))
    }

    @Test
    fun `il catalogo non inventa prezzi per le ricerche`() {
        ProviderId.entries.forEach { id ->
            ProviderCatalog.spec(id).prices.forEach { (model, price) ->
                assertNull(price.webSearchMicrosPerCall, "$id/$model")
            }
        }
    }

    @Test
    fun `i token di pensiero di Gemini sommati all output fanno parte del costo`() {
        // Il transport somma thoughtsTokenCount all'output (300 pensiero + 100 risposta).
        val withThoughts = CostEstimator.estimate(
            ProviderId.GEMINI,
            "gemini-2.5-flash",
            TurnUsage(inputTokens = 1_000_000, outputTokens = 400_000, reasoningTokens = 300_000),
        )
        // input 1M * $0.30 + output 400k * $2.50 = 300_000 + 1_000_000
        assertEquals(1_300_000L, withThoughts)
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
    fun `i modelli base suggeriti hanno il costo del listino ufficiale`() {
        val usage = TurnUsage(inputTokens = 1_000_000, outputTokens = 100_000, cachedInputTokens = 400_000)
        // (600_000 * 100_000 + 400_000 * 10_000 + 100_000 * 500_000) / 1M = 60_000 + 4_000 + 50_000
        assertEquals(114_000L, CostEstimator.estimate(ProviderId.OPENAI, "gpt-6-luna", usage))
        assertEquals(114_000L, CostEstimator.estimate(ProviderId.ANTHROPIC, "claude-haiku-5-5", usage))
        // (600_000 * 750_000 + 400_000 * 75_000 + 100_000 * 3_750_000) / 1M = 450_000 + 30_000 + 375_000
        assertEquals(855_000L, CostEstimator.estimate(ProviderId.GEMINI, "gemini-3.8-flash", usage))
    }

    @Test
    fun `opus usa il listino attuale da 5 e 25 dollari`() {
        val cost = CostEstimator.estimate(
            ProviderId.ANTHROPIC,
            "claude-opus-4-8",
            TurnUsage(inputTokens = 1_000_000, outputTokens = 100_000),
        )
        // input 1M * $5/M + output 100k * $25/M = 5_000_000 + 2_500_000
        assertEquals(7_500_000L, cost)
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
    fun `provider token-only ritorna null anche per modelli prima prezzati`() {
        // OpenRouter era stimato da listino statico (2 modelli su centinaia): ora è TOKEN-ONLY,
        // il costo in dollari è sempre n/d a prescindere dal modello.
        val usage = TurnUsage(inputTokens = 1_000_000, outputTokens = 100_000)
        assertNull(CostEstimator.estimate(ProviderId.OPENROUTER, "openai/gpt-5.5", usage))
        assertNull(CostEstimator.estimate(ProviderId.OPENROUTER, "anthropic/claude-sonnet-4-5", usage))
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
