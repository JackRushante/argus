package dev.argus.automation.geofence

import dev.argus.automation.CurrentLocationProvider
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GeofenceRegistration(
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Float,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude_invalid" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude_invalid" }
        require(!(latitude == 0.0 && longitude == 0.0)) { "coordinates_unresolved" }
        require(radiusM.isFinite() && radiusM > 0f) { "radius_invalid" }
    }

    companion object {
        fun from(automation: Automation): GeofenceRegistration {
            val trigger = automation.trigger as? Trigger.Geofence
                ?: throw IllegalArgumentException("trigger_not_geofence")
            require(trigger.transition in setOf(Transition.ENTER, Transition.EXIT)) {
                "transition_unsupported"
            }
            require(!trigger.resolveCurrentLocation) { "coordinates_unresolved" }
            return GeofenceRegistration(
                automationId = automation.id,
                approvalFingerprint = requireNotNull(automation.approvalFingerprint) {
                    "approval_fingerprint_missing"
                },
                latitude = trigger.lat,
                longitude = trigger.lng,
                radiusM = trigger.radiusM.toFloat(),
            )
        }
    }
}

data class PersistedGeofenceRegistration(
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    val active: Boolean,
    val lastTransition: Transition? = null,
    val sequence: Long = 0,
    val pendingTransition: Transition? = null,
    val pendingSequence: Long? = null,
) {
    init {
        require((pendingTransition == null) == (pendingSequence == null)) {
            "pending_transition_incomplete"
        }
        require(sequence >= 0 && (pendingSequence == null || pendingSequence > sequence)) {
            "transition_sequence_invalid"
        }
    }
}

data class PendingGeofenceTransition(
    val transition: Transition,
    val sequence: Long,
)

/**
 * Lo stato viene preparato PRIMA della chiamata al framework. Così un crash non può lasciare
 * una registrazione OS priva dell'identità necessaria a rimuoverla al bootstrap successivo.
 */
interface GeofenceStateStore {
    fun knownIds(): Set<AutomationId>
    fun get(automationId: AutomationId): PersistedGeofenceRegistration?
    fun prepare(registration: GeofenceRegistration)
    fun activate(automationId: AutomationId, approvalFingerprint: ApprovalFingerprint)
    fun pending(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
    ): PendingGeofenceTransition?
    fun beginTransition(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        transition: Transition,
    ): PendingGeofenceTransition?
    fun completeTransition(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        sequence: Long,
    )
    fun delete(automationId: AutomationId)
}

/** Implementazione deterministica usata nei test e negli spike senza storage Android. */
class MemoryGeofenceStateStore : GeofenceStateStore {
    private val lock = Any()
    private val values = linkedMapOf<AutomationId, PersistedGeofenceRegistration>()

    override fun knownIds(): Set<AutomationId> = synchronized(lock) { values.keys.toSet() }

    override fun get(automationId: AutomationId): PersistedGeofenceRegistration? =
        synchronized(lock) { values[automationId] }

    override fun prepare(registration: GeofenceRegistration) = synchronized(lock) {
        val previous = values[registration.automationId]
            ?.takeIf { it.approvalFingerprint == registration.approvalFingerprint }
        values[registration.automationId] = PersistedGeofenceRegistration(
            automationId = registration.automationId,
            approvalFingerprint = registration.approvalFingerprint,
            active = false,
            lastTransition = previous?.lastTransition,
            sequence = previous?.sequence ?: 0,
            pendingTransition = previous?.pendingTransition,
            pendingSequence = previous?.pendingSequence,
        )
    }

    override fun activate(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
    ) = synchronized(lock) {
        val current = requireNotNull(values[automationId]) { "registration_missing" }
        require(current.approvalFingerprint == approvalFingerprint) { "registration_stale" }
        values[automationId] = current.copy(active = true)
    }

    override fun pending(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
    ): PendingGeofenceTransition? = synchronized(lock) {
        values[automationId]
            ?.takeIf { it.approvalFingerprint == approvalFingerprint }
            ?.let { current ->
                current.pendingTransition?.let { transition ->
                    PendingGeofenceTransition(transition, requireNotNull(current.pendingSequence))
                }
            }
    }

    override fun beginTransition(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        transition: Transition,
    ): PendingGeofenceTransition? = synchronized(lock) {
        require(transition in setOf(Transition.ENTER, Transition.EXIT)) { "transition_unsupported" }
        val current = values[automationId]
            ?.takeIf { it.approvalFingerprint == approvalFingerprint }
            ?: return@synchronized null
        current.pendingTransition?.let {
            return@synchronized PendingGeofenceTransition(
                it,
                requireNotNull(current.pendingSequence),
            )
        }
        if (current.lastTransition == transition) return@synchronized null
        val next = Math.addExact(current.sequence, 1)
        values[automationId] = current.copy(
            pendingTransition = transition,
            pendingSequence = next,
        )
        PendingGeofenceTransition(transition, next)
    }

    override fun completeTransition(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        sequence: Long,
    ) = synchronized(lock) {
        val current = requireNotNull(values[automationId]) { "registration_missing" }
        require(current.approvalFingerprint == approvalFingerprint) { "registration_stale" }
        require(current.pendingSequence == sequence && current.pendingTransition != null) {
            "pending_transition_stale"
        }
        values[automationId] = current.copy(
            lastTransition = current.pendingTransition,
            sequence = sequence,
            pendingTransition = null,
            pendingSequence = null,
        )
    }

    override fun delete(automationId: AutomationId) {
        synchronized(lock) { values.remove(automationId) }
    }
}

interface GeofenceBackend {
    fun register(registration: GeofenceRegistration)
    fun unregister(automationId: AutomationId)
}

