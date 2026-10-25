package dev.argus.automation

import android.util.Log
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.data.RoomJournalMaintenance
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.NoopAuditSink
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.connectivity.NoopConnectivityTriggerRuntime
import dev.argus.automation.connectivity.ConnectivityEventIngress
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import dev.argus.automation.geofence.NoopGeofenceTriggerRuntime
import dev.argus.automation.phone.PhoneEventIngress
import dev.argus.automation.sensor.NoopSensorTriggerRuntime
import dev.argus.automation.sensor.SensorEventIngress
import dev.argus.automation.sensor.SensorTriggerRuntime

/** Bootstrap idempotente del runtime event-driven, condiviso da Application e lifecycle processo. */
class ArgusRuntimeController(
    private val scope: CoroutineScope,
    private val scheduler: TimeAlarmRuntime,
    private val capabilities: CapabilityReconciler,
    private val maintenance: RoomJournalMaintenance,
    private val shizukuStatus: Flow<ShizukuGatewayStatus>,
    private val preferences: AppPreferencesStore,
    private val replyRegistry: ActiveNotificationReplyRegistry,
    private val connectivity: ConnectivityTriggerRuntime = NoopConnectivityTriggerRuntime,
    private val geofence: GeofenceTriggerRuntime = NoopGeofenceTriggerRuntime,
    private val connectivityIngress: ConnectivityEventIngress? = null,
    private val phoneIngress: PhoneEventIngress? = null,
    private val sensor: SensorTriggerRuntime = NoopSensorTriggerRuntime,
    private val sensorIngress: SensorEventIngress? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * Store delle regole, per due compiti del ciclo di vita: disarmare all'avvio le regole
     * «subito» interrotte a metà (B31a) e togliere la registrazione del sensore quando l'ultima
     * regola sensore sparisce, ad esempio eliminata (B31c). Senza store entrambi sono inattivi.
     */
    private val store: AutomationStore? = null,
    private val audit: AuditSink = NoopAuditSink,
    /**
     * `true` se nessun dispatch di evento è in corso nel processo. La pulizia periodica non parte
     * mentre un programma lungo è in esecuzione: marcherebbe come interrotta un'esecuzione viva.
     */
    private val dispatchesIdle: () -> Boolean = { true },
) {
    private val started = AtomicBoolean(false)
    private val reconcileMutex = Mutex()
    private val periodicMaintenance = PeriodicMaintenance(
        run = { now -> maintenance.run(now) },
        nowMillis = nowMillis,
        canRun = dispatchesIdle,
    )

    /** Istante di avvio di QUESTO processo: ciò che è stato scattato prima è di un processo morto. */
    @Volatile
    private var startedAtMillis: Long = Long.MAX_VALUE

    fun start() {
        if (!started.compareAndSet(false, true)) return
        startedAtMillis = nowMillis()
        scope.launch {
            // Prima la pulizia, poi il reconcile: stesso ordine di sempre all'avvio.
            runMaintenanceIfDue()
            reconcile(ReconcileReason.APP_START)
        }
        scope.launch {
            shizukuStatus
                .drop(1)
                .collect { reconcile(ReconcileReason.CAPABILITY_CHANGED) }
        }
        scope.launch {
            // revokePrivacy() avviene con l'app già foreground: senza questo collector il
            // runtime resterebbe armato fino al prossimo ON_START.
            preferences.observe()
                .map { it.privacyAccepted }
                .distinctUntilChanged()
                .drop(1)
                .collect { accepted ->
                    if (!accepted) replyRegistry.clear()
                    reconcile(ReconcileReason.CAPABILITY_CHANGED)
                }
        }
        store?.let { rules ->
            scope.launch {
                // Quando l'insieme delle regole sensore armate cambia (per esempio l'ultima viene
                // eliminata) la registrazione fisica e il servizio devono seguirlo: reconcile è
                // idempotente e il coordinator sensori serializza da sé le chiamate concorrenti.
                // La prima emissione è lo stato iniziale, già coperto dal reconcile di avvio.
                rules.observeAll()
                    .map { automations ->
                        automations
                            .filter { it.enabled && it.trigger is Trigger.Sensor }
                            .map { it.id }
                            .toSet()
                    }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        runCatchingPreservingCancellation { sensor.reconcile() }
                            .onFailure { error ->
                                Log.w(TAG, "reconcile sensor fallito: ${error::class.java.simpleName}")
                            }
                    }
            }
        }
    }

    fun onForeground() {
        if (!started.get()) return
        maintenanceIfDue()
        scope.launch { reconcile(ReconcileReason.CAPABILITY_CHANGED) }
    }

    /**
     * Pulizia dei dati vecchi, al massimo una volta ogni 24 h di orologio reale (B28). Parte in
     * background e non blocca chi la chiama: la usano `onForeground` e la fine di ogni dispatch.
     */
    fun maintenanceIfDue() {
        if (!periodicMaintenance.isDue()) return
        scope.launch { runMaintenanceIfDue() }
    }

    internal suspend fun runMaintenanceIfDue(): Boolean =
        runCatchingPreservingCancellation { periodicMaintenance.runIfDue() }
            .onFailure { error ->
                Log.w(TAG, "manutenzione fallita: ${error::class.java.simpleName}")
            }
            .getOrDefault(false)

    internal suspend fun reconcile(reason: ReconcileReason) = reconcileMutex.withLock {
        runCatchingPreservingCancellation { capabilities.reconcile() }
            .onFailure { error ->
                Log.w(TAG, "reconcile capability fallito: ${error::class.java.simpleName}")
            }
        if (reason == ReconcileReason.APP_START) {
            runCatchingPreservingCancellation { disarmInterruptedImmediates() }
                .onFailure { error ->
                    Log.w(TAG, "recupero regole immediate fallito: ${error::class.java.simpleName}")
                }
        }
        // I recuperi dovuti del coordinator partono in background (RecoveryLauncher): il reconcile
        // non aspetta un programma che può durare un'ora.
        runCatchingPreservingCancellation { scheduler.reconcile(reason) }
            .onFailure { error ->
                Log.w(TAG, "reconcile scheduler fallito: ${error::class.java.simpleName}")
            }
        if (reason == ReconcileReason.APP_START) {
            // Un recupero per sorgente, in background: ognuno ha la sua coda sequenziale
            // nell'ingress (SequentialLanes), quindi l'ordine per sorgente si preserva e un evento
            // già in consegna non viene riconsegnato. Il reconcile non ne attende la fine.
            phoneIngress?.let { ingress ->
                launchRecovery("chiamata") { ingress.recoverPending() }
            }
            connectivityIngress?.let { ingress ->
                launchRecovery("connectivity") { ingress.recoverPending() }
            }
        }
        runCatchingPreservingCancellation { connectivity.reconcile() }
            .onFailure { error ->
                Log.w(TAG, "reconcile connectivity fallito: ${error::class.java.simpleName}")
            }
        runCatchingPreservingCancellation {
            geofence.reconcile(
                recreateOsRegistrations = reason == ReconcileReason.APP_START ||
                    reason.recreateOsRegistration,
            )
        }.onFailure { error ->
            Log.w(TAG, "reconcile geofence fallito: ${error::class.java.simpleName}")
        }
        // I sensori one-shot non sopravvivono a process death, reboot o package replace: ogni
        // reconcile li ri-registra dallo stato armato desiderato. requiredBy autorizza la
        // redelivery dei soli pending di regole ancora armate, senza dare lo store al controller.
        val sensorReport = runCatchingPreservingCancellation { sensor.reconcile() }
            .onFailure { error ->
                Log.w(TAG, "reconcile sensor fallito: ${error::class.java.simpleName}")
            }
            .getOrNull()
        if (reason == ReconcileReason.APP_START && sensorReport != null) {
            sensorIngress?.let { ingress ->
                val required = sensorReport.requiredBy.toSet()
                launchRecovery("sensor") { ingress.recoverPending(required) }
            }
        }
        Unit
    }

    private fun launchRecovery(label: String, block: suspend () -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { block() }
                .onFailure { error ->
                    Log.w(TAG, "recovery $label fallito: ${error::class.java.simpleName}")
                }
        }
    }

    /**
     * Una regola «subito» fira una sola volta all'arm e si disarma a esecuzione conclusa. Se
     * all'avvio è ancora armata ma ha già un claim di scatto di un processo precedente
     * (`lastFiredAt` anteriore a questo avvio), l'app è stata uccisa a metà: si disarma con un
     * audit e NON si riesegue, perché potrebbe ripetere effetti già avvenuti. Il confronto con
     * l'avvio del processo evita di toccare una regola armata e in esecuzione proprio ora.
     */
    private suspend fun disarmInterruptedImmediates() {
        val rules = store ?: return
        rules.armed()
            .filter { it.trigger is Trigger.Immediate }
            .forEach { automation ->
                val fingerprint = automation.approvalFingerprint ?: return@forEach
                val firedAt = rules.lastFiredAt(automation.id) ?: return@forEach
                if (firedAt >= startedAtMillis) return@forEach
                if (rules.disableIfApproved(automation.id, fingerprint)) {
                    recordInterrupted(automation.id)
                }
            }
    }

    private suspend fun recordInterrupted(id: dev.argus.engine.model.AutomationId) {
        runCatchingPreservingCancellation {
            audit.record(
                AuditEvent(
                    id,
                    AuditKind.ERROR,
                    nowMillis(),
                    detail = EXECUTION_INTERRUPTED,
                ),
            )
        }
    }

    private suspend fun <T> runCatchingPreservingCancellation(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private companion object {
        const val TAG = "ArgusRuntime"
        const val EXECUTION_INTERRUPTED = "execution_interrupted"
    }
}
