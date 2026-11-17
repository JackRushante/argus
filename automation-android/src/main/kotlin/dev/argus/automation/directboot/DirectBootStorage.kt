package dev.argus.automation.directboot

import android.content.Context
import dev.argus.engine.directboot.DirectBootRecord
import dev.argus.engine.directboot.DirectBootRecords
import dev.argus.engine.directboot.DirectBootSessionState
import dev.argus.engine.model.ArgusJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 0.8, rules before the first unlock: the only files the feature keeps, in DEVICE-encrypted storage (readable before
 * the first unlock, unlike the database and the preferences). App-private (`/data/user_de/<user>/<package>`): no other
 * app can read or write them, and the backup rules exclude every device-encrypted domain.
 *
 * - `copy.json`: the opted-in, qualifying, armed rules (written only by the normal runtime, after unlock);
 * - `queue.jsonl`: closed-code records of what the locked path did (imported into the Log after unlock);
 * - `session.json`: what each rule of the locked session waits for;
 * - `handover`: the boot in which the normal runtime took over (the locked path stands down for that boot).
 *
 * Two processes use these files (the locked one before unlock, the main one after): every access goes through
 * [locked], a file lock across processes plus a mutex inside each process.
 */
class DirectBootStorage(private val dir: File) {
    constructor(context: Context) : this(File(context.createDeviceProtectedStorageContext().filesDir, DIR))

    interface Files {
        fun readCopy(): String?

        /** null removes the copy. */
        fun writeCopy(text: String?)
        fun records(): List<DirectBootRecord>

        /** false when the record could not be written (full queue, storage error): the caller must not act. */
        fun append(record: DirectBootRecord): Boolean
        fun clearRecords()
        fun readSession(): DirectBootSessionState?
        fun writeSession(state: DirectBootSessionState?)
        fun handedOverBoot(): Int?
        fun markHandedOver(boot: Int)
    }

    suspend fun <T> locked(block: suspend Files.() -> T): T = PROCESS_LOCK.withLock {
        dir.mkdirs()
        RandomAccessFile(File(dir, LOCK_FILE), "rw").use { file ->
            file.channel.lock().use { block(files) }
        }
    }

    private val files = object : Files {
        override fun readCopy(): String? = read(COPY_FILE)

        override fun writeCopy(text: String?) {
            if (text == null) File(dir, COPY_FILE).delete() else writeAtomically(COPY_FILE, text)
        }

        override fun records(): List<DirectBootRecord> = read(QUEUE_FILE)?.let(DirectBootRecords::decode).orEmpty()

        override fun append(record: DirectBootRecord): Boolean = runCatching {
            val line = DirectBootRecords.encode(record)
            val queue = File(dir, QUEUE_FILE)
            if (queue.exists() && (queue.length() > MAX_QUEUE_BYTES || lineCount(queue) >= DirectBootRecords.MAX_RECORDS)) {
                return@runCatching false
            }
            FileOutputStream(queue, true).use { out ->
                out.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                out.fd.sync()
            }
            true
        }.getOrDefault(false)

        override fun clearRecords() {
            File(dir, QUEUE_FILE).delete()
        }

        override fun readSession(): DirectBootSessionState? = read(SESSION_FILE)?.let { text ->
            runCatching { ArgusJson.decodeFromString(DirectBootSessionState.serializer(), text) }.getOrNull()
        }

        override fun writeSession(state: DirectBootSessionState?) {
            if (state == null) {
                File(dir, SESSION_FILE).delete()
            } else {
                writeAtomically(SESSION_FILE, ArgusJson.encodeToString(DirectBootSessionState.serializer(), state))
            }
        }

        override fun handedOverBoot(): Int? = read(HANDOVER_FILE)?.trim()?.toIntOrNull()

        override fun markHandedOver(boot: Int) = writeAtomically(HANDOVER_FILE, boot.toString())
    }

    private fun read(name: String): String? {
        val file = File(dir, name)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        return runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
    }

    /** Temporary file then rename: a reader sees the old content or the new one, never half of it. */
    private fun writeAtomically(name: String, text: String) {
        val target = File(dir, name)
        val temporary = File(dir, "$name.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
        check(temporary.renameTo(target)) { "rename failed" }
    }

    private fun lineCount(file: File): Int = file.useLines { lines -> lines.count() }

    companion object {
        internal const val DIR = "before_unlock"
        internal const val COPY_FILE = "copy.json"
        internal const val QUEUE_FILE = "queue.jsonl"
        internal const val SESSION_FILE = "session.json"
        internal const val HANDOVER_FILE = "handover"
        private const val LOCK_FILE = ".lock"
        private const val MAX_FILE_BYTES = 1L * 1024 * 1024
        private const val MAX_QUEUE_BYTES = 768L * 1024
        private val PROCESS_LOCK = Mutex()
    }
}
