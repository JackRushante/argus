package dev.argus.engine.model

import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Regole e calcolo della variabile `clock` ([VarBinding.ClockTime], 0.5.0). Stesse funzioni per il
 * validatore (che prova fuso e formato) e per l'interprete (che li applica allo scatto): ciò che il
 * validatore accetta, l'interprete lo sa formattare.
 */
object ClockVariable {
    /** Formato quando `pattern` è null. */
    const val DEFAULT_PATTERN = "HH:mm"
    const val MAX_PATTERN_CHARS = 40
    const val MAX_TZ_CHARS = 64

    /** Istante di prova, fisso: la formattazione di prova non dipende dall'orologio. */
    private val PROBE: ZonedDateTime = ZonedDateTime.of(2026, 12, 31, 23, 59, 58, 0, ZoneOffset.UTC)

    /** Il fuso, o null se l'id non esiste (o è vuoto/troppo lungo). */
    fun parseZone(tz: String): ZoneId? {
        if (tz.isBlank() || tz.length > MAX_TZ_CHARS) return null
        return try {
            ZoneId.of(tz)
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * Il formattatore, o null se il formato è vuoto, più lungo di [MAX_PATTERN_CHARS], rifiutato da
     * `DateTimeFormatter.ofPattern` o impossibile da applicare a un istante con fuso (formattazione di prova).
     */
    fun parseFormatter(pattern: String, locale: Locale): DateTimeFormatter? {
        if (pattern.isBlank() || pattern.length > MAX_PATTERN_CHARS) return null
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern, locale)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            formatter.format(PROBE)
            formatter
        } catch (_: DateTimeException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** `tz` null = fuso del telefono: lo dà [clock]. null se fuso o formato non sono utilizzabili. */
    fun format(binding: VarBinding.ClockTime, clock: Clock, locale: Locale): String? {
        val tz = binding.tz
        val zone = if (tz == null) clock.zone else parseZone(tz) ?: return null
        val formatter = parseFormatter(binding.pattern ?: DEFAULT_PATTERN, locale) ?: return null
        return try {
            formatter.format(ZonedDateTime.ofInstant(clock.instant(), zone))
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * Lingua dei testi: quella dell'app (italiano se il telefono è in italiano, altrimenti inglese),
     * la stessa scelta dei testi dell'interfaccia.
     */
    fun appLocale(): Locale = if (Locale.getDefault().language == "it") Locale.ITALIAN else Locale.ENGLISH
}

/**
 * Orologio di produzione: istante corrente e fuso del telefono LETTO a ogni uso. `Clock.systemDefaultZone()`
 * fissa il fuso alla creazione, e un telefono che viaggia cambia fuso mentre il processo resta vivo.
 */
object LiveSystemClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)

    override fun instant(): Instant = Instant.now()
}