data class GeofenceReconcileReport(
    val requiredBy: List<AutomationId> = emptyList(),
    val registered: List<AutomationId> = emptyList(),
    val unregistered: List<AutomationId> = emptyList(),
    val failed: List<AutomationId> = emptyList(),
    val cleanupFailed: List<AutomationId> = emptyList(),
    val recovered: List<AutomationId> = emptyList(),
) {
    val cleanupSucceeded: Boolean get() = cleanupFailed.isEmpty()
}

fun interface GeofenceTriggerRuntime {
    suspend fun reconcile(recreateOsRegistrations: Boolean): GeofenceReconcileReport
}

object NoopGeofenceTriggerRuntime : GeofenceTriggerRuntime {
    override suspend fun reconcile(recreateOsRegistrations: Boolean) = GeofenceReconcileReport()
}

/** Single-writer che mantiene Room, stato locale e LocationManager coerenti. */
class GeofenceCoordinator(
    private val store: AutomationStore,
    private val state: GeofenceStateStore,
    private val backend: GeofenceBackend,
    private val currentLocation: CurrentLocationProvider? = null,
    private val ingress: GeofenceEventIngress? = null,
) : GeofenceTriggerRuntime {
    private val mutex = Mutex()

    suspend fun reconcile(): GeofenceReconcileReport = reconcile(false)

    override suspend fun reconcile(
        recreateOsRegistrations: Boolean,
    ): GeofenceReconcileReport = mutex.withLock {
        val desired = store.armed()
            .filter { it.trigger is Trigger.Geofence }
            .associateBy { it.id }
        val registered = mutableListOf<AutomationId>()
        val unregistered = mutableListOf<AutomationId>()
        val failed = mutableListOf<AutomationId>()
        val cleanupFailed = mutableListOf<AutomationId>()
        val recovered = mutableListOf<AutomationId>()

        state.knownIds().sortedBy { it.value }.forEach { id ->
            if (id !in desired) {
                try {
                    backend.unregister(id)
                    state.delete(id)
                    unregistered += id
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    cleanupFailed += id
                }
            }
        }

        desired.values.sortedBy { it.id.value }.forEach { automation ->
            val registration = try {
                GeofenceRegistration.from(automation)
            } catch (_: Exception) {
                failed += automation.id
                return@forEach
            }
            val knownBefore = automation.id in state.knownIds()
            val persisted = state.get(automation.id)
            if (!recreateOsRegistrations && persisted?.active == true &&
                persisted.approvalFingerprint == registration.approvalFingerprint
            ) return@forEach

            try {
                state.prepare(registration)
                if (knownBefore) {
                    backend.unregister(automation.id)
                }
                backend.register(registration)
                state.activate(automation.id, registration.approvalFingerprint)
                registered += automation.id
            } catch (error: CancellationException) {
                runCatching { backend.unregister(automation.id) }
                throw error
            } catch (_: Exception) {
                // Se register era riuscita ma il commit ACTIVE fallisce, non lasciare una fence
                // non tracciata. Lo stato PREPARED resta e rende il retry recuperabile.
                runCatching { backend.unregister(automation.id) }
                failed += automation.id
            }
        }

        if (recreateOsRegistrations && ingress != null) {
            recovered += ingress.recoverPending(desired.keys)
        }
        val recoveryCandidates = desired.values.filter { automation ->
            automation.id !in failed && state.get(automation.id)?.lastTransition != null
        }
        if (recreateOsRegistrations && recoveryCandidates.isNotEmpty() &&
            currentLocation != null && ingress != null
        ) {
            val location = try {
                currentLocation.current()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (location?.valid == true) {
                recoveryCandidates.sortedBy { it.id.value }.forEach { automation ->
                    val trigger = automation.trigger as Trigger.Geofence
                    val persisted = state.get(automation.id) ?: return@forEach
                    val distance = distanceMeters(
                        trigger.lat,
                        trigger.lng,
                        location.latitude,
                        location.longitude,
                    )
                    // Il fix one-shot può essere meno accurato del motore geofence. Non creare
                    // un bordo artificiale vicino alla circonferenza: lì aspettiamo il framework.
                    val detected = when (persisted.lastTransition) {
                        Transition.ENTER -> Transition.EXIT.takeIf {
                            distance > trigger.radiusM + RECOVERY_HYSTERESIS_METERS
                        }
                        Transition.EXIT -> Transition.ENTER.takeIf {
                            distance < (trigger.radiusM - RECOVERY_HYSTERESIS_METERS)
                                .coerceAtLeast(0.0)
                        }
                        else -> null
                    }
                    // UNKNOWN segue la semantica nativa: ENTER iniziale sì, EXIT iniziale no.
                    // Qui recuperiamo solo un bordo realmente perso rispetto allo stato noto.
                    if (detected != null && persisted.lastTransition != detected &&
                        ingress.onTransition(
                            automation.id,
                            persisted.approvalFingerprint,
                            detected,
                        )
                    ) recovered += automation.id
                }
            }
        }

        GeofenceReconcileReport(
            requiredBy = desired.keys.sortedBy { it.value },
            registered = registered,
            unregistered = unregistered,
            failed = failed.distinct(),
            cleanupFailed = cleanupFailed,
            recovered = recovered.distinct(),
        )
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2).let { it * it }
        val bounded = a.coerceIn(0.0, 1.0)
        return earthRadiusM * 2 * kotlin.math.atan2(
            kotlin.math.sqrt(bounded),
            kotlin.math.sqrt(1 - bounded),
        )
    }

    private companion object {
        const val RECOVERY_HYSTERESIS_METERS = 25.0
    }
}
