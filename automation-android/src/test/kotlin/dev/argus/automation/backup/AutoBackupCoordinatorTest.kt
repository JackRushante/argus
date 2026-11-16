package dev.argus.automation.backup

import dev.argus.automation.backup.AutoBackupTestRig.Companion.FAST_ITERATIONS
import dev.argus.automation.backup.AutoBackupTestRig.Companion.FOLDER
import dev.argus.data.backup.AutoBackupFailure
import dev.argus.data.backup.AutoBackupFrequency
import dev.argus.data.backup.AutoBackupLastRun
import dev.argus.data.backup.AutoBackupOutcome
import dev.argus.data.backup.BackupKeyDerivation
import dev.argus.data.backup.EncryptedBackupFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
class AutoBackupCoordinatorTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val plain = """{"format":"argus-rules"}""".toByteArray()

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // Lo scope del test: advanceUntilIdle aspetta anche i lavori lanciati dal coordinatore.
    private fun TestScope.rig() = AutoBackupTestRig(scope = this, now = at("2026-11-16T14:00"), zone = zone)

    private suspend fun AutoBackupTestRig.ready() {
        assertTrue(coordinator.setFolder(FOLDER))
        assertTrue(coordinator.setPassphrase("cavallo-batteria".toCharArray()))
    }

    private fun key(salt: Int) =
        BackupKeyDerivation.derive("cavallo-batteria".toCharArray(), ByteArray(16) { salt.toByte() }, FAST_ITERATIONS)

    @Test
    fun `a passphrase shorter than 8 characters is refused and nothing is saved`() = runTest {
        val rig = rig()

        assertFalse(rig.coordinator.setPassphrase("1234567".toCharArray()))
        assertNull(rig.keys.saved)
        assertFalse(rig.keys.configured.value)
    }

    @Test
    fun `setting the passphrase stores only the salt and the derived key`() = runTest {
        val rig = rig()

        assertTrue(rig.coordinator.setPassphrase("cavallo-batteria".toCharArray()))

        val saved = requireNotNull(rig.keys.saved)
        assertContentEquals(ByteArray(16) { 1 }, saved.salt)
        assertContentEquals(key(1), saved.keyCopy())
        assertTrue(rig.coordinator.snapshots.first().state.keyConfigured)
        assertFalse(rig.coordinator.snapshots.first().settingPassphrase)
    }

    @Test
    fun `changing the passphrase derives again with a new salt`() = runTest {
        val rig = rig()
        rig.coordinator.setPassphrase("cavallo-batteria".toCharArray())
        val first = requireNotNull(rig.keys.saved)

        rig.coordinator.setPassphrase("cavallo-batteria".toCharArray())

        val second = requireNotNull(rig.keys.saved)
        assertNotEquals(first.salt.toList(), second.salt.toList())
        assertNotEquals(first.keyCopy().toList(), second.keyCopy().toList())
    }

    @Test
    fun `turning it on when ready writes a first backup at once and schedules the next night`() = runTest {
        val rig = rig()
        rig.ready()

        rig.coordinator.setEnabled(true)
        advanceUntilIdle()

        val state = rig.store.state.value
        assertEquals(AutoBackupLastRun.Written(rig.now, 12), state.lastRun)
        assertEquals(setOf("argus-rules-20261116-1400.argusbackup"), state.writtenNames)
        val file = rig.folder.files.getValue("argus-rules-20261116-1400.argusbackup")
        assertContentEquals(plain, assertIs<EncryptedBackupFormat.Opened.Plain>(EncryptedBackupFormat.open(file, key(1))).bytes)
        assertEquals(at("2026-11-17T03:00"), rig.alarm.scheduledAt)
    }

    @Test
    fun `turning it on without a folder or a passphrase runs nothing and sets no alarm`() = runTest {
        val rig = rig()

        rig.coordinator.setEnabled(true)
        advanceUntilIdle()

        assertNull(rig.store.state.value.lastRun)
        assertTrue(rig.alarm.cancelled)
        assertNull(rig.alarm.scheduledAt)
    }

    @Test
    fun `a backup that is not due does not run, but the alarm is set again`() = runTest {
        val rig = rig()
        rig.ready()
        rig.store.update { it.copy(enabled = true, lastCompletedAtMillis = at("2026-11-16T03:00")) }

        assertNull(rig.coordinator.runIfDue())

        assertTrue(rig.folder.files.isEmpty())
        assertEquals(at("2026-11-17T03:00"), rig.alarm.scheduledAt)
    }

    @Test
    fun `an overdue backup runs on start, as after a reboot`() = runTest {
        val rig = rig()
        rig.ready()
        rig.store.update { it.copy(enabled = true, lastCompletedAtMillis = at("2026-11-10T03:00")) }

        rig.coordinator.start()
        advanceUntilIdle()

        assertIs<AutoBackupLastRun.Written>(rig.store.state.value.lastRun)
        assertEquals(1, rig.folder.files.size)
    }

    @Test
    fun `three failures in a row post one notification, a success clears it`() = runTest {
        val rig = rig()
        rig.ready()
        rig.store.update { it.copy(enabled = true) }
        rig.folder.unreachable = true
        val clearedBefore = rig.notice.cleared

        repeat(4) { rig.coordinator.runNow() }

        assertEquals(1, rig.notice.shown)
        assertEquals(4, rig.store.state.value.consecutiveFailures)
        assertEquals(
            AutoBackupFailure.FOLDER_UNREACHABLE,
            (rig.store.state.value.lastRun as AutoBackupLastRun.Failed).failure,
        )
        // Nuovo tentativo dopo il ritardo crescente, non la notte dopo.
        assertEquals(rig.now + 8 * 3_600_000L, rig.alarm.scheduledAt)

        rig.folder.unreachable = false
        assertIs<AutoBackupOutcome.Written>(rig.coordinator.runNow())
        assertEquals(clearedBefore + 1, rig.notice.cleared)
        assertEquals(0, rig.store.state.value.consecutiveFailures)
    }

    @Test
    fun `a key that no longer unwraps is reported, not a crash`() = runTest {
        val rig = rig()
        rig.ready()
        rig.keys.loadFails = true

        assertEquals(AutoBackupOutcome.Failed(AutoBackupFailure.KEY_UNAVAILABLE), rig.coordinator.runNow())
    }

    @Test
    fun `a folder argus cannot use is refused, a new one releases the old and forgets its files`() = runTest {
        val rig = rig()
        rig.folder.adoptFails = true
        assertFalse(rig.coordinator.setFolder(FOLDER))
        assertNull(rig.store.state.value.folderUri)

        rig.folder.adoptFails = false
        assertTrue(rig.coordinator.setFolder(FOLDER))
        rig.store.update { it.copy(writtenNames = setOf("argus-rules-20261101-0300.argusbackup")) }
        assertTrue(rig.coordinator.setFolder("content://tree/other"))

        assertEquals("content://tree/other", rig.store.state.value.folderUri)
        assertEquals("Backup", rig.store.state.value.folderLabel)
        assertEquals(listOf(FOLDER), rig.folder.released)
        assertTrue(rig.store.state.value.writtenNames.isEmpty())
    }

    @Test
    fun `weekly frequency moves the next alarm a week after the last backup`() = runTest {
        val rig = rig()
        rig.ready()
        rig.store.update { it.copy(enabled = true, lastCompletedAtMillis = at("2026-11-16T03:00")) }

        rig.coordinator.setFrequency(AutoBackupFrequency.WEEKLY)

        assertEquals(AutoBackupFrequency.WEEKLY, rig.store.state.value.frequency)
        assertEquals(at("2026-11-23T03:00"), rig.alarm.scheduledAt)
    }

    @Test
    fun `the alarm finishes the broadcast after the run`() = runTest {
        val rig = rig()
        rig.ready()
        rig.store.update { it.copy(enabled = true, lastCompletedAtMillis = at("2026-11-16T03:00")) }
        rig.now = at("2026-11-17T03:05")
        var finished = false

        rig.coordinator.onAlarm { finished = true }
        advanceUntilIdle()

        assertTrue(finished)
        assertEquals(AutoBackupLastRun.Written(rig.now, 12), rig.store.state.value.lastRun)
    }
}
