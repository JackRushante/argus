package dev.argus.automation.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.data.backup.AutoBackupFailure
import dev.argus.data.backup.AutoBackupFrequency
import dev.argus.data.backup.AutoBackupLastRun
import dev.argus.data.backup.AutoBackupState
import dev.argus.data.backup.BackupKey
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stato e chiave del backup automatico su SharedPreferences (Robolectric; il Keystore è una chiave software). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoBackupStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val wrapping: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val salt = ByteArray(16) { 7 }
    private val raw = ByteArray(32) { (it * 3).toByte() }

    private fun keys(key: SecretKey = wrapping) = AndroidAutoBackupKeys(
        context.getSharedPreferences("argus_auto_backup_private", Context.MODE_PRIVATE),
        { key },
    )

    private fun stateStore() = SharedPreferencesAutoBackupStateStore(
        context.getSharedPreferences("argus_auto_backup", Context.MODE_PRIVATE),
    )

    @Test
    fun `the key survives a restart and comes back with its salt`() = runTest {
        assertFalse(keys().configured.value)
        assertTrue(keys().save(BackupKey(salt, raw)))

        val reloaded = keys()
        assertTrue(reloaded.configured.value)
        val loaded = assertNotNull(reloaded.load())
        assertContentEquals(salt, loaded.salt)
        assertContentEquals(raw, loaded.keyCopy())
    }

    @Test
    fun `only the wrapped key is stored, never the key in clear`() = runTest {
        keys().save(BackupKey(salt, raw))

        val stored = context.getSharedPreferences("argus_auto_backup_private", Context.MODE_PRIVATE).all
        assertEquals(setOf("salt_v1", "key_v1"), stored.keys)
        val wrapped = java.util.Base64.getDecoder().decode(stored.getValue("key_v1") as String)
        assertFalse(wrapped.toList().windowed(raw.size).any { it == raw.toList() })
    }

    @Test
    fun `a lost keystore key gives no key instead of a crash`() = runTest {
        keys().save(BackupKey(salt, raw))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertNull(keys(other).load())
    }

    @Test
    fun `a swapped salt does not unwrap the key`() = runTest {
        keys().save(BackupKey(salt, raw))
        val prefs = context.getSharedPreferences("argus_auto_backup_private", Context.MODE_PRIVATE)
        prefs.edit().putString("salt_v1", java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 1 })).commit()

        assertNull(keys().load())
    }

    @Test
    fun `the state round trips through a restart`() = runTest {
        val state = AutoBackupState(
            enabled = true,
            frequency = AutoBackupFrequency.WEEKLY,
            folderUri = "content://tree/backup",
            folderLabel = "Nextcloud",
            lastCompletedAtMillis = 1_000,
            lastAttemptAtMillis = 2_000,
            lastRun = AutoBackupLastRun.Failed(2_000, AutoBackupFailure.WRITE_FAILED),
            consecutiveFailures = 3,
            failureNotified = true,
            writtenNames = setOf("argus-rules-20261116-0310.argusbackup"),
        )
        assertTrue(stateStore().update { state })

        assertEquals(state, stateStore().state.value)

        stateStore().update { it.copy(lastRun = AutoBackupLastRun.Written(3_000, 12), folderUri = null) }
        val reread = stateStore().state.value
        assertEquals(AutoBackupLastRun.Written(3_000, 12), reread.lastRun)
        assertNull(reread.folderUri)
    }

    @Test
    fun `a fresh install starts switched off with nothing chosen`() {
        assertEquals(AutoBackupState(), stateStore().state.value)
    }

    @Test
    fun `the state store never claims a key`() = runTest {
        stateStore().update { it.copy(keyConfigured = true) }

        assertFalse(stateStore().state.value.keyConfigured)
    }
}
