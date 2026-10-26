package dev.argus.automation.triggers

import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.DispatchFrame
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.TriggerEnvelope

class EngineDeviceEventDispatcher(
    private val engine: Engine,
    private val state: DeviceStateSnapshotProvider,
    private val frame: DispatchFrame = DispatchFrame.NONE,
) : DeviceEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        frame.run { engine.onTrigger(envelope) { request -> state.current(request) } }
    }
}
