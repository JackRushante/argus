package dev.argus.automation.span

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SpanRecord
import dev.argus.engine.runtime.ActiveSpan
import dev.argus.engine.runtime.SpanStore
import kotlinx.serialization.Serializable

/**
 * [SpanStore] su SharedPreferences (nessuna tabella Room), come gli altri `Prefs*StateStore`: per regola
 * l'intervallo in corso, legato all'impronta approvata che l'ha aperto, e l'istante dell'ultima esecuzione
 * delle azioni di fine. Ogni scrittura è un `commit()`: l'intervallo sopravvive alla morte del processo e al
 * riavvio. Un record che non si legge più (formato rotto) si scarta: non si rimette mai un valore incerto.
 */
class PrefsSpanStore internal constructor(private val prefs: SharedPreferences) : SpanStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val lock = Any()

    @Serializable
    private data class Stored(
        val fingerprint: String,
        val startedAtMillis: Long,
        val records: List<SpanRecord> = emptyList(),
        val handled: List<String> = emptyList(),
    )

    override suspend fun get(id: AutomationId): ActiveSpan? = synchronized(lock) { read(id) }

    override suspend fun all(): List<ActiveSpan> = synchronized(lock) {
        prefs.all.keys.filter { it.startsWith(SPAN_PREFIX) }.sorted()
            .mapNotNull { read(AutomationId(it.removePrefix(SPAN_PREFIX))) }
    }

    override suspend fun open(id: AutomationId, fingerprint: ApprovalFingerprint, atMillis: Long): ActiveSpan =
        synchronized(lock) {
            read(id)?.let { return@synchronized it }
            val span = ActiveSpan(id, fingerprint, atMillis)
            write(span)
            span
        }

    override suspend fun record(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
        key: String,
        record: SpanRecord?,
    ): Boolean = synchronized(lock) {
        val current = read(id)?.takeIf { it.approvalFingerprint == fingerprint } ?: return@synchronized false
        if (key in current.handled) return@synchronized false
        val updated = current.copy(
            records = if (record == null) current.records else current.records + record,
            handled = current.handled + key,
        )
        write(updated) && record != null
    }

    override suspend fun close(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean = synchronized(lock) {
        if (read(id)?.approvalFingerprint != fingerprint) return@synchronized false
        prefs.edit().remove(spanKey(id)).commit()
    }

    override suspend fun claimExitCooldown(id: AutomationId, atMillis: Long, cooldownMs: Long): Long? =
        synchronized(lock) {
            val key = EXIT_PREFIX + id.value
            val last = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
            if (cooldownMs > 0 && last != null && last <= atMillis && atMillis - last < cooldownMs) {
                return@synchronized if (last > Long.MAX_VALUE - cooldownMs) Long.MAX_VALUE else last + cooldownMs
            }
            prefs.edit().putLong(key, atMillis).commit()
            null
        }

    private fun read(id: AutomationId): ActiveSpan? {
        val raw = prefs.getString(spanKey(id), null) ?: return null
        val stored = runCatching { ArgusJson.decodeFromString(Stored.serializer(), raw) }.getOrNull()
        val fingerprint = stored?.let { runCatching { ApprovalFingerprint(it.fingerprint) }.getOrNull() }
        if (stored == null || fingerprint == null) {
            prefs.edit().remove(spanKey(id)).commit()
            return null
        }
        return ActiveSpan(id, fingerprint, stored.startedAtMillis, stored.records, stored.handled.toSet())
    }

    private fun write(span: ActiveSpan): Boolean {
        val stored = Stored(
            fingerprint = span.approvalFingerprint.value,
            startedAtMillis = span.startedAtMillis,
            records = span.records,
            handled = span.handled.sorted(),
        )
        return prefs.edit().putString(spanKey(span.automationId), ArgusJson.encodeToString(Stored.serializer(), stored)).commit()
    }

    private fun spanKey(id: AutomationId) = SPAN_PREFIX + id.value

    companion object {
        internal const val PREFERENCES_NAME = "argus_spans"
        private const val SPAN_PREFIX = "span."
        private const val EXIT_PREFIX = "exit_at."
    }
}
