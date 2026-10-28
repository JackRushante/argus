package dev.argus.automation.vm

import dev.argus.data.dao.AuditLogRecord
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.ListSummary
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.NextFireSummary
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.TodayErrorsSummary
import dev.argus.ui.presentation.RenderLanguage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

// =============================================================================
// Riepilogo in testa alla lista (0.7.0, redesign A, nota n1). Funzioni pure: i dati si calcolano
// sull'elenco COMPLETO delle regole (le righe della lista arrivano già filtrate) e sul log di oggi.
// =============================================================================

/** Righe di log lette per «errori di oggi»: oltre questo tetto una giornata non è più un riepilogo. */
internal const val TODAY_LOG_LIMIT = 1_000

/**
 * Riepilogo dell'elenco COMPLETO [allRows]. `null` se non c'è nessuna regola (niente da riassumere: la
 * lista mostra il suo stato vuoto, non un riepilogo di zeri). [todayErrors] `null` = log non letto (n/d).
 */
internal fun listSummaryOf(allRows: List<AutomationRow>, todayErrors: TodayErrorsSummary?): ListSummary? {
    if (allRows.isEmpty()) return null
    val next = allRows
        .mapNotNull { row ->
            val at = row.nextFireAtMillis
            val label = row.nextFireLabel
            if (at != null && label != null) NextFireSummary(row.id, row.name, label, at) else null
        }
        .minByOrNull { it.atMillis }
    return ListSummary(
        armedCount = allRows.count { it.status == StatusBadge.ARMED },
        // Stessa definizione del filtro «In approvazione» e del badge della barra: bozze nuove e
        // regole attive con una modifica in attesa.
        pendingCount = allRows.count { it.status == StatusBadge.PENDING_APPROVAL || it.pendingEditDraftId != null },
        needsReviewCount = allRows.count { it.status == StatusBadge.NEEDS_REVIEW },
        nextFire = next,
        todayErrors = todayErrors,
    )
}

/**
 * Errori di oggi: le righe del log con esito rosso (stesso criterio del Log: azioni fallite, scatti bloccati,
 * armamenti falliti), l'ultima per nome e ora. [records] sono già quelle di oggi (la query le filtra).
 */
internal fun todayErrorsOf(records: List<AuditLogRecord>, language: RenderLanguage): TodayErrorsSummary {
    val failures = records.filter { it.toLogRow(emptyList(), language).outcome == LogOutcome.FAILED }
    val last = failures.maxByOrNull { it.atMillis }
    return TodayErrorsSummary(
        count = failures.size,
        lastRuleName = last?.let { it.automationName ?: language.pick("Deleted automation", "Automazione rimossa") },
        lastTimeLabel = last?.let { clockLabel(it.atMillis) },
    )
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** «12:05» nel fuso del telefono. */
internal fun clockLabel(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(atMillis).atZone(zone).format(CLOCK_FORMAT)

/** Mezzanotte locale che apre il giorno di [now]: da qui in poi le righe del log sono «di oggi». */
internal fun startOfDayMillis(now: ZonedDateTime): Long =
    now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()

/**
 * Inizio del giorno corrente, rinnovato a ogni mezzanotte: la lista aperta a cavallo del cambio di giorno
 * smette di contare gli errori di ieri senza aspettare un'altra emissione.
 *
 * Gira su `Dispatchers.Default`, non sul dispatcher del chiamante: l'attesa fino a mezzanotte e' tempo reale.
 * Su un dispatcher di test con tempo virtuale `delay` salterebbe subito alla mezzanotte, ricalcolerebbe lo
 * stesso giorno e ripartirebbe in un ciclo senza fine che affama il resto. `distinctUntilChanged` evita di
 * riaprire la lettura del log quando il giorno non e' cambiato.
 */
internal fun startOfTodayFlow(): Flow<Long> = flow {
    while (true) {
        val now = ZonedDateTime.now()
        emit(startOfDayMillis(now))
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        delay(Duration.between(now, nextMidnight).toMillis() + 1_000)
    }
}.distinctUntilChanged().flowOn(Dispatchers.Default)
