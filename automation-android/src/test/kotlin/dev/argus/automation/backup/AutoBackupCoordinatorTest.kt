package dev.argus.automation.backup

import dev.argus.data.backup.AutoBackupFailure
import dev.argus.data.backup.AutoBackupFrequency
import dev.argus.data.backup.AutoBackupLastRun
import dev.argus.data.backup.AutoBackupOutcome
import dev.argus.data.backup.AutoBackupRunner
import dev.argus.data.backup.AutoBackupState
import dev.argus.data.backup.BackupFolder
import dev.argus.data.backup.BackupFolderFile
import dev.argus.data.backup.BackupKey
import dev.argus.data.backup.BackupKeyDerivation
import dev.argus.data.backup.BuiltBackup
import dev.argus.data.backup.EncryptedBackupFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoBackupCoordinatorTest {
    private val zone = ZoneId.of("Europe/Rome")
    private var now = at("2026-11-16T14:00")
    private val store = MemoryStateStore()
    private val keys = MemoryKeys()
    private val folder = MemoryFolder()
    private val alarm = RecordingAlarm()
    private val notice = RecordingNotice()
    private var salts = 0
    private val plain = """{"format":"argus-rules"}""".toByteArray()
    private var built: BuiltBackup = BuiltBackup.Ready(plain, exported = 12, skipped = 0)

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun TestScope.coordinator() = AutoBackupCoordinator(
        scope = backgroundScope,
        store = store,
        keys = keys,
        folder = folder,
        runner = AutoBackupRunner(build = { built }, folder = folder, randomBytes = { ByteArray(it) { 4 } }),
        alarm = alarm,
        notice = notice,
        nowMillis = { now },
        zone = { zone },
        newSalt = { ByteArray(16) { (++salts).toByte() } },
        deriveKey = { passphrase, salt -> BackupKeyDerivation.derive(passphrase, salt, iterations = 1_000) },
    )

    private suspend fun AutoBackupCoordinator.ready() {
        assertTrue(setFolder(FOLDER))
        assertTrue(setPassphrase("cavallo-batteria".toCharArray()))
    }

    @Test
    fun `a passphrase shorter than 8 characters is refused and nothing is saved`() = runTest {
        val coordinator = coordinator()

        assertFalse(coordinator.setPassphrase("1234567".toCharArray()))
        assertNull(keys.saved)
        assertFalse(keys.configured.value)
    }

    @Test
    fun `setting the passphrase stores only the salt and the derived key`() = runTest {
        val coordinator = coordinator()

        assertTrue(coordinator.setPassphrase("cavallo-batteria".toCharArray()))

        val saved = requireNotNull(keys.saved)
        assertContentEquals(ByteArray(16) { 1 }, saved.salt)
        assertContentEquals(
            BackupKeyDerivation.derive("cavallo-batteria".toCharArray(), ByteArray(16) { 1 }, iterations = 1_000),
            saved.keyCopy(),
        )
        assertTrue(coordinator.snapshots.first().state.keyConfigured)
        assertFalse(coordinator.snapshots.first().settingPassphrase)
    }

    @Test
    fun `changing the passphrase derives again with a new salt`() = runTest {
        val coordinator = coordinator()
        coordinator.setPassphrase("cavallo-batteria".toCharArray())
        val first = requireNotNull(keys.saved)

        coordinator.setPassphrase("cavallo-batteria".toCharArray())

        val second = requireNotNull(keys.saved)
        assertNotEquals(first.salt.toList(), second.salt.toList())
        assertNotEquals(first.keyCopy().toList(), second.keyCopy().toList())
    }

    @Test
    fun `turning it on when ready writes a first backup at once and schedules the next night`() = runTest {
        val coordinator = coordinator()
        coordinator.ready()

        coordinator.setEnabled(true)
        advanceUntilIdle()

        val state = store.state.value
        assertEquals(AutoBackupLastRun.Written(now, 12), state.lastRun)
        assertEquals(setOf("argus-rules-20261116-1400.argusbackup"), state.writtenNames)
        val file = folder.files.getValue("argus-rules-20261116-1400.argusbackup")
        val key = BackupKeyDerivation.derive("cavallo-batteria".toCharArray(), ByteArray(16) { 1 }, iterations = 1_000)
        assertContentEquals(plain, assertIs<EncryptedBackupFormat.Opened.Plain>(EncryptedBackupFormat.open(file, key)).bytes)
        assertEquals(at("2026-11-17T03:00"), alarm.scheduledAt)
    }

    @Test
    fun `turning it on without a folder or a passphrase runs nothing and sets no alarm`() = runTest {
        val coordinator = coordinator()

        coordinator.setEnabled(true)
        advanceUntilIdle()

        assertNull(store.state.value.lastRun)
        assertTrue(alarm.cancelled)
        assertNull(alarm.scheduledAt)
    }

    @Test
    fun `a backup that is not due does not run, but the alarm is set again`() = runTest {
        val coordinator = coordinator()
        coordinator.ready()
        store.update { it.copy(enabled = true, lastCompletedAtMillis = at("2026-11-16T03:00")) }

        assertNull(coordinator.runIfDue())

        assertTrue(folder.files.isEmpty())
        assertEquals(at("2026-11-17T03:00"), alarm.scheduledAt)
    }

    @Test
    fun `an overdue backup runs on start, as after a reboot`() = runTest {
        val coordinator = coordinator()
        coordinator.ready()
        store.update { it.copy(enabled = true, lastCompletedAtMillis = at("2026-11-10T03:00")) }

        coordinator.start()
        advanceUntilIdle()

        assertIs<AutoBackupLastRun.Written>(store.state.value.lastRun)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun `three failures in a row post one notification, a success clears it`() = runTest {
        val coordinator = coordinator()
        coordinator.ready()
        store.update { it.copy(enabled = true) }
        folder.unreachable = true
        val clearedBefore = notice.cleared

        repeat(4) { coordinator.runNow() }

        assertEquals(1, notice.shown)
        assertEquals(4, store.state.value.consecutiveFailures)
        assertEquals(AutoBackupFailure.FOLDER_UNREACHABLE, (store.state.value.lastRun as AutoBackupLastRun.Failed).failure)
        // Nuovo tentativo dopo il ritardo crescente, non la notte dopo.
        assertEquals(now + 8 * 3_600_000L, alarm.scheduledAt)

        folder.unreachable = false
        assertIs<AutoBackupOutcome.Written>(coordinator.runNow())
        assertEquals(clearedBefore + 1, notice.cleared)
        assertEquals(0, store.state.value.consecutiveFailures)
    }

    @Test
    fun `a key that no longer unwraps is reported, not a crash`() = runTest {
        val coordinator = coordinator()
        coordinator.ready()
        keys.loadFails = true

        assertEquals(AutoBackupOutcome.Failed(AutoBackupFailure.KEY_UNAVAILABLE), coordinator.runNow())
    }

    @Test
    fun `a folder argus cannot use is refused, a new one releases the old and forgets its files`() = runTest {
        val coordinator = coordinator()
        folder.adoptFails = true
        assertFalse(coordinator.setFolder(FOLDER))
        assertNull(store.state.value.folderUri)

        folder.adoptFails = false
        assertTrue(coordinator.setFolder(FOLDER))
        store.update { it.copy(writtenNames = setOf("argus-rules-20261101-0300.argusbackup")) }
        assertTrue(coordinator.setFolder("content://tree/other"))

        assertEquals("content://tree/other", store.state.value.folderUri)
        assertEquals("Backup", store.state.value.folderLabel)
        assertEquals(listOf(FOLDER), folder.released)
        assertTrue(store.state.value.writtenNames.isEmpty())
    }

    @Test
    fun `weekly frequency moves the next alarm a week after the last backup`() = runTest {
        val coordinator = coordinator()
        coordinator.ready()
        store.update { it.copy(enabled = true, lastCompletedAtMillis = at("2026-11-16T03:00")) }

        coordinator.setFrequency(AutoBackupFrequency.WEEKLY)

        assertEquals(AutoBackupFrequency.WEEKLY, store.state.value.frequency)
        assertEquals(at("2026-11-23T03:00"), alarm.scheduledAt)
    }

    @Test
    fun `the alarm finishes the broadcast after the run`() = runTest {
        val coordinator = coordinator()
        coordinator.ready()
        store.update { it.copy(enabled = true) }
        now = at("2026-11-17T03:05")
        var finished = false

        coordinator.onAlarm { finished = true }
        advanceUntilIdle()

        assertTrue(finished)
        assertIs<AutoBackupLastRun.Written>(store.state.value.lastRun)
    }

    // --- fake --------------------------------------------------------------------------------------

    private class MemoryStateStore : AutoBackupStateStore {
        private val mutable = MutableStateFlow(AutoBackupState())
        override val state: StateFlow<AutoBackupState> = mutable.asStateFlow()

        override suspend fun update(transform: (AutoBackupState) -> AutoBackupState): Boolean {
            mutable.value = transform(mutable.value).copy(keyConfigured = false)
            return true
        }
    }

    private class MemoryKeys : AutoBackupKeys {
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

    private class MemoryFolder : BackupFolder {
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

    private class RecordingAlarm : AutoBackupAlarmScheduler {
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

    private class RecordingNotice : AutoBackupFailureNotice {
        var shown = 0
        var cleared = 0

        override fun show() {
            shown++
        }

        override fun clear() {
            cleared++
        }
    }

    private companion object {
        const val FOLDER = "content://tree/backup"
    }
}
