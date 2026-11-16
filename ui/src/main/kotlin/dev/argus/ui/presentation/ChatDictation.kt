package dev.argus.ui.presentation

/**
 * Dettatura nella chat (0.7.2): il riconoscitore vocale di sistema restituisce i testi candidati e il
 * primo finisce nel campo, accodato a quello che l'utente aveva già scritto. Non invia mai: l'utente
 * rilegge e preme Invia.
 */
object ChatDictation {
    /** Il candidato migliore (il primo) senza spazi ai bordi; null se il riconoscitore non ha capito nulla. */
    fun bestResult(results: List<String?>?): String? =
        results?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Il campo dopo la dettatura: il testo riconosciuto si accoda, separato da uno spazio, a quello presente. */
    fun merge(current: String, recognised: String?): String {
        val spoken = recognised?.trim().orEmpty()
        if (spoken.isEmpty()) return current
        val typed = current.trimEnd()
        return if (typed.isEmpty()) spoken else "$typed $spoken"
    }
}
