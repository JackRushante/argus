package dev.argus.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.argus.device.StateReader
import dev.argus.device.ParametricStateReader
import dev.argus.automation.connectivity.ConnectivityEventDispatcher
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime
import dev.argus.automation.geofence.GeofenceEventDispatcher
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import dev.argus.automation.geofence.NoopGeofenceTriggerRuntime
import dev.argus.automation.sensor.NoopSensorTriggerRuntime
import dev.argus.automation.sensor.SensorEventDispatcher
import dev.argus.automation.sensor.SensorTriggerRuntime
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.NoopAuditSink
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.GeoPoint
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Legge lo snapshot solo quando l'Engine trova una regola che ne ha davvero bisogno. */
fun interface DeviceStateSnapshotProvider {
    suspend fun current(): DeviceState

    /** Compatibilità per fake/client v1: la produzione override per evitare la lettura globale. */
    suspend fun current(request: StateReadRequest): DeviceState = request.project(current())
}

class LazyDeviceStateProvider(
    private val reader: StateReader,
    private val shizukuStatus: () -> ShizukuGatewayStatus,
    private val location: CurrentLocationProvider,
    private val parametricReader: ParametricStateReader? = null,
) : DeviceStateSnapshotProvider {
    constructor(
        reader: StateReader,
        shizuku: ShizukuGateway,
        location: CurrentLocationProvider,
        parametricReader: ParametricStateReader,
    ) : this(reader, shizuku::status, location, parametricReader)

    override suspend fun current(): DeviceState = current(StateReadRequest.LEGACY_GENERATIVE)

    override suspend fun current(request: StateReadRequest): DeviceState {
        if (request.isEmpty) return DeviceState()
        val needsPrivilegedState = request.keys.isNotEmpty() || request.includeForegroundApp ||
            request.queries.isNotEmpty()
        val authorized = needsPrivilegedState &&
            runCatching { shizukuStatus() == ShizukuGatewayStatus.AUTHORIZED }.getOrDefault(false)
        val privileged = if (authorized && (request.keys.isNotEmpty() || request.includeForegroundApp)) {
            try {
                reader.read(request.keys, request.includeForegroundApp)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DeviceState()
            }
        } else {
            DeviceState()
        }
        val queryValues = if (authorized && request.queries.isNotEmpty()) {
            try {
                parametricReader?.read(request.queries).orEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        val point = if (request.includeLocation) {
            try {
                location.current()?.takeIf(DeviceLocation::valid)?.let {
                    GeoPoint(it.latitude, it.longitude)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return request.project(privileged.copy(location = point, queryValues = queryValues))
    }
}

class EngineTimeEventDispatcher(
    private val engine: Engine,
    private val state: LazyDeviceStateProvider,
) : TimeEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { request -> state.current(request) }
    }
}

/** Dispatcher del trigger immediato: fira on-arm, senza scheduling OS. Mirror di [TimeEventDispatcher]. */
fun interface ImmediateEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

class EngineImmediateEventDispatcher(
    private val engine: Engine,
    private val state: LazyDeviceStateProvider,
) : ImmediateEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { request -> state.current(request) }
    }
}

fun interface NotificationEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

class EngineNotificationEventDispatcher(
    private val engine: Engine,
    private val state: DeviceStateSnapshotProvider,
) : NotificationEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { request -> state.current(request) }
    }
}

/** Stesso pattern per la telefonia (P2-2): l'engine matcha, deduplica e decide da solo. */
class EnginePhoneEventDispatcher(
    private val engine: Engine,
    private val state: DeviceStateSnapshotProvider,
) : dev.argus.automation.phone.PhoneEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { request -> state.current(request) }
    }
}

class EngineConnectivityEventDispatcher(
    private val engine: Engine,
    private val state: DeviceStateSnapshotProvider,
) : ConnectivityEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { request -> state.current(request) }
    }
}

class EngineGeofenceEventDispatcher(
    private val engine: Engine,
    private val state: DeviceStateSnapshotProvider,
) : GeofenceEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { request -> state.current(request) }
    }
}

class EngineSensorEventDispatcher(
    private val engine: Engine,
    private val state: DeviceStateSnapshotProvider,
) : SensorEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { request -> state.current(request) }
    }
}

