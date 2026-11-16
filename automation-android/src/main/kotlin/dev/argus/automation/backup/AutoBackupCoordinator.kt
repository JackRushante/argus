package dev.argus.automation.backup

import android.util.Log
import dev.argus.data.backup.AutoBackupFrequency
import dev.argus.data.backup.AutoBackupOutcome
import dev.argus.data.backup.AutoBackupRunner
import dev.argus.data.backup.AutoBackupSchedule
import dev.argus.data.backup.AutoBackupState
import dev.argus.data.backup.BackupFolder
import dev.argus.data.backup.BackupKey
import dev.argus.data.backup.BackupKeyDerivation
import dev.argus.data.backup.EncryptedBackupFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.time.ZoneId

/** Quel che la card di Sistema mostra: stato salvato, chiave presente, lavori in corso. Nessun segreto. */
data class AutoBackupSnapshot(
    val state: AutoBackupState,
    val running: Boolean = false,
    val settingPassphrase: Boolean = false,
)

/**
 * Backup automatico delle regole (0.7.2): impostazioni, esecuzioni (sveglia, avvio, app aperta, «Esegui
 * ora»), calendario e notifica dopo tre fallimenti di fila. Un'esecuzione alla volta. Gira senza
 * l'utente: la passphrase serve solo quando la si imposta; poi basta la chiave avvolta dal Keystore.
 */
