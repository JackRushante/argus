package dev.argus.automation.sensor

import dev.argus.automation.connectivity.ConnectivitySentinelBackend
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Demand FGS assente: usato dai test del solo coordinator, dove il sentinel non serve. */
object NoopForegroundDemand : ConnectivitySentinelBackend {
    override suspend fun start(): Boolean = true
    override suspend fun stop(): Boolean = true
}

/**
 * Esito tipizzato della registrazione fisica di un trigger sensore. Distingue i casi che il
 * coordinator deve trattare diversamente: un `Unavailable` strutturale (hardware assente o mode
 * incompatibile) porta a `NEEDS_REVIEW`, un `Failure` transitorio a un retry bounded.
 */
sealed interface SensorRegistrationOutcome {
    data object Registered : SensorRegistrationOutcome
    data object AlreadyRegistered : SensorRegistrationOutcome
    data object Unavailable : SensorRegistrationOutcome
    data class Failure(val reason: String) : SensorRegistrationOutcome
}

/**
 * Registrazione FISICA di un sensore one-shot (Android `requestTriggerSensor`), astratta per il
 * test. Una sola registrazione per [SensorKind]: più regole dello stesso kind condividono lo
 * stesso `TriggerEventListener`, e il fan-out verso le regole avviene a valle nell'ingress.
 * Non usa Shizuku: i sensori restano disponibili durante un outage Shizuku.
 */
interface SensorTriggerBackend {
    fun register(kind: SensorKind): SensorRegistrationOutcome
    fun cancel(kind: SensorKind): Boolean
}

data class SensorReconcileReport(
    val requiredBy: List<AutomationId>,
    val registeredKinds: Set<SensorKind>,
    val needsReview: List<AutomationId> = emptyList(),
    val failed: List<AutomationId> = emptyList(),
    val cleanupSucceeded: Boolean = true,
)

fun interface SensorTriggerRuntime {
    suspend fun reconcile(): SensorReconcileReport
}

object NoopSensorTriggerRuntime : SensorTriggerRuntime {
    override suspend fun reconcile() = SensorReconcileReport(emptyList(), emptySet())
}

/**
 * Il rearm di un sensore one-shot dopo lo scatto, visto dall'ingress. Separato da
 * [SensorTriggerRuntime] così l'ingress dipende solo da ciò che gli serve.
 */
interface SensorRearmer {
    /** Il one-shot è scattato: la registrazione fisica non esiste più. */
    suspend fun markConsumed(kind: SensorKind)

    /** Ri-registra i sensori che le regole armate richiedono ancora; cancella gli altri. */
    suspend fun rearm(): SensorReconcileReport
}

/**
 * Coordinator single-writer del runtime sensori.
 *
 * Il set delle registrazioni fisiche vive SOLO in memoria ([registeredKinds]): è stato
 * process-local, non verità persistibile. Dopo process recreation questo set è vuoto e il
 * reconcile ri-registra dallo `store.armed()` desiderato — così un crash non lascia mai un falso
 * "registrato ma morto" (handoff §7.4). Il generation-id è quindi implicito: la memoria stessa è
 * la generazione.
 *
 * Registrazione CONDIVISA per kind: la prima regola eleggibile di un kind registra il sensore
 * fisico; le successive aggiornano solo il demand logico; l'ultima rimossa lo cancella.
 */
class SensorTriggerCoordinator(
    private val store: AutomationStore,
    private val backend: SensorTriggerBackend,
    private val implementedKinds: Set<SensorKind>,
    /**
     * Demand sul FGS condiviso: tenere vivo il processo perché il `TriggerEventListener` riceva i
     * callback in background. `start()` quando c'è ≥1 kind registrato, `stop()` quando nessuno.
     * Default no-op così i test del solo coordinator non richiedono il sentinel.
     */
    private val foregroundDemand: ConnectivitySentinelBackend = NoopForegroundDemand,
) : SensorTriggerRuntime, SensorRearmer {
    private val mutex = Mutex()
    private val registeredKinds = mutableSetOf<SensorKind>()

    override suspend fun rearm(): SensorReconcileReport = reconcile()

    /**
     * Un sensore one-shot si disattiva da solo dopo aver scattato: la registrazione fisica non
     * esiste più. Segnalarlo prima del rearm è indispensabile, altrimenti [reconcile] crederebbe
     * il kind ancora registrato e non lo ri-armerebbe. Usa lo stesso mutex di [reconcile] perché
     * muta lo stesso set: mescolare `@Synchronized` e `Mutex` non darebbe mutua esclusione.
     */
    override suspend fun markConsumed(kind: SensorKind) = mutex.withLock {
        registeredKinds.remove(kind)
        Unit
    }

    override suspend fun reconcile(): SensorReconcileReport = mutex.withLock {
        val eligible = store.armed().mapNotNull { automation ->
            val kind = (automation.trigger as? Trigger.Sensor)?.kind ?: return@mapNotNull null
            if (kind !in implementedKinds) null else automation.id to kind
        }
        val desiredKinds = eligible.map { it.second }.toSet()

        val needsReview = mutableListOf<AutomationId>()
        val failed = mutableListOf<AutomationId>()
        var cleanupSucceeded = true

        // Cleanup: cancella i kind fisici che nessuna regola armata richiede più.
        (registeredKinds - desiredKinds).forEach { kind ->
            if (backend.cancel(kind)) registeredKinds.remove(kind) else cleanupSucceeded = false
        }

        // Registrazione condivisa: un solo requestTriggerSensor per kind desiderato.
        desiredKinds.filter { it !in registeredKinds }.forEach { kind ->
            when (backend.register(kind)) {
                SensorRegistrationOutcome.Registered,
                SensorRegistrationOutcome.AlreadyRegistered,
                -> registeredKinds.add(kind)
                SensorRegistrationOutcome.Unavailable ->
                    needsReview += eligible.filter { it.second == kind }.map { it.first }
                is SensorRegistrationOutcome.Failure ->
                    failed += eligible.filter { it.second == kind }.map { it.first }
            }
        }

        // Il FGS deve vivere finché almeno un sensore è registrato. Idempotente lato sentinel:
        // start/stop scattano solo sulla transizione vuoto↔non-vuoto del demand condiviso.
        if (registeredKinds.isEmpty()) foregroundDemand.stop() else foregroundDemand.start()

        val requiredBy = eligible
            .filter { it.second in registeredKinds }
            .map { it.first }
            .sortedBy { it.value }

        SensorReconcileReport(
            requiredBy = requiredBy,
            registeredKinds = registeredKinds.toSet(),
            needsReview = needsReview.sortedBy { it.value },
            failed = failed.sortedBy { it.value },
            cleanupSucceeded = cleanupSucceeded,
        )
    }
}
