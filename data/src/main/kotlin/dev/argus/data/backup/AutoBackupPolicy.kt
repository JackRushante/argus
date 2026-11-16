package dev.argus.data.backup

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ogni quanto parte il backup automatico. */
enum class AutoBackupFrequency(val days: Long) { DAILY(1), WEEKLY(7) }

/** Perché un backup automatico non è riuscito. Vocabolario chiuso: il testo lo scrive la UI. */
enum class AutoBackupFailure {
    /** Nessuna cartella scelta. */
    FOLDER_NOT_SET,

    /** Cartella non raggiungibile: permesso perso, cartella cancellata, app di sincronizzazione assente. */
    FOLDER_UNREACHABLE,

    /** La chiave salvata non si legge più (o manca): serve impostare di nuovo la passphrase. */
    KEY_UNAVAILABLE,

    /** Lettura delle regole non riuscita. */
    READ_FAILED,

    /** Troppe regole per un solo file. */
    TOO_LARGE,

    /** Scrittura del file non riuscita. */
    WRITE_FAILED,
}

/** Esito di un'esecuzione del backup automatico. */
sealed interface AutoBackupOutcome {
    data class Written(val rules: Int, val fileName: String, val deleted: Int) : AutoBackupOutcome

    /** Nessuna regola da salvare: non si scrive un file vuoto e non si cancella nulla. */
    data object NothingToBackUp : AutoBackupOutcome
    data class Failed(val failure: AutoBackupFailure) : AutoBackupOutcome
}

/** Ultimo esito memorizzato, per la card di Sistema. */
sealed interface AutoBackupLastRun {
    val atMillis: Long

    data class Written(override val atMillis: Long, val rules: Int) : AutoBackupLastRun
    data class NothingToBackUp(override val atMillis: Long) : AutoBackupLastRun
    data class Failed(override val atMillis: Long, val failure: AutoBackupFailure) : AutoBackupLastRun
}

/**
 * Stato persistito del backup automatico, senza segreti: la chiave sta altrove (cifrata dal Keystore) e
 * qui c'è solo se esiste.
 */
data class AutoBackupState(
    val enabled: Boolean = false,
    val frequency: AutoBackupFrequency = AutoBackupFrequency.DAILY,
    /** URI dell'albero SAF scelto, con permesso persistente. */
    val folderUri: String? = null,
    /** Nome della cartella da mostrare. */
    val folderLabel: String? = null,
    val keyConfigured: Boolean = false,
    /** Ultima esecuzione riuscita (file scritto o nulla da salvare): base del calendario. */
    val lastCompletedAtMillis: Long? = null,
    /** Ultimo tentativo, riuscito o no: base dei nuovi tentativi. */
    val lastAttemptAtMillis: Long? = null,
    val lastRun: AutoBackupLastRun? = null,
    val consecutiveFailures: Int = 0,
    /** La notifica dei tre fallimenti di fila è già partita per questa serie. */
    val failureNotified: Boolean = false,
    /** Nomi dei file scritti da Argus nella cartella corrente: la pulizia tocca solo questi. */
    val writtenNames: Set<String> = emptySet(),
) {
    val configured: Boolean get() = folderUri != null && keyConfigured
    val active: Boolean get() = enabled && configured
}

/** Nome dei file e scelta di quelli da cancellare (gli ultimi [KEEP] restano). Puro. */
object AutoBackupRetention {
    const val KEEP = 7
    private val NAME = Regex("""argus-rules-\d{8}-\d{4}\.argusbackup""")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun fileName(at: LocalDateTime): String = "argus-rules-${STAMP.format(at)}.argusbackup"

    fun fileName(atMillis: Long, zone: ZoneId): String =
        fileName(LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone))

    fun isAutoBackupName(name: String): Boolean = NAME.matches(name)

    /**
     * File da cancellare: fra quelli [present] nella cartella, solo quelli che Argus ha scritto ([written])
     * e col nome del backup automatico, tranne i [keep] più recenti. Il nome porta la data a larghezza
     * fissa, quindi l'ordine dei nomi è l'ordine nel tempo.
     */
    fun toDelete(present: Collection<String>, written: Set<String>, keep: Int = KEEP): List<String> =
        present.asSequence()
            .filter { it in written && isAutoBackupName(it) }
            .distinct()
            .sortedDescending()
            .drop(keep)
            .toList()
}

