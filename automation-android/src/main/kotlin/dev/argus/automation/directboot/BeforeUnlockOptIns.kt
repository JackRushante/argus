package dev.argus.automation.directboot

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 0.8, «Attiva anche prima dello sblocco»: the rules the user opted in, each bound to the approved revision it was
 * opted in for. Organisation, not behaviour: it is not part of the rule nor of its fingerprint, and it lives in the
 * normal (credential-encrypted) preferences like the other `Prefs*` stores. A revision with another fingerprint is
 * NOT opted in: re-approving a rule switches the option off (the writer notes it in the Log).
 */
interface BeforeUnlockOptInStore {
    val optIns: StateFlow<Map<AutomationId, ApprovalFingerprint>>

    fun set(id: AutomationId, fingerprint: ApprovalFingerprint)

    fun remove(id: AutomationId)
}

/** Nothing opted in: the default of the view models in tests that do not exercise the option. */
object NoBeforeUnlockOptIns : BeforeUnlockOptInStore {
    override val optIns: StateFlow<Map<AutomationId, ApprovalFingerprint>> = MutableStateFlow(emptyMap())
    override fun set(id: AutomationId, fingerprint: ApprovalFingerprint) = Unit
    override fun remove(id: AutomationId) = Unit
}

class PrefsBeforeUnlockOptInStore internal constructor(
    private val preferences: SharedPreferences,
) : BeforeUnlockOptInStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val state = MutableStateFlow(readStored())

    override val optIns: StateFlow<Map<AutomationId, ApprovalFingerprint>> = state.asStateFlow()

    @Synchronized
    override fun set(id: AutomationId, fingerprint: ApprovalFingerprint) {
        if (state.value[id] == fingerprint) return
        preferences.edit().putString(PREFIX + id.value, fingerprint.value).apply()
        state.value = state.value + (id to fingerprint)
    }

    @Synchronized
    override fun remove(id: AutomationId) {
        if (id !in state.value) return
        preferences.edit().remove(PREFIX + id.value).apply()
        state.value = state.value - id
    }

    private fun readStored(): Map<AutomationId, ApprovalFingerprint> = runCatching {
        preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX) || value !is String) return@mapNotNull null
            val id = key.removePrefix(PREFIX).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fingerprint = runCatching { ApprovalFingerprint(value) }.getOrNull() ?: return@mapNotNull null
            AutomationId(id) to fingerprint
        }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        private const val PREFERENCES_NAME = "argus_before_unlock"
        private const val PREFIX = "rule."
    }
}
