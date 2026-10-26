package dev.argus.automation.vm

import dev.argus.data.dao.RuleFireStats
import dev.argus.ui.model.RuleStatsUi
import dev.argus.ui.model.RuleStatsWindow

/** Finestre delle statistiche di una regola (0.4.0, F10). */
internal const val STATS_SHORT_DAYS = 7
internal const val STATS_LONG_DAYS = 30
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000

/**
 * Istanti di inizio delle due finestre rispetto a [nowMillis]: (7 giorni fa, 30 giorni fa). Sono finestre
 * mobili «ultimi N giorni», non giorni di calendario.
 */
internal fun statsWindowStarts(nowMillis: Long): Pair<Long, Long> =
    (nowMillis - STATS_SHORT_DAYS * MILLIS_PER_DAY).coerceAtLeast(0) to
        (nowMillis - STATS_LONG_DAYS * MILLIS_PER_DAY).coerceAtLeast(0)

/**
 * Dal conteggio del journal alle statistiche mostrate. [retentionDays] è la conservazione del Log scelta
 * dall'utente: il journal non ricorda oltre, quindi la finestra lunga REALMENTE coperta è la minore tra
 * 30 giorni e quella (con un minimo di 7, la finestra breve). La UI lo dice invece di mostrare uno zero
 * che non sarebbe vero. Con la sola finestra breve coperta, la lunga coincide con essa: righe vecchie
 * rimaste prima della prossima manutenzione non entrano nei conteggi.
 */
internal fun RuleFireStats.toRuleStatsUi(retentionDays: Int): RuleStatsUi {
    val shortWindow = RuleStatsWindow(fires7, succeeded7, failed7)
    val longDays = retentionDays.coerceIn(STATS_SHORT_DAYS, STATS_LONG_DAYS)
    return RuleStatsUi(
        last7Days = shortWindow,
        last30Days = if (longDays <= STATS_SHORT_DAYS) shortWindow else RuleStatsWindow(fires30, succeeded30, failed30),
        longWindowDays = longDays,
    )
}
