package dev.argus.automation.sensor

import android.content.Context
import android.util.Base64
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SensorKind

/**
 * Detection pending per-regola su `SharedPreferences`, `commit()` sincrono perché il callback può
 * arrivare subito prima di un process death. È l'UNICO stato sensori persistito: la registrazione
 * fisica resta process-local (vedi [SensorTriggerCoordinator]).
 *
 * Corruption-safe: un record illeggibile (fingerprint/kind/sequence malformati) fa fallire chiuso
 * la `pending` — nessuna redelivery su dati corrotti, mai un crash o un restart loop.
 */
class PrefsSensorDetectionStore internal constructor(
    private val preferences: android.content.SharedPreferences,
) : SensorDetectionStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun knownIds(): Set<AutomationId> =
        preferences.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty().map { AutomationId(it) }.toSet()

    override fun pending(id: AutomationId, fingerprint: ApprovalFingerprint): SensorDetection? {
        val keys = keys(id)
        if (preferences.getBoolean(keys.pending, false).not()) return null
        val storedFingerprint = preferences.getString(keys.fingerprint, null) ?: return null
        if (storedFingerprint != fingerprint.value) return null
        val kind = preferences.getString(keys.kind, null)?.let(::kindOrNull) ?: return null
        val sequence = preferences.getLong(keys.sequence, -1L)
        if (sequence < 0) return null
        return SensorDetection(kind, sequence)
    }

    override fun beginDetection(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
        kind: SensorKind,
    ): SensorDetection {
        val keys = keys(id)
        val next = preferences.getLong(keys.sequence, 0L) + 1
        val ids = preferences.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty().toMutableSet()
            .apply { add(id.value) }
        preferences.edit()
            .putStringSet(KEY_KNOWN_IDS, ids)
            .putString(keys.fingerprint, fingerprint.value)
            .putString(keys.kind, kind.wireName)
            .putLong(keys.sequence, next)
            .putBoolean(keys.pending, true)
            .commitOrThrow()
        return SensorDetection(kind, next)
    }

    override fun completeDetection(id: AutomationId, fingerprint: ApprovalFingerprint, sequence: Long) {
        val keys = keys(id)
        if (preferences.getString(keys.fingerprint, null) != fingerprint.value) return
        if (preferences.getLong(keys.sequence, -1L) != sequence) return
        preferences.edit().putBoolean(keys.pending, false).commitOrThrow()
    }

    override fun forget(id: AutomationId) {
        val keys = keys(id)
        val ids = preferences.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty().toMutableSet()
            .apply { remove(id.value) }
        preferences.edit()
            .putStringSet(KEY_KNOWN_IDS, ids)
            .remove(keys.fingerprint)
            .remove(keys.kind)
            .remove(keys.sequence)
            .remove(keys.pending)
            .commitOrThrow()
    }

    private fun kindOrNull(wireName: String): SensorKind? =
        SensorKind.entries.firstOrNull { it.wireName == wireName }

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "sensor_detection_commit_failed" }
    }

    private data class RecordKeys(
        val fingerprint: String,
        val kind: String,
        val sequence: String,
        val pending: String,
    )

    private fun keys(id: AutomationId): RecordKeys {
        val base = "record." + Base64.encodeToString(id.value.toByteArray(), Base64.NO_WRAP)
        return RecordKeys("$base.fingerprint", "$base.kind", "$base.sequence", "$base.pending")
    }

    private companion object {
        const val PREFERENCES_NAME = "argus_sensor_detection_v1"
        const val KEY_KNOWN_IDS = "known_ids"
    }
}
