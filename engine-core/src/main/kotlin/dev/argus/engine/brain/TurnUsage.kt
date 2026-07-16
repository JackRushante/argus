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
        require(inputTokens >= 0 && outputTokens >= 0 && (cachedInputTokens ?: 0L) >= 0L) {
            "conteggi token negativi"
        }
    }
}
