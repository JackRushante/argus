package dev.argus.ui.presentation

import dev.argus.engine.model.SecretPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 0.8 · Sistema › Segreti: the pure parts of the screen (last use, form checks). No Android resources. */
object SecretsText {
    sealed interface LastUse {
        data object Never : LastUse
        data class Today(val time: String) : LastUse
        data class Yesterday(val time: String) : LastUse
        data class On(val date: String) : LastUse
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun lastUse(lastUsedAtMillis: Long?, nowMillis: Long, zone: ZoneId, locale: Locale): LastUse {
        val millis = lastUsedAtMillis ?: return LastUse.Never
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val today: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (at.toLocalDate()) {
            today -> LastUse.Today(at.format(TIME))
            today.minusDays(1) -> LastUse.Yesterday(at.format(TIME))
            else -> {
                val pattern = if (at.year == today.year) "d MMM" else "d MMM yyyy"
                LastUse.On(at.format(DateTimeFormatter.ofPattern(pattern, locale)))
            }
        }
    }

    enum class NameProblem { INVALID, TAKEN }

    /** null = the name can be saved as a NEW secret. */
    fun nameProblem(name: String, existing: Collection<String>): NameProblem? = when {
        !SecretPolicy.isNameValid(name) -> NameProblem.INVALID
        name in existing -> NameProblem.TAKEN
        else -> null
    }

    fun isValueValid(value: String): Boolean = SecretPolicy.isValueValid(value)
}
