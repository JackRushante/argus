package dev.argus.data.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * La cartella del backup automatico come albero del Storage Access Framework (`OpenDocumentTree`), con
 * il permesso persistente di lettura e scrittura. Nessun permesso di storage: solo quella cartella.
 */
class DocumentTreeBackupFolder(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupFolder {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun adopt(folderUri: String): String = withContext(dispatcher) {
        val tree = Uri.parse(folderUri)
        resolver.takePersistableUriPermission(tree, READ_WRITE)
        displayName(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
            ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
    }

    override fun release(folderUri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(folderUri), READ_WRITE)
        } catch (_: RuntimeException) {
            // Permesso già perso: niente da lasciare.
        }
    }

    override suspend fun list(folderUri: String): List<BackupFolderFile> = withContext(dispatcher) {
        val tree = Uri.parse(folderUri)
        requirePermission(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val cursor = resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: throw IOException("cartella non leggibile")
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    add(BackupFolderFile(name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()))
                }
            }
        }
    }

    override suspend fun create(folderUri: String, name: String, mimeType: String): BackupFolderFile =
        withContext(dispatcher) {
            val tree = Uri.parse(folderUri)
            requirePermission(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
                ?: throw IOException("file non creato")
            BackupFolderFile(displayName(created) ?: name, created.toString())
        }

    override suspend fun write(file: BackupFolderFile, bytes: ByteArray) {
        withContext(dispatcher) {
            val stream = resolver.openOutputStream(Uri.parse(file.uri), "wt")
                ?: throw FileNotFoundException("documento non apribile")
            stream.use { it.write(bytes) }
        }
    }

    override suspend fun delete(file: BackupFolderFile) {
        withContext(dispatcher) {
            if (!DocumentsContract.deleteDocument(resolver, Uri.parse(file.uri))) {
                throw IOException("file non cancellato")
            }
        }
    }

    /** Senza il permesso persistente la cartella non è più nostra: «non raggiungibile», non un crash. */
    private fun requirePermission(tree: Uri) {
        val granted = resolver.persistedUriPermissions.any { permission ->
            permission.uri == tree && permission.isReadPermission && permission.isWritePermission
        }
        if (!granted) throw IOException("permesso sulla cartella perso")
    }

    private fun displayName(document: Uri): String? = try {
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
