package dev.argus.automation.geofence

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Registry piccolo e sincrono: ogni mutazione usa commit(), perché il receiver può terminare il
 * processo subito dopo il dispatch. L'indice degli id resta leggibile anche se un record è
 * corrotto, così il coordinator può comunque rimuovere il PendingIntent corrispondente.
 */
class PrefsGeofenceStateStore internal constructor(
    private val preferences: SharedPreferences,
) : GeofenceStateStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val lock = Any()

    override fun knownIds(): Set<AutomationId> = synchronized(lock) {
        preferences.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty()
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= MAX_AUTOMATION_ID_LENGTH }
            .map(::AutomationId)
            .toSet()
    }

    override fun get(automationId: AutomationId): PersistedGeofenceRegistration? =
        synchronized(lock) { readLocked(automationId) }

    override fun prepare(registration: GeofenceRegistration) = synchronized(lock) {
        val previous = readLocked(registration.automationId)
            ?.takeIf { it.approvalFingerprint == registration.approvalFingerprint }
        val keys = keys(registration.automationId)
        val ids = preferences.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty().toMutableSet()
            .apply { add(registration.automationId.value) }
        preferences.edit()
            .putStringSet(KEY_KNOWN_IDS, ids)
            .putString(keys.fingerprint, registration.approvalFingerprint.value)
            .putBoolean(keys.active, false)
            .putLong(keys.sequence, previous?.sequence ?: 0)
            .apply {
                val transition = previous?.lastTransition
                if (transition == null) remove(keys.transition)
                else putString(keys.transition, transition.name)
                val pendingTransition = previous?.pendingTransition
                val pendingSequence = previous?.pendingSequence
                if (pendingTransition == null || pendingSequence == null) {
                    remove(keys.pendingTransition)
                    remove(keys.pendingSequence)
                } else {
                    putString(keys.pendingTransition, pendingTransition.name)
                    putLong(keys.pendingSequence, pendingSequence)
                }
            }
            .commitOrThrow()
    }

    override fun activate(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
    ) = synchronized(lock) {
        val current = requireNotNull(readLocked(automationId)) { "registration_missing" }
        require(current.approvalFingerprint == approvalFingerprint) { "registration_stale" }
        preferences.edit().putBoolean(keys(automationId).active, true).commitOrThrow()
    }

    override fun pending(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
    ): PendingGeofenceTransition? = synchronized(lock) {
        readLocked(automationId)
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
        val current = readLocked(automationId)
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
        val keys = keys(automationId)
        preferences.edit()
            .putString(keys.pendingTransition, transition.name)
            .putLong(keys.pendingSequence, next)
            .commitOrThrow()
        PendingGeofenceTransition(transition, next)
    }

    override fun completeTransition(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        sequence: Long,
    ) = synchronized(lock) {
        val current = requireNotNull(readLocked(automationId)) { "registration_missing" }
        require(current.approvalFingerprint == approvalFingerprint) { "registration_stale" }
        require(current.pendingSequence == sequence && current.pendingTransition != null) {
            "pending_transition_stale"
        }
        val keys = keys(automationId)
        preferences.edit()
            .putString(keys.transition, current.pendingTransition.name)
            .putLong(keys.sequence, sequence)
            .remove(keys.pendingTransition)
            .remove(keys.pendingSequence)
            .commitOrThrow()
    }

    override fun delete(automationId: AutomationId) = synchronized(lock) {
        val keys = keys(automationId)
        val ids = preferences.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty().toMutableSet()
            .apply { remove(automationId.value) }
        preferences.edit()
            .putStringSet(KEY_KNOWN_IDS, ids)
            .remove(keys.fingerprint)
            .remove(keys.active)
            .remove(keys.transition)
            .remove(keys.sequence)
            .remove(keys.pendingTransition)
            .remove(keys.pendingSequence)
            .commitOrThrow()
    }

    private fun readLocked(automationId: AutomationId): PersistedGeofenceRegistration? =
        runCatching {
            if (automationId !in knownIds()) return null
            val keys = keys(automationId)
            val fingerprint = ApprovalFingerprint(
                requireNotNull(preferences.getString(keys.fingerprint, null)),
            )
            val transition = preferences.getString(keys.transition, null)?.let(Transition::valueOf)
            require(transition == null || transition in setOf(Transition.ENTER, Transition.EXIT))
            val sequence = preferences.getLong(keys.sequence, 0)
            require(sequence >= 0)
            val pendingTransition = preferences.getString(keys.pendingTransition, null)
                ?.let(Transition::valueOf)
            require(
                pendingTransition == null ||
                    pendingTransition in setOf(Transition.ENTER, Transition.EXIT),
            )
            val pendingSequence = if (preferences.contains(keys.pendingSequence)) {
                preferences.getLong(keys.pendingSequence, -1)
            } else {
                null
            }
            PersistedGeofenceRegistration(
                automationId = automationId,
                approvalFingerprint = fingerprint,
                active = preferences.getBoolean(keys.active, false),
                lastTransition = transition,
                sequence = sequence,
                pendingTransition = pendingTransition,
                pendingSequence = pendingSequence,
            )
        }.getOrNull()

    private fun keys(automationId: AutomationId): RecordKeys {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            automationId.value.toByteArray(StandardCharsets.UTF_8),
        )
        val prefix = "record.$encoded"
        return RecordKeys(
            fingerprint = "$prefix.fingerprint",
            active = "$prefix.active",
            transition = "$prefix.transition",
            sequence = "$prefix.sequence",
            pendingTransition = "$prefix.pending_transition",
            pendingSequence = "$prefix.pending_sequence",
        )
    }

    private fun SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "geofence_state_commit_failed" }
    }

    private data class RecordKeys(
        val fingerprint: String,
        val active: String,
        val transition: String,
        val sequence: String,
        val pendingTransition: String,
        val pendingSequence: String,
    )

    companion object {
        internal const val PREFERENCES_NAME = "argus_geofence_state_v1"
        internal const val KEY_KNOWN_IDS = "known_ids"
        private const val MAX_AUTOMATION_ID_LENGTH = 256
    }
}
