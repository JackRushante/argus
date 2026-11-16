package dev.argus.automation.backup

import dev.argus.data.backup.AutoBackupRunner
import dev.argus.data.backup.AutoBackupState
import dev.argus.data.backup.BackupFolder
import dev.argus.data.backup.BackupFolderFile
import dev.argus.data.backup.BackupKey
import dev.argus.data.backup.BackupKeyDerivation
import dev.argus.data.backup.BuiltBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.time.ZoneId

/** Fake in memoria del backup automatico, condivisi dai test del coordinatore e del ViewModel. */
internal class MemoryStateStore : AutoBackupStateStore {
    private val mutable = MutableStateFlow(AutoBackupState())
    override val state: StateFlow<AutoBackupState> = mutable.asStateFlow()

    override suspend fun update(transform: (AutoBackupState) -> AutoBackupState): Boolean {
        mutable.value = transform(mutable.value).copy(keyConfigured = false)
        return true
    }
}

internal class MemoryKeys : AutoBackupKeys {
    private val mutable = MutableStateFlow(false)
    override val configured: StateFlow<Boolean> = mutable.asStateFlow()
    var saved: BackupKey? = null
    var loadFails = false

    override suspend fun save(key: BackupKey): Boolean {
        saved = BackupKey(key.salt, key.keyCopy())
        mutable.value = true
        return true
    }

    override suspend fun load(): BackupKey? {
        if (loadFails) throw IllegalStateException("keystore")
        return saved?.let { BackupKey(it.salt, it.keyCopy()) }
    }
}

internal class MemoryFolder : BackupFolder {
    val files = linkedMapOf<String, ByteArray>()
    val released = mutableListOf<String>()
    var unreachable = false
    var adoptFails = false

    override suspend fun adopt(folderUri: String): String {
        if (adoptFails) throw SecurityException("no")
        return "Backup"
    }

    override fun release(folderUri: String) {
        released += folderUri
    }

    override suspend fun list(folderUri: String): List<BackupFolderFile> {
        if (unreachable) throw IOException("offline")
        return files.keys.map { BackupFolderFile(it, it) }
    }

    override suspend fun create(folderUri: String, name: String, mimeType: String): BackupFolderFile {
        files[name] = ByteArray(0)
        return BackupFolderFile(name, name)
    }

    override suspend fun write(file: BackupFolderFile, bytes: ByteArray) {
        files[file.name] = bytes
    }

    override suspend fun delete(file: BackupFolderFile) {
        files.remove(file.name)
    }
}

internal class RecordingAlarm : AutoBackupAlarmScheduler {
    var scheduledAt: Long? = null
    var cancelled = false

    override fun schedule(atMillis: Long) {
        scheduledAt = atMillis
        cancelled = false
    }

    override fun cancel() {
        scheduledAt = null
        cancelled = true
    }
}

internal class RecordingNotice : AutoBackupFailureNotice {
    var shown = 0
    var cleared = 0

    override fun show() {
        shown++
    }

    override fun clear() {
        cleared++
    }
}

/** Un coordinatore con tutti i fake, derivazione veloce e salt prevedibili (1…, 2…, …). */
internal class AutoBackupTestRig(
    scope: CoroutineScope,
    var now: Long,
    val zone: ZoneId = ZoneId.of("Europe/Rome"),
    var built: BuiltBackup = BuiltBackup.Ready("""{"format":"argus-rules"}""".toByteArray(), exported = 12, skipped = 0),
) {
    val store = MemoryStateStore()
    val keys = MemoryKeys()
    val folder = MemoryFolder()
    val alarm = RecordingAlarm()
    val notice = RecordingNotice()
    private var salts = 0

    val coordinator = AutoBackupCoordinator(
        scope = scope,
        store = store,
        keys = keys,
        folder = folder,
        runner = AutoBackupRunner(build = { built }, folder = folder, randomBytes = { ByteArray(it) { 4 } }),
        alarm = alarm,
        notice = notice,
        nowMillis = { now },
        zone = { zone },
        newSalt = { (++salts).let { n -> ByteArray(16) { n.toByte() } } },
        deriveKey = { passphrase, salt -> BackupKeyDerivation.derive(passphrase, salt, iterations = FAST_ITERATIONS) },
    )

    companion object {
        const val FAST_ITERATIONS = 1_000
        const val FOLDER = "content://tree/backup"
    }
}
