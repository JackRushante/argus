package dev.argus.automation.connectivity

import android.content.Context
import dev.argus.engine.model.ConnState

/** Stato minimale delle sorgenti, con chiavi già hashate dall'ingress e commit sincrono. */
class PrefsConnectivityStateStore(context: Context) : ConnectivityStateStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun last(sourceKey: String): ConnectivityStateSnapshot? {
        val state = prefs.getString(key(sourceKey, STATE_SUFFIX), null)
            ?.let { runCatching { ConnState.valueOf(it) }.getOrNull() }
            ?: return null
        return ConnectivityStateSnapshot(
            state = state,
            name = prefs.getString(key(sourceKey, NAME_SUFFIX), null),
            transitionAtMillis = prefs.getLong(key(sourceKey, AT_SUFFIX), 0L),
        )
    }

    override fun record(sourceKey: String, snapshot: ConnectivityStateSnapshot) {
        val persisted = prefs.edit()
            .putString(key(sourceKey, STATE_SUFFIX), snapshot.state.name)
            .putLong(key(sourceKey, AT_SUFFIX), snapshot.transitionAtMillis)
            .apply {
                val nameKey = key(sourceKey, NAME_SUFFIX)
                if (snapshot.name == null) remove(nameKey) else putString(nameKey, snapshot.name)
            }
            .commit()
        check(persisted) { "connectivity_state_persist_failed" }
    }

    private fun key(sourceKey: String, suffix: String) = "$sourceKey.$suffix"

    private companion object {
        const val PREFS_NAME = "argus_connectivity_state"
        const val STATE_SUFFIX = "state"
        const val NAME_SUFFIX = "name"
        const val AT_SUFFIX = "at"
    }
}
