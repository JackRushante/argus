package dev.argus.automation.manual

import dev.argus.engine.model.Automation

/** Stato visivo di un riquadro delle impostazioni rapide, indipendente da `android.service.quicksettings`. */
enum class TileUiState { ACTIVE, INACTIVE, UNAVAILABLE }

/** Nota sotto l'etichetta del riquadro; il servizio la traduce in una stringa. */
enum class TileNote { NONE, NO_RULE, PAUSED }

/**
 * Riquadro «Argus: esegui regola» (F8). L'etichetta è il nome della regola scelta nell'app; senza una
 * regola manuale armata il riquadro è non disponibile e dice di sceglierne una.
 */
data class ManualTileSpec(val ruleName: String?, val state: TileUiState, val note: TileNote)

/** Riquadro «Pausa automazioni» (F21): attivo = in pausa. */
data class PauseTileSpec(val state: TileUiState)

object QuickTileModels {
    /** Lunghezza massima del nome sul riquadro: il resto lo tagliano comunque le impostazioni rapide. */
    const val MAX_LABEL_CHARS = 40

    fun manualTile(rule: Automation?, paused: Boolean): ManualTileSpec {
        if (rule == null || !ManualRuleCatalog.isRunnableManual(rule)) {
            return ManualTileSpec(ruleName = null, state = TileUiState.UNAVAILABLE, note = TileNote.NO_RULE)
        }
        return ManualTileSpec(
            ruleName = rule.name.take(MAX_LABEL_CHARS),
            state = TileUiState.INACTIVE,
            note = if (paused) TileNote.PAUSED else TileNote.NONE,
        )
    }

    fun pauseTile(paused: Boolean): PauseTileSpec =
        PauseTileSpec(if (paused) TileUiState.ACTIVE else TileUiState.INACTIVE)
}

/**
 * Logica dei due riquadri, senza `TileService`: il servizio legge lo spec e disegna, al tocco chiama
 * [onManualTileClick] / [onPauseTileClick].
 */
class QuickTileController(
    private val selection: ManualTileSelection,
    private val catalog: ManualRuleCatalog,
    private val runner: ManualRuleRunner,
    private val pause: AutomationPauseStore,
) {
    suspend fun manualSpec(): ManualTileSpec {
        val rule = selection.ruleId.value?.let { catalog.runnable(it) }
        return QuickTileModels.manualTile(rule, pause.isPaused())
    }

    fun pauseSpec(): PauseTileSpec = QuickTileModels.pauseTile(pause.isPaused())

    /** null = nessuna regola manuale armata scelta: il tocco non fa niente. */
    suspend fun onManualTileClick(): ManualRunResult? {
        val id = selection.ruleId.value ?: return null
        catalog.runnable(id) ?: return null
        return runner.runFromTile(id)
    }

    /** Inverte la pausa e restituisce il nuovo valore. */
    fun onPauseTileClick(): Boolean {
        val next = !pause.isPaused()
        pause.setPaused(next)
        return next
    }
}
