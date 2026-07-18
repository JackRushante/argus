package dev.argus.ui.presentation

import java.util.Locale

/**
 * Formattazione pura del budget LLM (nessuna logica nei Composable, §UiContracts). Interi micro-USD
 * in ingresso; l'EUR è una STIMA a tasso fisso, dichiarata come tale nella UI (design §5.4).
 */
object BudgetFormat {
    /** Tasso costante di conversione: STIMA dichiarata in UI, non un cambio reale. */
    const val DEFAULT_EUR_PER_USD: Double = 0.92

    /** 2_250_000 -> "$2.25"; 0 -> "$0.00". */
    fun usdLabel(micros: Long): String =
        "$" + String.format(Locale.US, "%.2f", micros / 1_000_000.0)

    /** 2_250_000 -> "≈ $2.25 · ≈ €2.07"; null -> "n/a" (EN) / "n/d" (IT). */
    fun costLabel(
        micros: Long?,
        eurPerUsd: Double = DEFAULT_EUR_PER_USD,
        language: RenderLanguage = RenderLanguage.system(),
    ): String {
        if (micros == null) return language.pick("n/a", "n/d")
        val eur = micros / 1_000_000.0 * eurPerUsd
        val eurLabel = "€" + String.format(Locale.US, "%.2f", eur)
        return "≈ ${usdLabel(micros)} · ≈ $eurLabel"
    }

    /** (3,20)->"3 / 20"; (3,null)->"3 · unlimited" (EN) / "3 · illimitato" (IT). */
    fun callsLabel(used: Long, limit: Int?, language: RenderLanguage = RenderLanguage.system()): String =
        if (limit != null && limit > 0) "$used / $limit"
        else "$used · " + language.pick("unlimited", "illimitato")

    /** 0f se limite null/<=0; altrimenti used/limit clampato in [0,1]. */
    fun ratio(used: Long, limit: Int?): Float {
        if (limit == null || limit <= 0) return 0f
        return (used.toFloat() / limit).coerceIn(0f, 1f)
    }

    fun costRatio(usedMicros: Long?, limitMicros: Long?): Float {
        if (usedMicros == null || limitMicros == null || limitMicros <= 0L) return 0f
        return (usedMicros.toFloat() / limitMicros).coerceIn(0f, 1f)
    }

    /** "2.25"/"2,25"->2_250_000; ""/"0"->0; invalido o negativo->null. */
    fun parseUsdToMicros(text: String): Long? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return 0L
        val dollars = cleaned.toDoubleOrNull() ?: return null
        if (dollars < 0.0) return null
        return Math.round(dollars * 1_000_000.0)
    }

    // --- TOKEN (metrica primaria dei provider token-only: Hermes/OpenRouter/Custom) ---

    /** (1500,300)->"in 1500 · out 300"; un lato null->"n/a"/"n/d"; entrambi null->"n/a"/"n/d". */
    fun tokensLabel(
        tokensIn: Long?,
        tokensOut: Long?,
        language: RenderLanguage = RenderLanguage.system(),
    ): String {
        val na = language.pick("n/a", "n/d")
        if (tokensIn == null && tokensOut == null) return na
        return "in ${tokensIn?.toString() ?: na} · out ${tokensOut?.toString() ?: na}"
    }

    /** (300,1000)->"300 / 1000"; limite null/<=0->"300 · unlimited"/"illimitato"; usato null->"n/a"/"n/d". */
    fun tokensCapLabel(
        used: Long?,
        limit: Long?,
        language: RenderLanguage = RenderLanguage.system(),
    ): String {
        val usedLabel = used?.toString() ?: language.pick("n/a", "n/d")
        return if (limit != null && limit > 0) "$usedLabel / $limit"
        else "$usedLabel · " + language.pick("unlimited", "illimitato")
    }

    /** 0f se usato n/d o limite null/<=0; altrimenti used/limit clampato in [0,1]. */
    fun tokenRatio(used: Long?, limit: Long?): Float {
        if (used == null || limit == null || limit <= 0L) return 0f
        return (used.toFloat() / limit).coerceIn(0f, 1f)
    }

    /** "1500"->1500; ""/"0"->0 (illimitato); non intero o negativo->null. */
    fun parseTokens(text: String): Long? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return 0L
        val tokens = cleaned.toLongOrNull() ?: return null
        if (tokens < 0L) return null
        return tokens
    }
}