/** Quando parte il prossimo backup e come cambia lo stato dopo un'esecuzione. Puro. */
object AutoBackupSchedule {
    /** Di notte: verso le 3 (sveglia inexact, Android può spostarla un po'). */
    val NIGHT_TIME: LocalTime = LocalTime.of(3, 0)

    /** Un backup fatto «un po' prima» del solito conta: la notte successiva al periodo non si salta. */
    const val EARLY_TOLERANCE_MILLIS = 12L * HOUR_MILLIS

    /** Fallimenti di fila dopo cui parte UNA notifica. */
    const val NOTIFY_AFTER_FAILURES = 3

    /** Nuovo tentativo dopo un fallimento: 1 h, 2 h, 4 h… fino a 12 h. */
    fun retryDelayMillis(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 4)
        return (HOUR_MILLIS shl exponent).coerceAtMost(MAX_RETRY_MILLIS)
    }

    /**
     * Istante del prossimo backup, o null se il backup automatico non è attivo. Può essere nel passato:
     * vuol dire «in ritardo», e parte alla prima occasione (avvio, app aperta, sveglia).
     */
    fun nextRunAt(state: AutoBackupState, nowMillis: Long, zone: ZoneId): Long? {
        if (!state.active) return null
        val attempt = state.lastAttemptAtMillis
        if (state.consecutiveFailures > 0 && attempt != null) {
            return attempt + retryDelayMillis(state.consecutiveFailures)
        }
        val completed = state.lastCompletedAtMillis ?: return nightAfter(nowMillis, zone)
        val earliest = completed + state.frequency.days * DAY_MILLIS - EARLY_TOLERANCE_MILLIS
        return nightAfter(earliest, zone)
    }

    fun isDue(state: AutoBackupState, nowMillis: Long, zone: ZoneId): Boolean =
        nextRunAt(state, nowMillis, zone)?.let { nowMillis >= it } ?: false

    /** Prima volta delle [NIGHT_TIME] strettamente dopo [afterMillis], nel fuso [zone]. */
    fun nightAfter(afterMillis: Long, zone: ZoneId): Long {
        val after = Instant.ofEpochMilli(afterMillis)
        val date = after.atZone(zone).toLocalDate()
        var candidate = date.atTime(NIGHT_TIME).atZone(zone)
        if (!candidate.toInstant().isAfter(after)) {
            candidate = date.plusDays(1).atTime(NIGHT_TIME).atZone(zone)
        }
        return candidate.toInstant().toEpochMilli()
    }

    /** Risultato di [record]: il nuovo stato e se pubblicare ora la notifica dei fallimenti. */
    data class Recorded(val state: AutoBackupState, val notifyFailure: Boolean, val clearFailureNotice: Boolean)

    fun record(state: AutoBackupState, outcome: AutoBackupOutcome, nowMillis: Long): Recorded = when (outcome) {
        is AutoBackupOutcome.Written -> Recorded(
            state.copy(
                lastCompletedAtMillis = nowMillis,
                lastAttemptAtMillis = nowMillis,
                lastRun = AutoBackupLastRun.Written(nowMillis, outcome.rules),
                consecutiveFailures = 0,
                failureNotified = false,
            ),
            notifyFailure = false,
            clearFailureNotice = state.failureNotified,
        )
        AutoBackupOutcome.NothingToBackUp -> Recorded(
            state.copy(
                lastCompletedAtMillis = nowMillis,
                lastAttemptAtMillis = nowMillis,
                lastRun = AutoBackupLastRun.NothingToBackUp(nowMillis),
                consecutiveFailures = 0,
                failureNotified = false,
            ),
            notifyFailure = false,
            clearFailureNotice = state.failureNotified,
        )
        is AutoBackupOutcome.Failed -> {
            val failures = state.consecutiveFailures + 1
            val notify = failures >= NOTIFY_AFTER_FAILURES && !state.failureNotified
            Recorded(
                state.copy(
                    lastAttemptAtMillis = nowMillis,
                    lastRun = AutoBackupLastRun.Failed(nowMillis, outcome.failure),
                    consecutiveFailures = failures,
                    failureNotified = state.failureNotified || notify,
                ),
                notifyFailure = notify,
                clearFailureNotice = false,
            )
        }
    }

    private const val MAX_RETRY_MILLIS = 12L * HOUR_MILLIS
}

private const val HOUR_MILLIS = 60L * 60 * 1_000
private const val DAY_MILLIS = 24L * HOUR_MILLIS
