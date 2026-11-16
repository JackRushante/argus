package dev.argus.automation.backup

import android.content.Context
import android.content.SharedPreferences
import dev.argus.data.backup.AutoBackupFailure
import dev.argus.data.backup.AutoBackupFrequency
import dev.argus.data.backup.AutoBackupLastRun
import dev.argus.data.backup.AutoBackupState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Impostazioni ed esiti del backup automatico. Nessun segreto: la chiave sta in [AutoBackupKeys]. */
interface AutoBackupStateStore {
    /** Lo stato salvato; `keyConfigured` qui è sempre false (lo aggiunge chi combina con la chiave). */
    val state: StateFlow<AutoBackupState>

    /** Applica [transform] allo stato corrente e lo salva; false se la scrittura fallisce. */
    suspend fun update(transform: (AutoBackupState) -> AutoBackupState): Boolean
}

class SharedPreferencesAutoBackupStateStore internal constructor(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoBackupStateStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(read())
    override val state: StateFlow<AutoBackupState> = mutableState.asStateFlow()

    override suspend fun update(transform: (AutoBackupState) -> AutoBackupState): Boolean = mutex.withLock {
        val next = transform(mutableState.value).copy(keyConfigured = false)
        val saved = withContext(ioDispatcher) { write(next) }
        if (saved) mutableState.value = next
        saved
    }

    private fun read(): AutoBackupState {
        val frequency = preferences.getString(KEY_FREQUENCY, null)
            ?.let { name -> AutoBackupFrequency.entries.firstOrNull { it.name == name } }
            ?: AutoBackupFrequency.DAILY
        return AutoBackupState(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            frequency = frequency,
            folderUri = preferences.getString(KEY_FOLDER_URI, null),
            folderLabel = preferences.getString(KEY_FOLDER_LABEL, null),
            lastCompletedAtMillis = long(KEY_LAST_COMPLETED),
            lastAttemptAtMillis = long(KEY_LAST_ATTEMPT),
            lastRun = lastRun(),
            consecutiveFailures = preferences.getInt(KEY_FAILURES, 0).coerceAtLeast(0),
            failureNotified = preferences.getBoolean(KEY_NOTIFIED, false),
            writtenNames = preferences.getStringSet(KEY_WRITTEN, emptySet()).orEmpty().toSet(),
        )
    }

    private fun lastRun(): AutoBackupLastRun? {
        val at = long(KEY_RUN_AT) ?: return null
        return when (preferences.getString(KEY_RUN_KIND, null)) {
            RUN_WRITTEN -> AutoBackupLastRun.Written(at, preferences.getInt(KEY_RUN_RULES, 0))
            RUN_NOTHING -> AutoBackupLastRun.NothingToBackUp(at)
            RUN_FAILED -> preferences.getString(KEY_RUN_FAILURE, null)
                ?.let { name -> AutoBackupFailure.entries.firstOrNull { it.name == name } }
                ?.let { AutoBackupLastRun.Failed(at, it) }
            else -> null
        }
    }

    private fun long(key: String): Long? = if (preferences.contains(key)) preferences.getLong(key, 0L) else null

    private fun write(state: AutoBackupState): Boolean {
        val editor = preferences.edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putString(KEY_FREQUENCY, state.frequency.name)
            .putInt(KEY_FAILURES, state.consecutiveFailures)
            .putBoolean(KEY_NOTIFIED, state.failureNotified)
            .putStringSet(KEY_WRITTEN, state.writtenNames.toSet())
        editor.putOrRemove(KEY_FOLDER_URI, state.folderUri)
        editor.putOrRemove(KEY_FOLDER_LABEL, state.folderLabel)
        editor.putOrRemove(KEY_LAST_COMPLETED, state.lastCompletedAtMillis)
        editor.putOrRemove(KEY_LAST_ATTEMPT, state.lastAttemptAtMillis)
        when (val run = state.lastRun) {
            null -> editor.remove(KEY_RUN_AT).remove(KEY_RUN_KIND).remove(KEY_RUN_RULES).remove(KEY_RUN_FAILURE)
            is AutoBackupLastRun.Written -> editor.putLong(KEY_RUN_AT, run.atMillis).putString(KEY_RUN_KIND, RUN_WRITTEN)
                .putInt(KEY_RUN_RULES, run.rules).remove(KEY_RUN_FAILURE)
            is AutoBackupLastRun.NothingToBackUp -> editor.putLong(KEY_RUN_AT, run.atMillis)
                .putString(KEY_RUN_KIND, RUN_NOTHING).remove(KEY_RUN_RULES).remove(KEY_RUN_FAILURE)
            is AutoBackupLastRun.Failed -> editor.putLong(KEY_RUN_AT, run.atMillis).putString(KEY_RUN_KIND, RUN_FAILED)
                .putString(KEY_RUN_FAILURE, run.failure.name).remove(KEY_RUN_RULES)
        }
        return editor.commit()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private companion object {
        const val PREFERENCES_NAME = "argus_auto_backup"
        const val KEY_ENABLED = "enabled"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_FOLDER_LABEL = "folder_label"
        const val KEY_LAST_COMPLETED = "last_completed_at"
        const val KEY_LAST_ATTEMPT = "last_attempt_at"
        const val KEY_RUN_AT = "last_run_at"
        const val KEY_RUN_KIND = "last_run_kind"
        const val KEY_RUN_RULES = "last_run_rules"
        const val KEY_RUN_FAILURE = "last_run_failure"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_NOTIFIED = "failure_notified"
        const val KEY_WRITTEN = "written_names"
        const val RUN_WRITTEN = "written"
        const val RUN_NOTHING = "nothing"
        const val RUN_FAILED = "failed"
    }
}
