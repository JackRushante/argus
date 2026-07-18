package dev.argus.engine.brain

/**
 * Conteggi token di un singolo turno LLM. Pure-data, nessun segreto, nessun import HTTP.
 * Traslocata in engine-core (S12) perché [ActResult] la trasporti come `usage`
 * (engine-core non può dipendere da brain-android); in brain-android resta un typealias di compat.
 * I transport diretti la popolano dopo ogni turno; i decorator e il journal la leggono da [ActResult].
 */
data class TurnUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long? = null,
    val model: String? = null,
) {
    init {
        require(inputTokens in 0..MAX_TOKENS_PER_TURN && outputTokens in 0..MAX_TOKENS_PER_TURN) {
            "conteggi token fuori intervallo"
        }
        require(cachedInputTokens == null || cachedInputTokens in 0..inputTokens) {
            "conteggio token cached incoerente"
        }
        require(
            model == null ||
                model.isNotBlank() && model.length <= MAX_MODEL_CHARS && model.none(Char::isISOControl),
        ) {
            "identificatore modello non valido"
        }
    }

    companion object {
        /** Bound difensivo molto sopra i context window correnti, ma sicuro per DB e costo intero. */
        const val MAX_TOKENS_PER_TURN: Long = 100_000_000L
        const val MAX_MODEL_CHARS: Int = 256
    }
}