/**
 * Registrar per-trigger senza service persistente: Time registra presso AlarmManager tramite il
 * coordinator; Notification non ha una registrazione OS per-rule e richiede soltanto il grant
 * globale del listener più lo snapshot persistito ARMED. Ogni altro trigger resta fail-closed.
 */
class AndroidArmedAutomationRegistrar(
    private val coordinator: TimeAlarmCoordinator,
    private val store: AutomationStore,
    private val snapshots: FirePolicySnapshotProvider,
    private val connectivity: ConnectivityTriggerRuntime = NoopConnectivityTriggerRuntime,
    private val geofence: GeofenceTriggerRuntime = NoopGeofenceTriggerRuntime,
    private val sensor: SensorTriggerRuntime = NoopSensorTriggerRuntime,
    // Il trigger immediato non registra nulla presso l'OS: fira on-arm dispatchando subito al motore.
    private val immediateDispatcher: ImmediateEventDispatcher = ImmediateEventDispatcher { },
    // Clock iniettabile per un id univoco per-arm (un RE-ARM ri-fira, niente dedup permanente).
    private val now: () -> java.time.Instant = java.time.Instant::now,
    private val audit: AuditSink = NoopAuditSink,
) : ArmedAutomationRegistrar {
    override suspend fun register(automation: Automation): Boolean = when (automation.trigger) {
        is Trigger.Time -> registerTime(automation)
        is Trigger.Immediate -> registerImmediate(automation)
        is Trigger.Notification -> registerNotification(automation)
        // I receiver manifest sono sempre attivi: la registrazione OS è il grant runtime
        // stesso, verificato qui con la capability granulare dell'evento (sms vs call).
        is Trigger.PhoneState -> registerBroadcastBacked(automation)
        is Trigger.Connectivity -> registerConnectivity(automation)
        is Trigger.Geofence -> registerGeofence(automation)
        is Trigger.Sensor -> registerSensor(automation)
    }

    private suspend fun registerTime(automation: Automation): Boolean = try {
        val report = coordinator.reconcile(ReconcileReason.CAPABILITY_CHANGED)
        val persisted = store.get(automation.id)
        automation.id !in report.failed && persisted?.status == AutomationStatus.ARMED &&
            persisted.enabled
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    /**
     * Trigger immediato: nessuna registrazione OS. All'arm dispatcha subito un [TriggerEvent.ImmediateFired]
     * al motore (che esegue le azioni), poi consuma la regola one-shot disabilitandola come un time
     * one-shot dopo il fire. L'id include il timestamp corrente così un RE-ARM ri-fira senza dedup.
     */
    private suspend fun registerImmediate(automation: Automation): Boolean {
        return try {
            val fingerprint = automation.approvalFingerprint ?: return false
            val persisted = store.get(automation.id)
            if (persisted?.status != AutomationStatus.ARMED || !persisted.enabled) return false
            val envelope = TriggerEnvelope(
                id = TriggerEventId("immediate:${fingerprint.value}:${now().toEpochMilli()}"),
                event = TriggerEvent.ImmediateFired(automation.id, fingerprint),
            )
            immediateDispatcher.dispatch(envelope)
            // Consuma la regola one-shot dopo il fire, come TimeAlarmCoordinator per un time one-shot.
            if (store.disableIfApproved(automation.id, fingerprint)) {
                // Lifecycle (task #31-B): il disable post-fire va tracciato, mai silenzioso.
                recordLifecycle(automation.id, AuditKind.RULE_DISABLED, "one_shot_consumed")
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun registerNotification(automation: Automation): Boolean = try {
        val snapshot = snapshots.current()
        val persisted = store.get(automation.id)
        CapabilityIds.TRIGGER_NOTIFICATION in snapshot.availableCapabilities &&
            persisted?.status == AutomationStatus.ARMED && persisted.enabled
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun registerBroadcastBacked(automation: Automation): Boolean = try {
        val snapshot = snapshots.current()
        val persisted = store.get(automation.id)
        val required = CapabilityRequirements.derive(automation.trigger, emptyList())
        required.all { it in snapshot.availableCapabilities } &&
            persisted?.status == AutomationStatus.ARMED && persisted.enabled
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun registerConnectivity(automation: Automation): Boolean {
        if (!registerBroadcastBacked(automation)) return false
        val medium = (automation.trigger as Trigger.Connectivity).medium
        if (medium == ConnMedium.BT) return true
        return try {
            val report = connectivity.reconcile()
            report.active && automation.id in report.requiredBy && automation.id !in report.failed
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun registerGeofence(automation: Automation): Boolean {
        if (!registerBroadcastBacked(automation)) return false
        return try {
            val report = geofence.reconcile(recreateOsRegistrations = false)
            automation.id in report.requiredBy && automation.id !in report.failed
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun registerSensor(automation: Automation): Boolean {
        // Il pre-check verifica la capability granulare `trigger.sensor.<kind>`, pubblicata dal
        // probe solo quando hardware, grant e backend collegato coincidono.
        if (!registerBroadcastBacked(automation)) return false
        return try {
            val report = sensor.reconcile()
            automation.id in report.requiredBy &&
                automation.id !in report.needsReview &&
                automation.id !in report.failed
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun recordLifecycle(
        automationId: AutomationId,
        kind: AuditKind,
        reason: String,
    ) {
        try {
            audit.record(
                AuditEvent(
                    automationId,
                    kind,
                    now().toEpochMilli(),
                    detail = reason,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il logging non deve cambiare l'esito della registrazione.
        }
    }
}

/** Posizione one-shot, senza cache e senza inventare coordinate in assenza di grant/provider. */
class FrameworkCurrentLocationProvider(context: Context) : CurrentLocationProvider {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private val directExecutor = Executor(Runnable::run)

    override suspend fun current(): DeviceLocation? = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return@withTimeoutOrNull null
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                manager.hasProvider(LocationManager.FUSED_PROVIDER) &&
                manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            manager.hasProviderCompat(LocationManager.NETWORK_PROVIDER) &&
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            fine && manager.hasProviderCompat(LocationManager.GPS_PROVIDER) &&
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> return@withTimeoutOrNull null
        }
        suspendCancellableCoroutine { continuation ->
            val cancellation = android.os.CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                manager.getCurrentLocation(provider, cancellation, directExecutor) { location ->
                    if (continuation.isActive) {
                        continuation.resume(
                            location?.let {
                                DeviceLocation(
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    horizontalAccuracyMeters = it.accuracy
                                        .takeIf { accuracy ->
                                            it.hasAccuracy() && accuracy.isFinite() && accuracy >= 0f
                                        }
                                        ?.toDouble(),
                                    ageMillis = (
                                        SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos
                                        ).coerceAtLeast(0L) / 1_000_000L,
                                )
                            }
                                ?.takeIf(DeviceLocation::valid),
                        )
                    }
                }
            } catch (error: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (error: RuntimeException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun LocationManager.hasProviderCompat(provider: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasProvider(provider)
        } else {
            provider in allProviders
        }

    private companion object {
        const val LOCATION_TIMEOUT_MILLIS = 15_000L
    }
}

class AndroidAutomationNotifier(context: Context) : AutomationNotifier {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        ensureChannel()
    }

    fun ensureChannel() {
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.argus_automation_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(
                    R.string.argus_automation_channel_description,
                )
            },
        )
    }

    override suspend fun show(title: String, text: String, context: FireContext) {
        check(canPost()) { "notification_permission_unavailable" }
        val contentIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    appContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val safeTitle = title.take(MAX_TITLE_CHARS)
        val safeText = text.take(MAX_TEXT_CHARS)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setContentTitle(safeTitle)
            .setContentText(safeText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeText))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .build()
        try {
            manager.notify(context.executionId.value.hashCode(), notification)
        } catch (error: SecurityException) {
            throw IllegalStateException("notification_permission_unavailable", error)
        }
    }

    private fun canPost(): Boolean = (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) && manager.areNotificationsEnabled()

    private companion object {
        const val CHANNEL_ID = "argus_automations"
        const val MAX_TITLE_CHARS = 160
        const val MAX_TEXT_CHARS = 4_096
    }
}
