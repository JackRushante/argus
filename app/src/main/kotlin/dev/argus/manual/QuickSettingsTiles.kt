package dev.argus.manual

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.argus.R
import dev.argus.automation.manual.ManualRunResult
import dev.argus.automation.manual.TileNote
import dev.argus.automation.manual.TileUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private fun TileUiState.toTileState(): Int = when (this) {
    TileUiState.ACTIVE -> Tile.STATE_ACTIVE
    TileUiState.INACTIVE -> Tile.STATE_INACTIVE
    TileUiState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
}

/**
 * Riquadro «Argus: esegui regola» (F8). La regola si sceglie nell'app (Dettaglio → «Usa nel riquadro»)
 * e l'etichetta è il suo nome. Il tocco passa dal runner come ogni altro ingresso manuale; lo spec
 * (etichetta, stato, nota) lo decide `QuickTileController`, qui si disegna soltanto.
 */
class ManualRuleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val entry = manualEntryPoint()
        listening?.cancel()
        listening = scope.launch {
            // La scelta della regola o la pausa cambiano mentre il pannello è aperto: si ridisegna.
            combine(entry.tileSelection().ruleId, entry.pause().paused) { _, _ -> }
                .collect { render() }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        // A telefono bloccato il gesto aspetta lo sblocco: una regola non parte su un telefono chiuso.
        unlockAndRun {
            scope.launch {
                when (val result = manualEntryPoint().tiles().onManualTileClick()) {
                    null -> toast(getString(R.string.manual_toast_no_rule))
                    is ManualRunResult.Accepted ->
                        if (result.paused) toast(getString(R.string.manual_toast_paused))
                    is ManualRunResult.Rejected -> toast(getString(R.string.manual_toast_unavailable))
                }
                render()
            }
        }
    }

    private suspend fun render() {
        val tile = qsTile ?: return
        val spec = manualEntryPoint().tiles().manualSpec()
        val base = getString(R.string.manual_tile_label)
        tile.label = spec.ruleName ?: base
        tile.subtitle = when (spec.note) {
            TileNote.NONE -> base
            TileNote.NO_RULE -> getString(R.string.manual_tile_none)
            TileNote.PAUSED -> getString(R.string.pause_tile_on)
        }
        tile.state = spec.state.toTileState()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_manual)
        tile.updateTile()
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Riquadro «Pausa automazioni» (F21): attivo = in pausa. Il tocco inverte l'interruttore globale; lo
 * stato segue anche le modifiche fatte da Impostazioni o dal banner della lista.
 */
class PauseTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val pause = manualEntryPoint().pause()
        listening?.cancel()
        listening = scope.launch { pause.paused.collect { render() } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        // Come per l'esecuzione: a telefono bloccato l'interruttore aspetta lo sblocco, così chi ha in
        // mano un telefono chiuso non può spegnere le automazioni.
        unlockAndRun {
            manualEntryPoint().tiles().onPauseTileClick()
            render()
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val controller = manualEntryPoint().tiles()
        val spec = controller.pauseSpec()
        tile.label = getString(R.string.pause_tile_label)
        tile.subtitle = getString(
            if (spec.state == TileUiState.ACTIVE) R.string.pause_tile_on else R.string.pause_tile_off,
        )
        tile.state = spec.state.toTileState()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_pause)
        tile.updateTile()
    }
}
