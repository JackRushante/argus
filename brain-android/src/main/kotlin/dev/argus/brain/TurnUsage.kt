package dev.argus.brain

/**
 * Conteggi token di un singolo turno LLM. Pure-data, nessun segreto, nessun import HTTP.
 * NOTA S12: traslocherà in dev.argus.engine.brain quando ActResult guadagnerà `usage`
 * (engine-core non può dipendere da brain-android); qui resterà al più un typealias.
 * In Wave 1 nessuno la popola: esiste perché i transport di Wave 2 la usino senza toccare il contratto.
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
