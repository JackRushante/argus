package dev.argus

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.phone.PhoneEventIngress
import dev.argus.automation.connectivity.ConnectivityEventIngress
import dev.argus.automation.connectivity.ConnectivitySentinelStatus
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.geofence.GeofenceEventIngress
import dev.argus.automation.geofence.GeofenceStateStore
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import dev.argus.automation.ShizukuStaticShellRunner
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
    fun phoneEventIngress(): PhoneEventIngress
    fun connectivityEventIngress(): ConnectivityEventIngress
    fun connectivitySentinelStatus(): ConnectivitySentinelStatus
    fun connectivityTriggerRuntime(): ConnectivityTriggerRuntime
    fun geofenceEventIngress(): GeofenceEventIngress
    fun geofenceStateStore(): GeofenceStateStore
    fun geofenceTriggerRuntime(): GeofenceTriggerRuntime
    fun staticShellRunner(): ShizukuStaticShellRunner
    fun approvalFlow(): ApprovalFlow
    fun automationStore(): AutomationStore
    fun draftRepository(): DraftRepository
    fun timeAlarmRuntime(): TimeAlarmRuntime
    fun timeAlarmBackend(): TimeAlarmBackend
    fun database(): ArgusDatabase
    fun shizukuGateway(): ShizukuGateway
    fun deviceController(): DeviceController
}
