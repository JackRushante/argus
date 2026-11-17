package dev.argus.manual

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.argus.automation.di.ApplicationScope
import dev.argus.automation.manual.AutomationPauseStore
import dev.argus.automation.manual.ManualRuleCatalog
import dev.argus.data.RoomUserModeStore
import dev.argus.engine.runtime.AutomationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Tiene aggiornato il widget delle regole manuali: lo ridisegna quando cambia l'insieme (id e nome)
 * delle regole manuali armate o la pausa. Parte con l'applicazione; il widget si aggiorna comunque da
 * solo quando il sistema lo ricrea.
 */
class ManualWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AutomationStore,
    private val pause: AutomationPauseStore,
    private val modes: RoomUserModeStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val manualRules = store.observeAll()
                .map { rules ->
                    rules.filter { ManualRuleCatalog.isRunnableManual(it) }.map { it.id.value to it.name }.toSet()
                }
                .distinctUntilChanged()
            // 0.8: anche l'elenco delle modalità e quella attiva (righe modalità del widget).
            val modeRows = modes.observe().map { it.modes to it.active }.distinctUntilChanged()
            combine(manualRules, pause.paused, modeRows) { _, _, _ -> }
                .collect { runCatching { ManualWidgetProvider.refreshAll(context) } }
        }
    }
}
