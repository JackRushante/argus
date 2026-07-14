package dev.argus

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.TimeAlarmBackend
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.brain.BridgeConfigurationStore
import dev.argus.data.ArgusDatabase
import dev.argus.device.DeviceController
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.safety.DraftRepository
import dev.argus.shizuku.ShizukuGateway

/** Accesso diagnostico disponibile solo nell'APK debug per gli instrumented test. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ArgusApplicationEntryPoint {
    fun appPreferencesStore(): AppPreferencesStore
    fun bridgeConfigurationStore(): BridgeConfigurationStore
    fun brain(): Brain
    fun capabilityProbe(): CapabilityProbe
    fun deviceStateSnapshotProvider(): DeviceStateSnapshotProvider
    fun approvalFlow(): ApprovalFlow
    fun automationStore(): AutomationStore
    fun draftRepository(): DraftRepository
    fun timeAlarmRuntime(): TimeAlarmRuntime
    fun timeAlarmBackend(): TimeAlarmBackend
    fun database(): ArgusDatabase
    fun shizukuGateway(): ShizukuGateway
    fun deviceController(): DeviceController
}
