package dev.argus.automation.budget

import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ModelPrice
import dev.argus.brain.ProviderId
import dev.argus.engine.brain.TurnUsage

/**
 * Stima del costo di un turno LLM in micro-USD dal listino statico [ProviderCatalog]
 * (versione [ProviderCatalog.PRICING_VERSION]). Interi puri: mai float sui soldi.
 */
object CostEstimator {
    /**
     * Costo in micro-USD, oppure `null` (mai throw) se: il provider non è `costTracked` (i TOKEN-ONLY
     * — HERMES, OPENROUTER, CUSTOM_OPENAI_COMPAT — non hanno mai un costo in dollari), [usage] è null,
     * [model] è null o sconosciuto al listino, o il prezzo è assente.
     *
     * I token cached, se il listino espone `cachedInputMicrosPerMTok`, vengono scorporati dall'input
     * e prezzati alla tariffa cached; altrimenti restano prezzati come input pieno.
     * Arrotondamento half-up: `(somma + 500_000) / 1_000_000`.
     *
     * [atMillis] è l'istante dell'evento d'uso (epoch ms): il prezzo applicato è quello valido a quella
     * data ([ModelPrice.effectiveAt]), quindi un evento del 2026 ricalcolato nel 2027 costa come nel 2026.
     * Chi registra un evento appena avvenuto può omettere il parametro (orologio di sistema); i test e i
     * ricalcoli passano sempre il timestamp dell'evento.
     */
    fun estimate(
        providerId: ProviderId,
        model: String?,
        usage: TurnUsage?,
        atMillis: Long = System.currentTimeMillis(),
    ): Long? {
        val spec = ProviderCatalog.spec(providerId)
        if (!spec.costTracked) return null
        if (usage == null || model == null) return null
        val price = spec.prices[model]?.effectiveAt(atMillis) ?: return null
        return priceTurn(price, usage)
    }

    /**
     * Prezzo di un turno dato il listino del modello. Le ricerche web si addebitano SOLO se il
     * listino ha un prezzo per ricerca ([ModelPrice.webSearchMicrosPerCall]); per ora nessun
     * modello ce l'ha, quindi vengono solo contate.
     */
    internal fun priceTurn(price: ModelPrice, usage: TurnUsage): Long {
        val cached = usage.cachedInputTokens ?: 0L
        val cachedRate = price.cachedInputMicrosPerMTok
        val (fullInputTokens, cachedTokens) = if (cachedRate != null) {
            (usage.inputTokens - cached).coerceAtLeast(0L) to cached
        } else {
            usage.inputTokens to 0L
        }

        val tokenSum = fullInputTokens * price.inputMicrosPerMTok +
            cachedTokens * (cachedRate ?: 0L) +
            usage.outputTokens * price.outputMicrosPerMTok
        val tokenMicros = (tokenSum + 500_000L) / 1_000_000L

        val searchMicros = (usage.webSearches ?: 0) * (price.webSearchMicrosPerCall ?: 0L)
        return tokenMicros + searchMicros
    }
}
