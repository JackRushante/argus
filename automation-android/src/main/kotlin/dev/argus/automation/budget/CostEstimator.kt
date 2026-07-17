package dev.argus.automation.budget

import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderId
import dev.argus.engine.brain.TurnUsage

/**
 * Stima del costo di un turno LLM in micro-USD dal listino statico [ProviderCatalog]
 * (versione [ProviderCatalog.PRICING_VERSION]). Interi puri: mai float sui soldi.
 */
object CostEstimator {
    /**
     * Costo in micro-USD, oppure `null` (mai throw) se: [usage] è null, [model] è null o sconosciuto
     * al listino, o il provider non ha prezzi (HERMES, CUSTOM_OPENAI_COMPAT).
     *
     * I token cached, se il listino espone `cachedInputMicrosPerMTok`, vengono scorporati dall'input
     * e prezzati alla tariffa cached; altrimenti restano prezzati come input pieno.
     * Arrotondamento half-up: `(somma + 500_000) / 1_000_000`.
     */
    fun estimate(providerId: ProviderId, model: String?, usage: TurnUsage?): Long? {
        if (usage == null || model == null) return null
        val price = ProviderCatalog.spec(providerId).prices[model] ?: return null

        val cached = usage.cachedInputTokens ?: 0L
        val cachedRate = price.cachedInputMicrosPerMTok
        val (fullInputTokens, cachedTokens) = if (cachedRate != null) {
            (usage.inputTokens - cached).coerceAtLeast(0L) to cached
        } else {
            usage.inputTokens to 0L
        }

        val sum = fullInputTokens * price.inputMicrosPerMTok +
            cachedTokens * (cachedRate ?: 0L) +
            usage.outputTokens * price.outputMicrosPerMTok

        return (sum + 500_000L) / 1_000_000L
    }
}