class AutoBackupCoordinator(
    private val scope: CoroutineScope,
    private val store: AutoBackupStateStore,
    private val keys: AutoBackupKeys,
    private val folder: BackupFolder,
    private val runner: AutoBackupRunner,
    private val alarm: AutoBackupAlarmScheduler,
    private val notice: AutoBackupFailureNotice,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newSalt: () -> ByteArray = {
        ByteArray(EncryptedBackupFormat.SALT_BYTES).also(SecureRandom()::nextBytes)
    },
    /** Lenta (600 000 iterazioni): fuori dal thread principale. */
    private val deriveKey: suspend (CharArray, ByteArray) -> ByteArray = { passphrase, salt ->
        withContext(Dispatchers.Default) { BackupKeyDerivation.derive(passphrase, salt) }
    },
) {
    private val runMutex = Mutex()
    private val running = MutableStateFlow(false)
    private val settingPassphrase = MutableStateFlow(false)

    val snapshots: Flow<AutoBackupSnapshot> = combine(
        store.state,
        keys.configured,
        running,
        settingPassphrase,
    ) { state, keyConfigured, isRunning, isSetting ->
        AutoBackupSnapshot(state.copy(keyConfigured = keyConfigured), isRunning, isSetting)
    }

    private fun current(): AutoBackupState = store.state.value.copy(keyConfigured = keys.configured.value)

    /** Avvio del processo (anche dopo un riavvio del telefono): rimette la sveglia e recupera un ritardo. */
    fun start(): Job = scope.launch { guarded("avvio") { runIfDue() } }

    /** App in primo piano: un backup in ritardo (o un nuovo tentativo dovuto) parte ora. */
    fun onForeground(): Job = scope.launch { guarded("primo piano") { runIfDue() } }

    /** Sveglia: [onFinished] chiude il broadcast; oltre la finestra il lavoro prosegue da solo. */
    fun onAlarm(onFinished: () -> Unit): Job = scope.launch {
        try {
            val work = launch { guarded("sveglia") { runIfDue() } }
            withTimeoutOrNull(RECEIVER_WINDOW_MILLIS) { work.join() }
        } finally {
            onFinished()
        }
    }

    /** «Esegui backup ora»: parte anche se non è dovuto (non serve che l'interruttore sia acceso). */
    fun requestRunNow(): Job = scope.launch { guarded("esegui ora") { runNow() } }

    /** Esegue il backup se il calendario lo dice; comunque rimette la sveglia. null = non dovuto. */
    suspend fun runIfDue(): AutoBackupOutcome? = runMutex.withLock {
        if (!AutoBackupSchedule.isDue(current(), nowMillis(), zone())) {
            reschedule()
            return@withLock null
        }
        runLocked()
    }

    suspend fun runNow(): AutoBackupOutcome = runMutex.withLock { runLocked() }

    private suspend fun runLocked(): AutoBackupOutcome {
        running.value = true
        try {
            val state = current()
            val now = nowMillis()
            val key = loadKey()
            val result = try {
                runner.run(state, key, now, zone())
            } finally {
                key?.wipe()
            }
            val recorded = AutoBackupSchedule.record(state.copy(writtenNames = result.writtenNames), result.outcome, now)
            store.update { latest ->
                latest.copy(
                    lastCompletedAtMillis = recorded.state.lastCompletedAtMillis,
                    lastAttemptAtMillis = recorded.state.lastAttemptAtMillis,
                    lastRun = recorded.state.lastRun,
                    consecutiveFailures = recorded.state.consecutiveFailures,
                    failureNotified = recorded.state.failureNotified,
                    // Cartella cambiata durante l'esecuzione: l'elenco di prima non vale per la nuova.
                    writtenNames = if (latest.folderUri == state.folderUri) recorded.state.writtenNames else latest.writtenNames,
                )
            }
            if (recorded.notifyFailure) notice.show()
            if (recorded.clearFailureNotice) notice.clear()
            reschedule()
            return result.outcome
        } finally {
            running.value = false
        }
    }

    private suspend fun loadKey(): BackupKey? = try {
        keys.load()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** Accende o spegne. Acceso e pronto, il primo backup parte subito: l'utente vede che funziona. */
    suspend fun setEnabled(enabled: Boolean): Boolean {
        val saved = store.update { it.copy(enabled = enabled) }
        if (saved && enabled && current().configured) requestRunNow() else rescheduleLocked()
        return saved
    }

    suspend fun setFrequency(frequency: AutoBackupFrequency): Boolean =
        store.update { it.copy(frequency = frequency) }.also { rescheduleLocked() }

    /**
     * Nuova cartella (URI dell'albero SAF). Prende il permesso persistente e lascia quello della
     * cartella precedente. I file già scritti nella vecchia restano dove sono. false se Argus non può usarla.
     */
    suspend fun setFolder(folderUri: String): Boolean {
        val label = try {
            folder.adopt(folderUri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        val previous = store.state.value.folderUri
        val saved = store.update { state ->
            state.copy(
                folderUri = folderUri,
                folderLabel = label,
                writtenNames = if (state.folderUri == folderUri) state.writtenNames else emptySet(),
                consecutiveFailures = 0,
                failureNotified = false,
            )
        }
        if (!saved) return false
        if (previous != null && previous != folderUri) folder.release(previous)
        notice.clear()
        rescheduleLocked()
        return true
    }

    /**
     * Imposta o cambia la passphrase: nuovo salt, chiave derivata salvata avvolta dal Keystore. La
     * passphrase non si salva; il chiamante azzera [passphrase] dopo. I file vecchi restano leggibili con
     * la passphrase di allora (il salt viaggia nel file).
     */
    suspend fun setPassphrase(passphrase: CharArray): Boolean {
        if (!BackupKeyDerivation.isAcceptable(passphrase)) return false
        settingPassphrase.value = true
        try {
            val salt = newSalt()
            val derived = deriveKey(passphrase, salt)
            val saved = try {
                val key = BackupKey(salt, derived)
                try {
                    keys.save(key)
                } finally {
                    key.wipe()
                }
            } finally {
                derived.fill(0)
            }
            if (!saved) return false
            // Una chiave nuova chiude la serie di fallimenti (anche quella per chiave illeggibile).
            store.update { it.copy(consecutiveFailures = 0, failureNotified = false) }
            notice.clear()
            rescheduleLocked()
            return true
        } finally {
            settingPassphrase.value = false
        }
    }

    private suspend fun rescheduleLocked() = runMutex.withLock { reschedule() }

    private fun reschedule() {
        val now = nowMillis()
        val next = AutoBackupSchedule.nextRunAt(current(), now, zone())
        try {
            if (next == null) alarm.cancel() else alarm.schedule(maxOf(next, now + MIN_ALARM_DELAY_MILLIS))
        } catch (error: RuntimeException) {
            Log.w(TAG, "sveglia del backup non programmata: ${error::class.java.simpleName}")
        }
    }

    private suspend fun guarded(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "backup automatico ($label) fallito: ${error::class.java.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ArgusAutoBackup"

        /** Sotto il limite di un broadcast in background. */
        const val RECEIVER_WINDOW_MILLIS = 50_000L

        /** Un backup in ritardo è già partito da [runIfDue]: la sveglia successiva non va nel passato. */
        const val MIN_ALARM_DELAY_MILLIS = 60_000L
    }
}
