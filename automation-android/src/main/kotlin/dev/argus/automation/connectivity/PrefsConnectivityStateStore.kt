package dev.argus.automation.connectivity

import android.content.Context
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId

/** Stato minimale delle sorgenti, con chiavi già hashate dall'ingress e commit sincrono. */
class PrefsConnectivityStateStore(context: Context) : ConnectivityStateStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun last(sourceKey: String): ConnectivityStateSnapshot? = runCatching {
        require(sourceKey.matches(SOURCE_KEY_PATTERN))
        val state = ConnState.valueOf(
            requireNotNull(prefs.getString(key(sourceKey, STATE_SUFFIX), null)),
        )
        val name = readBoundedOptional(key(sourceKey, NAME_SUFFIX), MAX_NAME_CHARS)
        val atMillis = prefs.getLong(key(sourceKey, AT_SUFFIX), 0L)
        require(atMillis >= 0)
        ConnectivityStateSnapshot(state, name, atMillis)
    }.getOrNull()

    override fun pending(sourceKey: String): TriggerEnvelope? = readPending(sourceKey)

    override fun pending(): List<Pair<String, TriggerEnvelope>> = prefs.all.keys.asSequence()
        .filter { it.endsWith(PENDING_ID_SUFFIX) }
        .map { it.removeSuffix(".$PENDING_ID_SUFFIX") }
        .filter { it.matches(SOURCE_KEY_PATTERN) }
        .distinct()
        .mapNotNull { sourceKey -> readPending(sourceKey)?.let { sourceKey to it } }
        .toList()

    override fun record(
        sourceKey: String,
        snapshot: ConnectivityStateSnapshot,
        pending: TriggerEnvelope?,
    ) {
        require(sourceKey.matches(SOURCE_KEY_PATTERN)) { "connectivity_source_key_invalid" }
        require(snapshot.transitionAtMillis >= 0) { "connectivity_transition_time_invalid" }
        require(snapshot.name.isBoundedOptional(MAX_NAME_CHARS)) {
            "connectivity_name_invalid"
        }
        val pendingEvent = pending?.let {
            require(it.id.value.matches(EVENT_ID_PATTERN)) { "connectivity_pending_id_invalid" }
            requireNotNull(it.event as? TriggerEvent.ConnectivityChanged) {
                "connectivity_pending_event_invalid"
            }.also { event ->
                require(event.state == snapshot.state && event.name == snapshot.name) {
                    "connectivity_pending_snapshot_mismatch"
                }
                require(event.name.isBoundedOptional(MAX_NAME_CHARS)) {
                    "connectivity_pending_name_invalid"
                }
            }
        }
        val persisted = prefs.edit()
            .putString(key(sourceKey, STATE_SUFFIX), snapshot.state.name)
            .putLong(key(sourceKey, AT_SUFFIX), snapshot.transitionAtMillis)
            .apply {
                val nameKey = key(sourceKey, NAME_SUFFIX)
                if (snapshot.name == null) remove(nameKey) else putString(nameKey, snapshot.name)
                if (pending == null || pendingEvent == null) {
                    removePending(sourceKey)
                } else {
                    putString(key(sourceKey, PENDING_ID_SUFFIX), pending.id.value)
                    putString(key(sourceKey, PENDING_MEDIUM_SUFFIX), pendingEvent.medium.name)
                    putString(key(sourceKey, PENDING_STATE_SUFFIX), pendingEvent.state.name)
                    val pendingNameKey = key(sourceKey, PENDING_NAME_SUFFIX)
                    if (pendingEvent.name == null) remove(pendingNameKey)
                    else putString(pendingNameKey, pendingEvent.name)
                }
            }
            .commit()
        check(persisted) { "connectivity_state_persist_failed" }
    }

    override fun complete(sourceKey: String, eventId: String) {
        require(prefs.getString(key(sourceKey, PENDING_ID_SUFFIX), null) == eventId) {
            "connectivity_pending_stale"
        }
        check(prefs.edit().apply { removePending(sourceKey) }.commit()) {
            "connectivity_state_persist_failed"
        }
    }

    private fun readPending(sourceKey: String): TriggerEnvelope? = runCatching {
        require(sourceKey.matches(SOURCE_KEY_PATTERN))
        val id = requireNotNull(prefs.getString(key(sourceKey, PENDING_ID_SUFFIX), null))
        require(id.matches(EVENT_ID_PATTERN))
        val medium = ConnMedium.valueOf(
            requireNotNull(prefs.getString(key(sourceKey, PENDING_MEDIUM_SUFFIX), null)),
        )
        val state = ConnState.valueOf(
            requireNotNull(prefs.getString(key(sourceKey, PENDING_STATE_SUFFIX), null)),
        )
        val name = readBoundedOptional(key(sourceKey, PENDING_NAME_SUFFIX), MAX_NAME_CHARS)
        TriggerEnvelope(
            TriggerEventId(id),
            TriggerEvent.ConnectivityChanged(medium, state, name),
        )
    }.getOrNull()

    private fun android.content.SharedPreferences.Editor.removePending(sourceKey: String) {
        remove(key(sourceKey, PENDING_ID_SUFFIX))
        remove(key(sourceKey, PENDING_MEDIUM_SUFFIX))
        remove(key(sourceKey, PENDING_STATE_SUFFIX))
        remove(key(sourceKey, PENDING_NAME_SUFFIX))
    }

    private fun readBoundedOptional(storageKey: String, maxChars: Int): String? {
        val value = prefs.getString(storageKey, null) ?: return null
        require(value.isBoundedOptional(maxChars))
        return value
    }

    private fun String?.isBoundedOptional(maxChars: Int): Boolean =
        this == null || (length <= maxChars && none(Char::isISOControl))

    private fun key(sourceKey: String, suffix: String) = "$sourceKey.$suffix"

    companion object {
        internal const val PREFS_NAME = "argus_connectivity_state"
        private const val STATE_SUFFIX = "state"
        private const val NAME_SUFFIX = "name"
        private const val AT_SUFFIX = "at"
        private const val PENDING_ID_SUFFIX = "pending_id"
        private const val PENDING_MEDIUM_SUFFIX = "pending_medium"
        private const val PENDING_STATE_SUFFIX = "pending_state"
        private const val PENDING_NAME_SUFFIX = "pending_name"
        private val SOURCE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
        private val EVENT_ID_PATTERN = Regex("^connectivity:[0-9a-f]{64}$")
        private const val MAX_NAME_CHARS = 120
    }
}
