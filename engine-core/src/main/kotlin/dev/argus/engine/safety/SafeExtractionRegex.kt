package dev.argus.engine.safety

import com.google.re2j.Pattern

/**
 * Regex di estrazione eseguite su SMS/notifiche non fidati. RE2/J esclude lookaround e
 * backreference, ma garantisce tempo lineare anche per pattern come `(a+)+$`.
 */
object SafeExtractionRegex {
    const val MAX_PATTERN_CHARS = 512
    const val DEFAULT_OTP_PATTERN = "(?:^|[^+0-9])([0-9]{4,8})(?:[^0-9]|$)"
    const val LEGACY_OTP_PATTERN = "(?<!\\+)\\b(\\d{4,8})\\b"

    sealed interface Result {
        data class Match(val value: String) : Result
        data object NoMatch : Result
        data object InvalidPattern : Result
    }

    fun isValid(pattern: String): Boolean = compile(pattern) != null

    fun extract(pattern: String, input: String): Result {
        val compiled = compile(pattern) ?: return Result.InvalidPattern
        val matcher = compiled.matcher(input)
        if (!matcher.find()) return Result.NoMatch
        val firstGroup = if (matcher.groupCount() >= 1) matcher.group(1) else null
        return Result.Match(firstGroup?.takeIf(String::isNotEmpty) ?: matcher.group())
    }

    private fun compile(pattern: String): Pattern? {
        if (pattern.length > MAX_PATTERN_CHARS) return null
        // Compatibilità per le regole OTP già approvate prima dell'hardening RE2.
        val safePattern = if (pattern == LEGACY_OTP_PATTERN) DEFAULT_OTP_PATTERN else pattern
        return runCatching { Pattern.compile(safePattern) }.getOrNull()
    }
}
