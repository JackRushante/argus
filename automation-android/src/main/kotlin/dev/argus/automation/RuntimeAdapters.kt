package dev.argus.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.argus.device.StateReader
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.TriggerEnvelope
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
}

class LazyDeviceStateProvider(
    private val reader: StateReader,
    private val shizuku: ShizukuGateway,
) : DeviceStateSnapshotProvider {
    override suspend fun current(): DeviceState {
        if (shizuku.status() != ShizukuGatewayStatus.AUTHORIZED) return DeviceState()
        return reader.read(StateKeys.ALL.keys, includeForegroundApp = true)
    }
}

class EngineTimeEventDispatcher(
    private val engine: Engine,
    private val state: LazyDeviceStateProvider,
) : TimeEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { state.current() }
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
        engine.onTrigger(envelope) { state.current() }
    }
}

/** Stesso pattern per la telefonia (P2-2): l'engine matcha, deduplica e decide da solo. */
class EnginePhoneEventDispatcher(
    private val engine: Engine,
    private val state: DeviceStateSnapshotProvider,
) : dev.argus.automation.phone.PhoneEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        engine.onTrigger(envelope) { state.current() }
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
) : ArmedAutomationRegistrar {
    override suspend fun register(automation: Automation): Boolean = when (automation.trigger) {
        is Trigger.Time -> registerTime(automation)
        is Trigger.Notification -> registerNotification(automation)
        else -> false
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
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return@withTimeoutOrNull null
        }
        suspendCancellableCoroutine { continuation ->
            val cancellation = android.os.CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                manager.getCurrentLocation(provider, cancellation, directExecutor) { location ->
                    if (continuation.isActive) {
                        continuation.resume(
                            location?.let { DeviceLocation(it.latitude, it.longitude) }
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
