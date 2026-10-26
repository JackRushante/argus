package dev.argus.data.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/** Lettura e scrittura dei documenti scelti dall'utente con il Storage Access Framework. */
interface BackupDocumentIo {
    /**
     * Legge al massimo [maxBytes] byte. Restituisce null se il contenuto è PIÙ grande (senza leggerlo
     * tutto in memoria); lancia [IOException] se il documento non si può leggere.
     */
    suspend fun read(source: Uri, maxBytes: Int): ByteArray?

    /** Scrive [bytes] sostituendo il contenuto; lancia [IOException] se non riesce. */
    suspend fun write(destination: Uri, bytes: ByteArray)
}

class ContentResolverDocumentIo(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupDocumentIo {
    override suspend fun read(source: Uri, maxBytes: Int): ByteArray? = withContext(dispatcher) {
        val stream = context.contentResolver.openInputStream(source)
            ?: throw FileNotFoundException("documento non apribile")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return@use null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    override suspend fun write(destination: Uri, bytes: ByteArray) {
        withContext(dispatcher) {
            val stream = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw FileNotFoundException("documento non apribile")
            stream.use { it.write(bytes) }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 8 * 1024
    }
}
