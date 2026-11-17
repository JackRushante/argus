package dev.argus.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Un'esecuzione del backup automatico contro una cartella finta: puro JVM. */
class AutoBackupRunnerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = LocalDateTime.parse("2026-11-16T03:10").atZone(zone).toInstant().toEpochMilli()
    private val salt = ByteArray(16) { 3 }
    private val keyBytes = BackupKeyDerivation.derive("cavallo-batteria".toCharArray(), salt, iterations = 1_000)
    private val key = BackupKey(salt, keyBytes)
    private val plain = """{"format":"argus-rules"}""".toByteArray()
    private val folder = FakeFolder()
    private var built: BuiltBackup = BuiltBackup.Ready(plain, exported = 12, skipped = 0)
    private val state = AutoBackupState(enabled = true, folderUri = FOLDER, keyConfigured = true)

    private fun runner() = AutoBackupRunner(build = { built }, folder = folder, randomBytes = { ByteArray(it) { 9 } })

    @Test
    fun `a run writes one encrypted file with the full backup that the passphrase opens`() = runTest {
        val result = runner().run(state, key, now, zone)

        val written = assertIs<AutoBackupOutcome.Written>(result.outcome)
        assertEquals(12, written.rules)
        assertEquals("argus-rules-20261116-0310.argusbackup", written.fileName)
        assertEquals(setOf("argus-rules-20261116-0310.argusbackup"), result.writtenNames)
        val bytes = folder.files.getValue("argus-rules-20261116-0310.argusbackup")
        assertEquals(EncryptedBackupFormat.MIME_TYPE, folder.mimeTypes.single())
        val opened = assertIs<EncryptedBackupFormat.Opened.Plain>(
            EncryptedBackupFormat.open(
                bytes,
                BackupKeyDerivation.derive("cavallo-batteria".toCharArray(), salt, iterations = 1_000),
            ),
        )
        assertContentEquals(plain, opened.bytes)
    }

    @Test
    fun `retention keeps the seven newest of its own files and leaves the user's files alone`() = runTest {
        val ours = (1..8).map { "argus-rules-202611%02d-0300.argusbackup".format(it) }
        ours.forEach { folder.files[it] = byteArrayOf(1) }
        folder.files["argus-rules-20200101-0000.argusbackup"] = byteArrayOf(1) // dell'utente
        folder.files["note.txt"] = byteArrayOf(1)

        val result = runner().run(state.copy(writtenNames = ours.toSet()), key, now, zone)

        val written = assertIs<AutoBackupOutcome.Written>(result.outcome)
        assertEquals(2, written.deleted)
        assertEquals(
            setOf(
                "argus-rules-20200101-0000.argusbackup",
                "note.txt",
                "argus-rules-20261103-0300.argusbackup",
                "argus-rules-20261104-0300.argusbackup",
                "argus-rules-20261105-0300.argusbackup",
                "argus-rules-20261106-0300.argusbackup",
                "argus-rules-20261107-0300.argusbackup",
                "argus-rules-20261108-0300.argusbackup",
                "argus-rules-20261116-0310.argusbackup",
            ),
            folder.files.keys,
        )
        assertEquals(7, result.writtenNames.size)
        assertTrue("argus-rules-20200101-0000.argusbackup" !in result.writtenNames)
    }

    @Test
    fun `a file removed by hand leaves the list of written files`() = runTest {
        val result = runner().run(state.copy(writtenNames = setOf("argus-rules-20261101-0300.argusbackup")), key, now, zone)

        assertEquals(setOf("argus-rules-20261116-0310.argusbackup"), result.writtenNames)
    }

    @Test
    fun `two runs in the same minute rewrite argus's own file instead of making a copy`() = runTest {
        val first = runner().run(state, key, now, zone)
        val second = runner().run(state.copy(writtenNames = first.writtenNames), key, now, zone)

        assertIs<AutoBackupOutcome.Written>(second.outcome)
        assertEquals(1, folder.created)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun `a same-named file argus did not write is never overwritten`() = runTest {
        folder.files["argus-rules-20261116-0310.argusbackup"] = byteArrayOf(42)

        val result = runner().run(state, key, now, zone)

        val written = assertIs<AutoBackupOutcome.Written>(result.outcome)
        assertEquals("argus-rules-20261116-0310 (1).argusbackup", written.fileName)
        assertContentEquals(byteArrayOf(42), folder.files.getValue("argus-rules-20261116-0310.argusbackup"))
    }

    @Test
    fun `an unreachable folder fails without writing and keeps the list`() = runTest {
        folder.unreachable = true
        val names = setOf("argus-rules-20261101-0300.argusbackup")

        val result = runner().run(state.copy(writtenNames = names), key, now, zone)

        assertEquals(AutoBackupOutcome.Failed(AutoBackupFailure.FOLDER_UNREACHABLE), result.outcome)
        assertEquals(names, result.writtenNames)
    }

    @Test
    fun `a revoked permission counts as an unreachable folder`() = runTest {
        folder.listError = SecurityException("permesso revocato")

        assertEquals(
            AutoBackupOutcome.Failed(AutoBackupFailure.FOLDER_UNREACHABLE),
            runner().run(state, key, now, zone).outcome,
        )
    }

    @Test
    fun `a failed write removes the half written file and deletes nothing else`() = runTest {
        val ours = (1..8).map { "argus-rules-202611%02d-0300.argusbackup".format(it) }
        ours.forEach { folder.files[it] = byteArrayOf(1) }
        folder.writeFails = true

        val result = runner().run(state.copy(writtenNames = ours.toSet()), key, now, zone)

        assertEquals(AutoBackupOutcome.Failed(AutoBackupFailure.WRITE_FAILED), result.outcome)
        assertEquals(ours.toSet(), folder.files.keys)
    }

    @Test
    fun `no rules means nothing written and nothing deleted`() = runTest {
        built = BuiltBackup.Failed(BackupFailure.NOTHING_TO_EXPORT)
        folder.files["argus-rules-20261101-0300.argusbackup"] = byteArrayOf(1)

        val result = runner().run(state, key, now, zone)

        assertEquals(AutoBackupOutcome.NothingToBackUp, result.outcome)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun `missing folder, missing key and an oversized export are reported`() = runTest {
        assertEquals(
            AutoBackupOutcome.Failed(AutoBackupFailure.FOLDER_NOT_SET),
            runner().run(state.copy(folderUri = null), key, now, zone).outcome,
        )
        assertEquals(
            AutoBackupOutcome.Failed(AutoBackupFailure.KEY_UNAVAILABLE),
            runner().run(state, null, now, zone).outcome,
        )
        built = BuiltBackup.Failed(BackupFailure.EXPORT_TOO_LARGE)
        assertEquals(AutoBackupOutcome.Failed(AutoBackupFailure.TOO_LARGE), runner().run(state, key, now, zone).outcome)
        built = BuiltBackup.Failed(BackupFailure.WRITE_FAILED)
        assertEquals(AutoBackupOutcome.Failed(AutoBackupFailure.READ_FAILED), runner().run(state, key, now, zone).outcome)
        assertTrue(folder.files.isEmpty())
    }

    private class FakeFolder : BackupFolder {
        val files = linkedMapOf<String, ByteArray>()
        val mimeTypes = mutableListOf<String>()
        var created = 0
        var unreachable = false
        var listError: RuntimeException? = null
        var writeFails = false

        override suspend fun adopt(folderUri: String): String = "Backup"
        override fun release(folderUri: String) = Unit

        override suspend fun list(folderUri: String): List<BackupFolderFile> {
            if (unreachable) throw IOException("offline")
            listError?.let { throw it }
            return files.keys.map { BackupFolderFile(it, "uri:$it") }
        }

        override suspend fun create(folderUri: String, name: String, mimeType: String): BackupFolderFile {
            created++
            mimeTypes += mimeType
            // Come i provider veri: un nome già usato prende un suffisso.
            val actual = if (name in files) name.replace(".argusbackup", " (1).argusbackup") else name
            files[actual] = ByteArray(0)
            return BackupFolderFile(actual, "uri:$actual")
        }

        override suspend fun write(file: BackupFolderFile, bytes: ByteArray) {
            if (writeFails) throw IOException("disco pieno")
            files[file.name] = bytes
        }

        override suspend fun delete(file: BackupFolderFile) {
            files.remove(file.name)
        }
    }

    private companion object {
        const val FOLDER = "content://tree/backup"
    }
}
