package dev.argus.automation.manual

import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.DispatchFrame
import dev.argus.automation.foreground.ReceiverWorkLauncher
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.TriggerEnvelope
import kotlinx.coroutines.Job

/**
 * Consegna l'evento manuale al motore con la stessa cornice di ogni altro scatto: wakelock per la
 * durata del programma, conteggio dei dispatch in corso, manutenzione periodica a fine dispatch.
 */
class EngineManualEventDispatcher(
    private val engine: Engine,
    private val state: DeviceStateSnapshotProvider,
    private val frame: DispatchFrame = DispatchFrame.NONE,
) : ManualEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        frame.run { engine.onTrigger(envelope) { request -> state.current(request) } }
    }
}

/**
 * Il lavoro manuale gira sul lanciatore condiviso: un programma lungo (attese, chiamate AI) tiene il
 * processo vivo con la lease del servizio in primo piano, anche se l'attività trampolino si chiude.
 */
class ReceiverManualWorkLauncher(
    private val launcher: ReceiverWorkLauncher,
) : ManualWorkLauncher {
    override fun launch(automationId: AutomationId, block: suspend () -> Unit): Job =
        launcher.launch(SOURCE, automationId, block)

    private companion object {
        const val SOURCE = "manual"
    }
}
