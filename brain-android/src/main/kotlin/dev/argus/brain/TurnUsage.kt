package dev.argus.brain

/** Traslocata in engine-core (S12): typealias di compatibilità per i transport e i test. */
typealias TurnUsage = dev.argus.engine.brain.TurnUsage

/**
 * Costruzione tollerante dell'uso di un turno dai campi del provider. Prima un solo campo facoltativo
 * anomalo (token in cache oltre l'input, un motivo di fine strano, un nome di modello con caratteri di
 * controllo) faceva cadere TUTTO l'uso e il turno, già pagato, non entrava nel budget. Qui si scarta solo
 * quel campo; senza conteggi di input e output validi non c'è uso.
 */
internal object TurnUsages {
    private val FINISH_REASON = Regex("^[A-Za-z0-9_.:-]{1,64}$")

    fun build(
        inputTokens: Long,
        outputTokens: Long,
        model: String?,
        fallbackModel: String? = null,
        cachedInputTokens: Long? = null,
        reasoningTokens: Long? = null,
        finishReason: String? = null,
        webSearches: Int? = null,
    ): TurnUsage? {
        val range = 0..TurnUsage.MAX_TOKENS_PER_TURN
        if (inputTokens !in range || outputTokens !in range) return null
        return runCatching {
            TurnUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedInputTokens = cachedInputTokens?.takeIf { it in 0..inputTokens },
                reasoningTokens = reasoningTokens?.takeIf { it in 0..outputTokens },
                model = model?.takeIf(::validModel) ?: fallbackModel?.takeIf(::validModel),
                finishReason = finishReason?.takeIf(FINISH_REASON::matches),
                webSearches = webSearches?.takeIf { it in 0..TurnUsage.MAX_WEB_SEARCHES },
            )
        }.getOrNull()
    }

    private fun validModel(value: String): Boolean =
        value.isNotBlank() && value.length <= TurnUsage.MAX_MODEL_CHARS && value.none(Char::isISOControl)
}
